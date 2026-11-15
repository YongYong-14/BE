# Regression for the verification runner itself. Uses only NEW disposable containers.
param([string]$Java = (Join-Path $env:JAVA_HOME 'bin/java.exe'))
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'parking-decoy-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$report = Join-Path $repo "build/db-verification/$name"
New-Item -ItemType Directory -Path $report -Force | Out-Null
function Invoke-DecoyDocker {
    param([string[]]$DockerArgs)
    $ErrorActionPreference = 'Continue'
    & docker @DockerArgs
    if ($LASTEXITCODE -ne 0) { throw 'Decoy Docker command failed.' }
}
function Get-EnvironmentSnapshot {
    $result = @{}
    foreach ($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) { $result[$entry.Key] = $entry.Value }
    return $result
}
function Assert-EnvironmentUnchanged($Before) {
    $after = Get-EnvironmentSnapshot
    if ($Before.Count -ne $after.Count) { throw 'Parent environment size changed.' }
    foreach ($key in $Before.Keys) {
        if (!$after.ContainsKey($key) -or $Before[$key] -cne $after[$key]) { throw "Parent environment changed: $key" }
    }
}
function Get-DecoySnapshot {
    $dump = Invoke-DecoyDocker @('exec', $name, 'pg_dump', '-U', 'postgres', '-d', 'decoy', '--no-owner')
    # New PostgreSQL versions add random psql restrict keys to otherwise identical dumps.
    return (($dump | Where-Object { $_ -notmatch '^\\(un)?restrict ' }) -join "`n")
}
$original = Get-EnvironmentSnapshot
$created = $false
try {
    Invoke-DecoyDocker @('run','-d','--name',$name,'--tmpfs','/var/lib/postgresql/data',
        '-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_DB=decoy','-p','127.0.0.1::5432',
        'postgis/postgis:16-3.6','-c','log_connections=on') | Out-Null
    $created = $true
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        & docker exec $name pg_isready -h 127.0.0.1 -U postgres -d decoy *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Decoy not ready.' }
    Invoke-DecoyDocker @('exec',$name,'psql','-X','-U','postgres','-d','decoy','-v','ON_ERROR_STOP=1','-c',
        "CREATE SCHEMA canary; CREATE TABLE canary.marker(id int PRIMARY KEY, value text); INSERT INTO canary.marker VALUES(1,'must remain unchanged'); CREATE SCHEMA flyway; CREATE TABLE flyway.flyway_schema_history(installed_rank int PRIMARY KEY, version text, success boolean); INSERT INTO flyway.flyway_schema_history VALUES(1,'decoy-sentinel',true);") | Out-Null
    $port = ((Invoke-DecoyDocker @('port',$name,'5432/tcp')).Trim() -split ':')[-1]
    $url = "jdbc:postgresql://127.0.0.1:$port/decoy?ApplicationName=decoy-poison&currentSchema=public"
    $beforeDump = Get-DecoySnapshot
    # No real passwords: this isolated decoy uses trust. Bait files contain only placeholders.
    $baitFile = Join-Path $report 'application.properties'
    [IO.File]::WriteAllText($baitFile, "spring.datasource.url=$url`nspring.flyway.url=$url`nspring.flyway.user=postgres`nspring.flyway.password=unused-decoy`n")
    $poison = @{
        SPRING_DATASOURCE_URL=$url; SPRING_DATASOURCE_USERNAME='postgres'; SPRING_DATASOURCE_PASSWORD='unused-decoy'
        SPRING_DATASOURCE_HIKARI_JDBC_URL=$url
        SPRING_FLYWAY_URL=$url; SPRING_FLYWAY_USER='postgres'; SPRING_FLYWAY_PASSWORD='unused-decoy'
        SPRING_APPLICATION_JSON=(@{spring=@{datasource=@{url=$url;username='postgres';password='unused-decoy'};flyway=@{url=$url;user='postgres';password='unused-decoy'}}} | ConvertTo-Json -Compress -Depth 5)
        SPRING_CONFIG_LOCATION=('file:'+$baitFile); SPRING_CONFIG_ADDITIONAL_LOCATION=('file:'+$report+'/')
        SPRING_CONFIG_IMPORT=('file:'+$baitFile); SPRING_CONFIG_NAME='application'
        SPRING_PROFILES_ACTIVE='prod'; SPRING_FLYWAY_ENABLED='false'; SPRING_SQL_INIT_MODE='always'
        JAVA_TOOL_OPTIONS="-Dspring.datasource.url=$url -Dspring.flyway.url=$url"
        JDK_JAVA_OPTIONS="-Dspring.config.location=file:$baitFile -Dspring.flyway.url=$url"
        _JAVA_OPTIONS="-Dspring.application.json={broken-json} -Dspring.flyway.url=$url"
        JAVA_OPTS="-Dspring.flyway.url=$url"; CLASSPATH='invalid-parent-classpath'
        DB_HOST='127.0.0.1'; DB_PORT=$port; POSTGRES_DB='decoy'
        DB_APP_USER='postgres'; DB_APP_PASSWORD='unused-decoy'; DB_MIGRATION_USER='postgres'; DB_MIGRATION_PASSWORD='unused-decoy'
    }
    $results = @()
    foreach ($scenario in @('poisoned','normal')) {
        if ($scenario -eq 'poisoned') {
            foreach ($key in $poison.Keys) { [Environment]::SetEnvironmentVariable($key,$poison[$key],'Process') }
        } else {
            foreach ($key in @( [Environment]::GetEnvironmentVariables('Process').Keys )) {
                if ($key -match '^(SPRING_|DB_|POSTGRES_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$|JAVA_OPTS$|CLASSPATH$)') {
                    [Environment]::SetEnvironmentVariable($key,$null,'Process')
                }
            }
        }
        $beforeEnvironment = Get-EnvironmentSnapshot
        Write-Output "Running $scenario isolation regression (no development DB access)."
        $messages = & (Join-Path $PSScriptRoot 'Verify-Database.ps1') -Java $Java
        Assert-EnvironmentUnchanged $beforeEnvironment
        $afterDump = Get-DecoySnapshot
        if ($afterDump -cne $beforeDump) { throw "Decoy schema/data/Flyway history changed in $scenario scenario." }
        $reports = @($messages | Where-Object { $_ -like 'Reports: *' })
        if ($reports.Count -ne 1 -or !($messages -match '^PASS:')) { throw 'Verification runner did not complete.' }
        $runFolder = $reports[0].Substring(9)
        $logs = Invoke-DecoyDocker @('logs',$name) 2>&1
        if (($logs -join "`n").Contains('application_name=decoy-poison')) { throw 'A poisoned JVM connected to the decoy.' }
        $results += [pscustomobject]@{
            scenario=$scenario; verificationPassed=$true; parentEnvironmentPreserved=$true
            decoySchemaAndHistoryUnchanged=$true; noPoisonedConnection=$true; reports=$runFolder
        }
        Write-Output "PASS: $scenario; parent environment and decoy schema/history preserved."
    }
    $results | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $report 'results.json') -Encoding UTF8
    Write-Output "Isolation results: $report"
} finally {
    # This harness intentionally poisons its environment; restore even after test failure.
    foreach ($key in @( [Environment]::GetEnvironmentVariables('Process').Keys )) {
        if (!$original.ContainsKey($key)) { [Environment]::SetEnvironmentVariable($key,$null,'Process') }
    }
    foreach ($key in $original.Keys) { [Environment]::SetEnvironmentVariable($key,$original[$key],'Process') }
    if ($created) { & docker stop $name | Out-Null }
}
