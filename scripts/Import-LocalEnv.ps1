# Dot-source from the repository root: . ./scripts/Import-LocalEnv.ps1
# Literal KEY=value only: no evaluation, interpolation, or secret output.
param([string]$Path = (Join-Path $PSScriptRoot '../.env'))
$ErrorActionPreference = 'Stop'
foreach ($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path))) {
    $entry = $line.Trim()
    if (!$entry -or $entry.StartsWith('#')) { continue }
    if ($entry -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        throw 'Invalid .env entry; use literal KEY=value lines.'
    }
    $key = $Matches[1]
    $value = $Matches[2]
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}
