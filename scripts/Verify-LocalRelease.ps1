param([Parameter(Mandatory=$true)][string]$Directory)
$ErrorActionPreference = 'Stop'
$Directory = (Resolve-Path -LiteralPath $Directory).Path
$expected = @('target/fincore-payments-0.0.1-SNAPSHOT.jar','Dockerfile','.dockerignore','deployment/compose.yml','deployment/entrypoint.sh',
    'docs/DEPLOYMENT_RUNBOOK.md','docs/FINAL_PROJECT_GUIDE.md','docs/RETENTION_POLICY.md','docs/DATABASE_RECOVERY_RUNBOOK.md','scripts/Verify-LocalRelease.ps1','release-manifest.json')
$checksumFile = Join-Path $Directory 'SHA256SUMS'
if ((Get-Item -LiteralPath $checksumFile).Length -gt 65536) { throw 'Oversized checksum file.' }
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($line in [IO.File]::ReadAllLines($checksumFile)) {
    $match = [regex]::Match($line,'^([0-9a-f]{64})  ([A-Za-z0-9_./-]+)$')
    if (-not $match.Success) { throw 'Malformed checksum entry.' }
    $relative = $match.Groups[2].Value
    if ($relative -cnotin $expected -or -not $seen.Add($relative)) { throw 'Unexpected or duplicate release file.' }
    $path = Join-Path $Directory $relative
    $item = Get-Item -LiteralPath $path
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Release files must be regular files.' }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $match.Groups[1].Value) { throw 'Release checksum mismatch.' }
}
if ($seen.Count -ne $expected.Count) { throw 'Missing release entries.' }
$manifestFile = Join-Path $Directory 'release-manifest.json'
if ((Get-Item -LiteralPath $manifestFile).Length -gt 1MB) { throw 'Oversized manifest.' }
$manifest = [IO.File]::ReadAllText($manifestFile) | ConvertFrom-Json
if ($manifest.version -ne 1 -or $manifest.scope -cne 'LOCAL_SANDBOX_RELEASE' -or $manifest.realFunds -ne $false -or $manifest.productionReadinessCertified -ne $false -or
    $manifest.schemaVersion -ne 22 -or $manifest.verification.executed -lt 1 -or $manifest.verification.failures -ne 0 -or $manifest.verification.errors -ne 0) { throw 'Unsupported release evidence.' }
$actualJar = (Get-FileHash -LiteralPath (Join-Path $Directory $expected[0]) -Algorithm SHA256).Hash.ToLowerInvariant()
if ($manifest.jarSha256 -cne $actualJar) { throw 'Manifest JAR binding disagrees.' }
[ordered]@{status='LOCAL_RELEASE_FILES_MATCH';checkedFiles=$seen.Count;jarSha256=$actualJar;authenticityAttested=$false;realFunds=$false} | ConvertTo-Json -Compress
