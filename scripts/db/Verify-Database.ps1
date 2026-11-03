# Build first with Java 21: ./gradlew.bat test bootJar
# Creates a NEW isolated container; never reads .env or touches Compose services.
param([string]$Java = (Join-Path $env:JAVA_HOME 'bin/java.exe'))
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$jar = Join-Path $repo 'build/libs/parking-server-0.0.1-SNAPSHOT.jar'
if (!(Test-Path $jar)) { throw 'Run gradlew test bootJar first.' }
$container = 'parking-flyway-verify-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$output = Join-Path $repo "build/db-verification/$container"
New-Item -ItemType Directory -Path $output -Force | Out-Null
# Extract exactly the built application's classes/libraries. Do not use parent CLASSPATH.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$runtime = Join-Path $output 'runtime'
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($entry in $archive.Entries) {
        if (!$entry.Name -or $entry.FullName -notmatch '^BOOT-INF/(classes|lib)/') { continue }
        $destination = [IO.Path]::GetFullPath((Join-Path $runtime $entry.FullName.Substring(9)))
        if (!$destination.StartsWith([IO.Path]::GetFullPath($runtime) + [IO.Path]::DirectorySeparatorChar)) {
            throw 'Invalid runtime archive path.'
        }
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
    }
} finally { $archive.Dispose() }
$appPassword = [guid]::NewGuid().ToString('N')
$migrationPassword = [guid]::NewGuid().ToString('N')
$identity = [guid]::NewGuid().ToString('N')
$dbPort = $null
$children = [Collections.Generic.List[object]]::new()
function Start-VerificationJvm {
    param([string]$Database, [string]$LogName, [switch]$Target4, [switch]$Http, [string]$ExpectedIdentity = $identity)
    if ($Database -notin @('parking_verify','parking_upgrade','parking_blocked') -or $dbPort -notmatch '^\d+$') {
        throw 'Invalid isolated database target.'
    }
    $classpath = (Join-Path $runtime 'classes') + [IO.Path]::PathSeparator + (Join-Path $runtime 'lib/*')
    $launcher = Join-Path $PSScriptRoot 'VerificationLauncher.java'
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Java
    $start.Arguments = '--class-path "' + $classpath + '" "' + $launcher + '"'
    if ($Target4) { $start.Arguments += ' --target4' }
    if ($Http) { $start.Arguments += ' --http' }
    $start.WorkingDirectory = $output
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # Allowlist instead of a blacklist: no SPRING_*, JVM option hooks, CLASSPATH,
    # external configuration paths, JNDI settings or parent application credentials.
    $start.EnvironmentVariables.Clear()
    foreach ($key in @('SystemRoot','WINDIR','TEMP','TMP','USERPROFILE','HOMEDRIVE','HOMEPATH','PATH')) {
        $value = [Environment]::GetEnvironmentVariable($key, 'Process')
        if ($null -ne $value) { $start.EnvironmentVariables[$key] = $value }
    }
    $start.EnvironmentVariables['VERIFY_URL'] = "jdbc:postgresql://127.0.0.1:$dbPort/${Database}?currentSchema=public"
    $start.EnvironmentVariables['VERIFY_DATABASE'] = $Database
    $start.EnvironmentVariables['VERIFY_IDENTITY'] = $ExpectedIdentity
    $start.EnvironmentVariables['VERIFY_APP_PASSWORD'] = $appPassword
    $start.EnvironmentVariables['VERIFY_MIGRATION_PASSWORD'] = $migrationPassword
    $readyFile = Join-Path $output "$LogName.ready"
    $start.EnvironmentVariables['VERIFY_READY_FILE'] = $readyFile
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (!$process.Start()) { throw 'Cannot start isolated verification JVM.' }
    $child = [pscustomobject]@{
        Process = $process
        Stdout = $process.StandardOutput.ReadToEndAsync()
        Stderr = $process.StandardError.ReadToEndAsync()
        Log = (Join-Path $output "$LogName.log")
        ReadyFile = $readyFile
    }
    $children.Add($child)
    return $child
}
function Complete-VerificationJvm {
    param($Child)
    $Child.Process.WaitForExit()
    $text = $Child.Stdout.GetAwaiter().GetResult() + $Child.Stderr.GetAwaiter().GetResult()
    $text = $text.Replace($appPassword, '[REDACTED]').Replace($migrationPassword, '[REDACTED]')
    [IO.File]::WriteAllText($Child.Log, $text, [Text.UTF8Encoding]::new($false))
    return $Child.Process.ExitCode
}
function Invoke-VerificationJvm {
    param([string]$Database, [string]$LogName, [switch]$Target4)
    $child = Start-VerificationJvm -Database $Database -LogName $LogName -Target4:$Target4
    if ((Complete-VerificationJvm $child) -ne 0) { throw "Verification JVM failed; see $($child.Log)" }
}
function Invoke-Docker {
    param([string[]]$DockerArgs)
    $ErrorActionPreference = 'Continue' # psql NOTICE messages are native stderr, not failure.
    & docker @DockerArgs
    if ($LASTEXITCODE -ne 0) { throw "Docker verification command failed ($LASTEXITCODE)." }
}
$created = $false
try {
    # Trust is ONLY for this temporary loopback container; never use for the dev DB.
    Invoke-Docker @('run', '-d', '--name', $container, '--tmpfs', '/var/lib/postgresql/data',
        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_DB=parking_verify',
        '-p', '127.0.0.1::5432', 'postgis/postgis:16-3.6') | Out-Null
    $created = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec $container pg_isready -h 127.0.0.1 -U postgres -d parking_verify *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Isolated PostgreSQL did not become ready.' }
    $portMapping = Invoke-Docker @('port', $container, '5432/tcp')
    $dbPort = ($portMapping.Trim() -split ':')[-1]
    Invoke-Docker @('cp', (Join-Path $PSScriptRoot 'bootstrap.sql'), "${container}:/tmp/bootstrap.sql")
    Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', 'parking_verify',
        '-v', 'ON_ERROR_STOP=1', '-f', '/tmp/bootstrap.sql') > (Join-Path $output 'bootstrap.log')
    Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', 'parking_verify',
        '-v', 'ON_ERROR_STOP=1', '-c',
        "ALTER ROLE parking_api PASSWORD '$appPassword'; ALTER ROLE parking_migrator PASSWORD '$migrationPassword'; ALTER DATABASE parking_verify SET verification.identity TO '$identity';") | Out-Null
    # Keep local Unix-socket admin access, but require real SCRAM passwords for JDBC TCP.
    Invoke-Docker @('exec', $container, 'sed', '-i', '/^host/s/trust/scram-sha-256/g',
        '/var/lib/postgresql/data/pg_hba.conf')
    Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', 'parking_verify',
        '-c', 'SELECT pg_reload_conf();') | Out-Null
    # Negative control: even a valid endpoint/role must fail on a wrong DB marker,
    # before Flyway is permitted to create its history schema or business objects.
    $rejected = Start-VerificationJvm -Database 'parking_verify' -LogName 'preflight-rejected' -ExpectedIdentity 'wrong-marker'
    if ((Complete-VerificationJvm $rejected) -eq 0 -or
        !(Select-String -LiteralPath $rejected.Log -SimpleMatch 'Verification preflight rejected' -Quiet)) {
        throw 'Wrong database identity was not rejected.'
    }
    Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', 'parking_verify',
        '-v', 'ON_ERROR_STOP=1', '-c',
        "DO `$`$ BEGIN IF to_regclass('flyway.flyway_schema_history') IS NOT NULL OR to_regclass('public.users') IS NOT NULL THEN RAISE EXCEPTION 'Preflight ran too late'; END IF; END `$`$;") | Out-Null
    foreach ($run in 1..2) { Invoke-VerificationJvm 'parking_verify' "startup-$run" }
    if (!(Select-String -LiteralPath (Join-Path $output 'startup-1.log') -Pattern 'Successfully applied 5 migrations' -Quiet)) {
        throw 'First startup did not apply all five migrations.'
    }
    if (!(Select-String -LiteralPath (Join-Path $output 'startup-2.log') -Pattern 'No migration necessary' -Quiet)) {
        throw 'Second startup did not report an up-to-date schema.'
    }
    foreach ($file in @('verify-catalog.sql', 'verify-domain.sql', 'verify-parking-lots.sql')) {
        Invoke-Docker @('cp', (Join-Path $PSScriptRoot $file), "${container}:/tmp/$file")
        Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', 'parking_verify',
            '-v', 'ON_ERROR_STOP=1', '-f', "/tmp/$file") *> (Join-Path $output "$file.log")
    }
    Invoke-Docker @('cp', (Join-Path $PSScriptRoot 'tests_v5.sql'), "${container}:/tmp/tests_v5.sql")
    Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', 'parking_verify',
        '-v', 'ON_ERROR_STOP=1', '-f', '/tmp/tests_v5.sql') *> (Join-Path $output 'regression.log')
    # Normalize only the temporary copy, never the reference file.
    $concurrencyCopy = Join-Path $output 'tests_concurrency.sh'
    [IO.File]::WriteAllText($concurrencyCopy,
        [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'tests_concurrency.sh')).Replace("`r`n", "`n"),
        [Text.UTF8Encoding]::new($false))
    Invoke-Docker @('cp', $concurrencyCopy, "${container}:/tmp/tests_concurrency.sh")
    Invoke-Docker @('exec', '-e', 'PSQL=psql -U postgres -d parking_verify', $container,
        'bash', '/tmp/tests_concurrency.sh') *> (Join-Path $output 'concurrency.log')
    # Exercise an existing V4 DB, and refusal before any DDL when legacy text is present.
    foreach ($file in @('seed-parking-lots-v4.sql', 'verify-parking-lots-upgrade.sql', 'verify-parking-lots-blocked.sql')) {
        Invoke-Docker @('cp', (Join-Path $PSScriptRoot $file), "${container}:/tmp/$file")
    }
    foreach ($scenario in @('upgrade','blocked')) {
        $database = "parking_$scenario"
        Invoke-Docker @('exec', $container, 'createdb', '-U', 'postgres', $database)
        Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', $database,
            '-v', 'ON_ERROR_STOP=1', '-f', '/tmp/bootstrap.sql') *> (Join-Path $output "$scenario-bootstrap.log")
        Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', $database,
            '-v', 'ON_ERROR_STOP=1', '-c', "ALTER DATABASE $database SET verification.identity TO '$identity';") | Out-Null
        Invoke-VerificationJvm $database "$scenario-v4" -Target4
        Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', $database,
            '-v', 'ON_ERROR_STOP=1', '-f', '/tmp/seed-parking-lots-v4.sql') *> (Join-Path $output "$scenario-seed.log")
        if ($scenario -eq 'upgrade') {
            Invoke-VerificationJvm $database 'upgrade-v5'
            foreach ($file in @('verify-parking-lots-upgrade.sql','verify-parking-lots.sql')) {
                Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', $database,
                    '-v', 'ON_ERROR_STOP=1', '-f', "/tmp/$file") *> (Join-Path $output "upgrade-$file.log")
            }
            Invoke-VerificationJvm $database 'upgrade-rerun'
            if (!(Select-String -LiteralPath (Join-Path $output 'upgrade-rerun.log') -Pattern 'No migration necessary' -Quiet)) {
                throw 'Upgraded database rerun was not a no-op.'
            }
        } else {
            # Empty text also counts as existing data; never silently discard it.
            foreach ($case in @('legacy','empty')) {
                $legacy = if ($case -eq 'legacy') { 'original unparsed hours 09:00-18:00' } else { '' }
                $fixture = "UPDATE parking_lots SET operating_hours='$legacy' WHERE source_record_key='1'; " +
                    "UPDATE verification.snapshots SET value=(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM parking_lots p) WHERE kind='rows';"
                Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', $database,
                    '-v', 'ON_ERROR_STOP=1', '-c', $fixture) | Out-Null
                $failed = Start-VerificationJvm -Database $database -LogName "blocked-$case-v5"
                if ((Complete-VerificationJvm $failed) -eq 0 -or !(Select-String -LiteralPath $failed.Log -Pattern 'parking_lots.operating_hours has data' -Quiet)) {
                    throw "Legacy hours ($case) did not safely block V5."
                }
                Invoke-Docker @('exec', $container, 'psql', '-X', '-U', 'postgres', '-d', $database,
                    '-v', 'ON_ERROR_STOP=1', '-f', '/tmp/verify-parking-lots-blocked.sql') *> (Join-Path $output "blocked-$case-preservation.log")
            }
        }
    }
    $http = Start-VerificationJvm -Database 'parking_verify' -LogName 'http-startup' -Http
    $httpReady = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($http.Process.HasExited) { throw 'HTTP application exited before readiness.' }
        if (Test-Path -LiteralPath $http.ReadyFile) {
            $httpPort = [IO.File]::ReadAllText($http.ReadyFile)
            if ($httpPort -notmatch '^\d+$') { throw 'Invalid verification HTTP port.' }
            $response = Invoke-RestMethod "http://localhost:$httpPort/api/v1/health"
            if ($response.success -and $response.data.status -eq 'UP') { $httpReady = $true; break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (!$httpReady) { throw 'HTTP health check failed.' }
    Write-Output 'PASS: fresh V1-V5, V4 upgrade/backfill, no-op reruns, blocked-hours preservation, catalog/domain, reference SQL/concurrency, local HTTP startup.'
    Write-Output "Reports: $output"
} finally {
    foreach ($child in $children) {
        if (!$child.Process.HasExited) { $child.Process.Kill() }
        Complete-VerificationJvm $child | Out-Null
        $child.Process.Dispose()
    }
    if ($created) { & docker stop $container | Out-Null }
}
