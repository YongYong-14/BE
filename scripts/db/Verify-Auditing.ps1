# Opt-in auditing integration tests. Does not read .env or use Compose/development DBs.
param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to a Java 21 JDK or pass -JavaHome.'
}
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$container = 'parking-auditing-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$report = Join-Path $repo "build/auditing-verification/$container"
New-Item -ItemType Directory -Path $report -Force | Out-Null
$password = [guid]::NewGuid().ToString('N')
$identity = [guid]::NewGuid().ToString('N')
function Invoke-AuditingDocker {
    param([string[]]$DockerArgs)
    $ErrorActionPreference = 'Continue'
    & docker @DockerArgs
    if ($LASTEXITCODE -ne 0) { throw 'Auditing verification Docker command failed.' }
}
$created = $false
$process = $null
try {
    Invoke-AuditingDocker @('run','-d','--name',$container,'--tmpfs','/var/lib/postgresql/data',
        '-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_DB=auditing_verify',
        '-p','127.0.0.1::5432','postgis/postgis:16-3.6') | Out-Null
    $created = $true
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        & docker exec $container pg_isready -h 127.0.0.1 -U postgres -d auditing_verify *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Auditing test DB did not become ready.' }
    $port = ((Invoke-AuditingDocker @('port',$container,'5432/tcp')).Trim() -split ':')[-1]
    if ($port -notmatch '^\d+$') { throw 'Invalid isolated DB port.' }
    $bootstrap = "CREATE ROLE auditing_tester LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$password'; " +
        "GRANT CONNECT,CREATE ON DATABASE auditing_verify TO auditing_tester; " +
        "ALTER DATABASE auditing_verify SET verification.identity TO '$identity';"
    # Credentials go through stdin, never shell arguments or report output.
    $bootstrap | & docker exec -i $container psql -X -U postgres -d auditing_verify -v ON_ERROR_STOP=1 -q
    if ($LASTEXITCODE -ne 0) { throw 'Cannot provision isolated auditing role.' }
    Invoke-AuditingDocker @('exec',$container,'sed','-i','/^host/s/trust/scram-sha-256/g',
        '/var/lib/postgresql/data/pg_hba.conf')
    Invoke-AuditingDocker @('exec',$container,'psql','-X','-U','postgres','-d','auditing_verify',
        '-c','SELECT pg_reload_conf();') | Out-Null

    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $env:SystemRoot 'System32/cmd.exe'
    $start.Arguments = '/d /c gradlew.bat postgresTest --no-daemon'
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
    $start.EnvironmentVariables['AUDITING_TEST_JDBC_URL'] = "jdbc:postgresql://127.0.0.1:$port/auditing_verify?currentSchema=auditing_test"
    $start.EnvironmentVariables['AUDITING_TEST_PASSWORD'] = $password
    $start.EnvironmentVariables['AUDITING_TEST_IDENTITY'] = $identity
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (!$process.Start()) { throw 'Cannot start auditing test Gradle process.' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $log = ($stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()).Replace($password,'[REDACTED]')
    [IO.File]::WriteAllText((Join-Path $report 'gradle.log'),$log,[Text.UTF8Encoding]::new($false))
    if ($process.ExitCode -ne 0) { throw "Auditing tests failed; see $report/gradle.log" }
    $results = Join-Path $repo 'build/test-results/postgresTest/TEST-io.github.yongyong14.parking_server.auditing.AuditingPostgresTest.xml'
    [xml]$xml = [IO.File]::ReadAllText($results)
    if ([int]$xml.testsuite.tests -ne 4 -or [int]$xml.testsuite.failures -ne 0 -or
        [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0) {
        throw 'Expected four passing auditing integration tests.'
    }
    Copy-Item -LiteralPath $results -Destination (Join-Path $report 'tests.xml')
    Invoke-AuditingDocker @('exec',$container,'psql','-X','-U','postgres','-d','auditing_verify',
        '-c',"SELECT version(); SELECT table_schema,table_name FROM information_schema.tables WHERE table_schema='auditing_test' ORDER BY table_name;") > (Join-Path $report 'database.log')
    Write-Output 'PASS: 4 PostgreSQL auditing tests; controlled Instants, inherited listener, flush/clear/reload, bulk-update behavior.'
    Write-Output "Reports: $report"
} finally {
    if ($process) {
        if (!$process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
    if ($created) { & docker stop $container | Out-Null }
}
