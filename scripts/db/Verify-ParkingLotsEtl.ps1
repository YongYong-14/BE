# Fresh tmpfs DB only. Does not read .env or touch development volumes.
param([string]$Python = 'build/parking-lots-venv/Scripts/python.exe')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$container = 'parking-lots-etl-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$identity = [guid]::NewGuid().ToString('N')
$created = $false
function Assert-Exit { if ($LASTEXITCODE -ne 0) { throw 'Isolated ETL verification command failed.' } }
try {
    & docker run -d --name $container --tmpfs /var/lib/postgresql/data -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=parking_lots_verify -p 127.0.0.1::5432 postgis/postgis:16-3.6 | Out-Null
    Assert-Exit
    $created = $true
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        & docker exec $container pg_isready -h 127.0.0.1 -U postgres -d parking_lots_verify *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Isolated DB did not start.' }
    Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repo 'scripts/db/bootstrap.sql') | & docker exec -i $container psql -X -U postgres -d parking_lots_verify -v ON_ERROR_STOP=1 -q
    Assert-Exit
    "ALTER DATABASE parking_lots_verify SET verification.identity TO '$identity';" | & docker exec -i $container psql -X -U postgres -d parking_lots_verify -v ON_ERROR_STOP=1 -q
    Assert-Exit
    # Discover every migration in numeric version order; never pin the latest version.
    $migrations = Get-ChildItem -LiteralPath (Join-Path $repo 'src/main/resources/db/migration') -Filter 'V*__*.sql' | Sort-Object { [int]($_.Name -replace '^V(\d+)__.*','$1') }
    foreach ($migration in $migrations) {
        $sql = "BEGIN; SET LOCAL search_path=public,pg_catalog;`n" + [IO.File]::ReadAllText($migration.FullName) + "`nCOMMIT;"
        $sql | & docker exec -i $container psql -X -U parking_migrator -d parking_lots_verify -v ON_ERROR_STOP=1 -q
        Assert-Exit
        Write-Output "Applied $($migration.Name)"
    }
    $port = ((& docker port $container 5432/tcp).Trim() -split ':')[-1]
    Assert-Exit
    if ($port -notmatch '^\d+$') { throw 'Invalid isolated DB port.' }
    $env:PARKING_LOTS_TEST_DSN = "host=127.0.0.1 port=$port dbname=parking_lots_verify user=parking_loader"
    $env:PARKING_LOTS_TEST_IDENTITY = $identity
    $env:PYTHONIOENCODING = 'utf-8'
    $report = Join-Path $repo 'build/parking-lots-etl-tests.xml'
    & $Python -m pytest etl/tests/test_load_parking_lots.py -v -rs -o cache_dir=build/pytest-cache --junitxml=$report
    Assert-Exit
    [xml]$results = [IO.File]::ReadAllText($report)
    $suite = $results.testsuites.testsuite
    if ([int]$suite.tests -lt 15 -or [int]$suite.skipped -ne 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0) {
        throw 'ETL verification must include every real-CSV and database test without skips.'
    }
} finally {
    Remove-Item Env:PARKING_LOTS_TEST_DSN -ErrorAction SilentlyContinue
    Remove-Item Env:PARKING_LOTS_TEST_IDENTITY -ErrorAction SilentlyContinue
    if ($created) { & docker stop $container | Out-Null }
}
