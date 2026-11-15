# Opt-in map integration tests; follows Verify-Authentication isolation and identity preflight. Does not read .env or use Compose/development DBs.
param([string]$JavaHome = $env:JAVA_HOME, [string]$ReferenceDump)
$ErrorActionPreference = 'Stop'
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to a Java 21 JDK or pass -JavaHome.'
}
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$container = 'parking-map-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$report = Join-Path $repo "build/map-verification/$container"
New-Item -ItemType Directory -Path $report -Force | Out-Null
$password = [guid]::NewGuid().ToString('N')
$identity = [guid]::NewGuid().ToString('N')
function Invoke-MapRiskZonesDocker {
    param([string[]]$DockerArgs)
    $ErrorActionPreference = 'Continue'
    & docker @DockerArgs
    if ($LASTEXITCODE -ne 0) { throw 'MapRiskZones verification Docker command failed.' }
}
function Invoke-MapRiskGradle {
    param([string]$Suite, [string]$LogName = 'gradle.log')
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $env:SystemRoot 'System32/cmd.exe'
    $start.Arguments = "/d /c gradlew.bat postgresTest --tests $Suite --no-daemon"
    $start.WorkingDirectory = $repo
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.EnvironmentVariables.Clear()
    foreach ($key in @('SystemRoot','WINDIR','TEMP','TMP','USERPROFILE','HOMEDRIVE','HOMEPATH','PATH','APPDATA','LOCALAPPDATA')) {
        $value=[Environment]::GetEnvironmentVariable($key,'Process')
        if ($null -ne $value) { $start.EnvironmentVariables[$key]=$value }
    }
    $start.EnvironmentVariables['JAVA_HOME'] = (Resolve-Path -LiteralPath $JavaHome).Path
    $start.EnvironmentVariables['AUTH_TEST_JDBC_URL'] = "jdbc:postgresql://127.0.0.1:$port/auth_verify"
    $start.EnvironmentVariables['AUTH_TEST_PASSWORD'] = $password
    $start.EnvironmentVariables['AUTH_TEST_IDENTITY'] = $identity
    $child = [Diagnostics.Process]::new()
    $child.StartInfo = $start
    $started = $false
    try {
        if (!$child.Start()) { throw 'Cannot start map verification Gradle process.' }
        $started = $true
        $stdout = $child.StandardOutput.ReadToEndAsync()
        $stderr = $child.StandardError.ReadToEndAsync()
        $child.WaitForExit()
        $log = ($stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()).Replace($password,'[REDACTED]')
        [IO.File]::WriteAllText((Join-Path $report $LogName),$log,[Text.UTF8Encoding]::new($false))
        if ($child.ExitCode -ne 0) { throw "MapRiskZones tests failed; see $report/$LogName" }
    } finally {
        if ($started -and !$child.HasExited) { $child.Kill() }
        $child.Dispose()
    }
}
$created = $false
try {
    Invoke-MapRiskZonesDocker @('run','-d','--name',$container,'--tmpfs','/var/lib/postgresql/data',
        '-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_DB=auth_verify',
        '-p','127.0.0.1::5432','postgis/postgis:16-3.6') | Out-Null
    $created = $true
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        & docker exec $container pg_isready -h 127.0.0.1 -U postgres -d auth_verify *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'MapRiskZones test DB did not become ready.' }
    $port = ((Invoke-MapRiskZonesDocker @('port',$container,'5432/tcp')).Trim() -split ':')[-1]
    if ($port -notmatch '^\d+$') { throw 'Invalid isolated DB port.' }
    Invoke-MapRiskZonesDocker @('cp',(Join-Path $repo 'scripts/db/bootstrap.sql'),"${container}:/tmp/bootstrap.sql")
    Invoke-MapRiskZonesDocker @('exec',$container,'psql','-X','-U','postgres','-d','auth_verify','-f','/tmp/bootstrap.sql') | Out-Null
    $bootstrap = "ALTER ROLE parking_migrator PASSWORD '$password'; ALTER ROLE parking_api PASSWORD '$password'; " +
        "ALTER DATABASE auth_verify SET verification.identity TO '$identity';"
    # Credentials go through stdin, never shell arguments or report output.
    $bootstrap | & docker exec -i $container psql -X -U postgres -d auth_verify -v ON_ERROR_STOP=1 -q
    if ($LASTEXITCODE -ne 0) { throw 'Cannot provision isolated map roles.' }
    if ($ReferenceDump) {
        $dumpPath=(Resolve-Path -LiteralPath $ReferenceDump).Path
        Invoke-MapRiskZonesDocker @('cp',$dumpPath,"${container}:/tmp/reference.dump")
        # Real Flyway history and latest grants, with identity checks before any migration.
        Invoke-MapRiskGradle 'io.github.yongyong14.parking_server.map.MapRiskDumpPostgresTest.migrateFreshReferenceDatabase' 'migration.log'
        # Empty disposable DB only; all canonical guards remain installed and enabled.
        # Never import user/account/event/log tables, dump schema, ACLs, functions or Flyway history.
        foreach ($table in @('data_imports','zone_types','zone_rule_sets','zone_rules','spatial_grids',
            'geocoded_parcels','source_availability','parcel_report_stats','facilities','holidays',
            'report_aggregates')) {
            Invoke-MapRiskZonesDocker @('exec',$container,'pg_restore','--data-only','--no-owner','--no-privileges',
                '--exit-on-error','-U','parking_migrator','-d','auth_verify','-t',$table,'/tmp/reference.dump')
        }
        # Preserve original version metadata in a staging table; public parents start unfrozen.
        "CREATE SCHEMA map_restore; CREATE TABLE map_restore.prediction_versions (LIKE public.prediction_versions INCLUDING DEFAULTS);" |
            & docker exec -i $container psql -X -U parking_migrator -d auth_verify -v ON_ERROR_STOP=1 -q
        if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare reference staging.' }
        $versionData=Invoke-MapRiskZonesDocker @('exec',$container,'pg_restore','--data-only','--no-owner','--no-privileges',
            '-t','prediction_versions','-f','-','/tmp/reference.dump')
        ($versionData -replace 'COPY public.prediction_versions','COPY map_restore.prediction_versions') |
            & docker exec -i $container psql -X -U parking_migrator -d auth_verify -v ON_ERROR_STOP=1 -q
        if ($LASTEXITCODE -ne 0) { throw 'Cannot stage reference versions.' }
        "INSERT INTO public.prediction_versions SELECT (jsonb_populate_record(NULL::public.prediction_versions,to_jsonb(v)||jsonb_build_object('active',false,'frozen_at',null,'validated_at',null,'loaded_row_count',0))).* FROM map_restore.prediction_versions v;" |
            & docker exec -i $container psql -X -U parking_migrator -d auth_verify -v ON_ERROR_STOP=1 -q
        if ($LASTEXITCODE -ne 0) { throw 'Cannot load unfrozen reference versions.' }
        foreach ($table in @('prediction_target_grids','risk_predictions')) {
            # pg_restore clears search_path, while the existing guard uses unqualified table names.
            # Change only that restore-session setting; never alter or disable the guard functions.
            Invoke-MapRiskZonesDocker @('exec',$container,'pg_restore','--data-only','--no-owner','--no-privileges',
                '-t',$table,'-f','/tmp/guarded-reference.sql','/tmp/reference.dump')
            Invoke-MapRiskZonesDocker @('exec',$container,'sed','-i',
                "s/set_config('search_path', '', false)/set_config('search_path', 'public, pg_catalog', false)/g",'/tmp/guarded-reference.sql')
            Invoke-MapRiskZonesDocker @('exec',$container,'psql','-X','-U','parking_migrator','-d','auth_verify',
                '-v','ON_ERROR_STOP=1','-q','-f','/tmp/guarded-reference.sql') | Out-Null
        }
        @'
UPDATE public.prediction_versions p SET frozen_at=v.frozen_at,validated_at=v.validated_at
FROM map_restore.prediction_versions v WHERE p.id=v.id AND v.frozen_at IS NOT NULL;
DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM public.prediction_versions p JOIN map_restore.prediction_versions v USING(id)
    WHERE p.loaded_row_count<>v.loaded_row_count OR p.frozen_at IS DISTINCT FROM v.frozen_at
       OR p.validated_at IS DISTINCT FROM v.validated_at) THEN RAISE EXCEPTION 'Restored version mismatch'; END IF;
END $$;
SELECT public.activate_prediction_version(id) FROM map_restore.prediction_versions WHERE active;
DROP TABLE map_restore.prediction_versions;
DROP SCHEMA map_restore;
'@ | & docker exec -i $container psql -X -U parking_migrator -d auth_verify -v ON_ERROR_STOP=1 -q
        if ($LASTEXITCODE -ne 0) { throw 'Cannot validate, freeze and activate restored reference versions.' }
    }
    Invoke-MapRiskZonesDocker @('exec',$container,'sed','-i','/^host/s/trust/scram-sha-256/g',
        '/var/lib/postgresql/data/pg_hba.conf')
    Invoke-MapRiskZonesDocker @('exec',$container,'psql','-X','-U','postgres','-d','auth_verify',
        '-c','SELECT pg_reload_conf();') | Out-Null

    $suite = 'io.github.yongyong14.parking_server.map.MapRiskPostgresTest'
    if ($ReferenceDump) { $suite = 'io.github.yongyong14.parking_server.map.MapRiskDumpPostgresTest.referenceDumpMatchesServingCountsAndApplicationPrivileges' }
    Invoke-MapRiskGradle $suite
    $results = Join-Path $repo 'build/test-results/postgresTest/TEST-io.github.yongyong14.parking_server.map.MapRiskPostgresTest.xml'
    if ($ReferenceDump) { $results=$results.Replace('MapRiskPostgresTest','MapRiskDumpPostgresTest') }
    [xml]$xml = [IO.File]::ReadAllText($results)
    if ([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -ne 0 -or
        [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0) {
        throw 'Expected one passing map risk zones integration test.'
    }
    Copy-Item -LiteralPath $results -Destination (Join-Path $report 'tests.xml')
    Invoke-MapRiskZonesDocker @('exec',$container,'psql','-X','-U','postgres','-d','auth_verify',
        '-c',"SELECT version(); SELECT table_schema,table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name;") > (Join-Path $report 'database.log')
    Write-Output 'PASS: PostGIS map bounds, facilities, frozen predictions, report lineage and application SELECT grants.'
    Write-Output "Reports: $report"
} finally {
    if ($created) { & docker stop $container | Out-Null }
}
