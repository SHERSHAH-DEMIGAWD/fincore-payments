param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand,
    [string]$MavenRepository,
    [switch]$Offline,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome for Java21.' }
$java = Join-Path $JavaHome ('bin/java' + $(if ($IsWindows) { '.exe' } else { '' }))
if (-not (Test-Path -LiteralPath $java)) { throw 'Java executable was not found.' }
$version = (& $java -version 2>&1 | Out-String)
if ($version -notmatch 'version\s+"21(?:\.|\")') { throw 'This release requires Java21.' }
if (-not $MavenCommand) { $MavenCommand = Join-Path $projectRoot $(if ($IsWindows) { 'mvnw.cmd' } else { 'mvnw' }) }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot ('release-artifacts/run-' + [guid]::NewGuid().ToString('N')) }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $OutputDirectory.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Release output must stay inside this project.' }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'A new release output directory is required.' }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$env:JAVA_HOME = $JavaHome
$env:PATH = (Split-Path -Parent $java) + [IO.Path]::PathSeparator + $env:PATH
$arguments = @('-B','-ntp')
if ($Offline) { $arguments += '-o' }
if ($MavenRepository) { $arguments += "-Dmaven.repo.local=$([IO.Path]::GetFullPath($MavenRepository))" }
$arguments += @('-Dit.test=LocalPostgres*IT,LocalKafkaBrokerIT,LocalEventOperationsCliIT,Packaged*IT,*IntegrationIT','clean','verify')
$started = [DateTimeOffset]::UtcNow
Push-Location $projectRoot
try {
    & $MavenCommand @arguments *> (Join-Path $OutputDirectory 'verify.log')
    if ($LASTEXITCODE -ne 0) { throw 'Verification failed; inspect the protected verify.log. No release bundle was completed.' }
} finally { Pop-Location }
$executed = 0; $skipped = 0; $failures = 0; $errors = 0
$suites = @()
foreach ($reports in @('surefire-reports','failsafe-reports')) {
    Get-ChildItem -LiteralPath (Join-Path $projectRoot "target/$reports") -Filter 'TEST-*.xml' | ForEach-Object {
        if ($_.LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale test report encountered.' }
        [xml]$report = [IO.File]::ReadAllText($_.FullName)
        $suite = $report.testsuite
        $ran = [int]$suite.tests - [int]$suite.skipped
        $executed += $ran; $skipped += [int]$suite.skipped
        $failures += [int]$suite.failures; $errors += [int]$suite.errors
        $suites += [ordered]@{ name=[string]$suite.name; executed=$ran; skipped=[int]$suite.skipped }
    }
}
if ($executed -eq 0 -or $failures -ne 0 -or $errors -ne 0) { throw 'Test evidence did not establish a passing release.' }
$bundle = Join-Path $OutputDirectory 'bundle'
New-Item -ItemType Directory -Path $bundle | Out-Null
$files = @('target/fincore-payments-0.0.1-SNAPSHOT.jar','Dockerfile','.dockerignore','deployment/compose.yml','deployment/entrypoint.sh',
    'docs/DEPLOYMENT_RUNBOOK.md','docs/FINAL_PROJECT_GUIDE.md','docs/RETENTION_POLICY.md','docs/DATABASE_RECOVERY_RUNBOOK.md','scripts/Verify-LocalRelease.ps1')
foreach ($relative in $files) {
    $destination = Join-Path $bundle $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot $relative) -Destination $destination
}
$jarHash = (Get-FileHash -LiteralPath (Join-Path $bundle $files[0]) -Algorithm SHA256).Hash.ToLowerInvariant()
$revision = $null
try {
    $git = [Diagnostics.ProcessStartInfo]::new('git')
    $git.UseShellExecute=$false; $git.CreateNoWindow=$true; $git.RedirectStandardOutput=$true; $git.RedirectStandardError=$true
    foreach ($argument in @('-C',$projectRoot,'rev-parse','--verify','HEAD')) { $git.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($git)
    $candidate = $process.StandardOutput.ReadToEnd(); [void]$process.StandardError.ReadToEnd()
    if ($process.WaitForExit(5000) -and $process.ExitCode -eq 0) { $revision = $candidate.Trim() }
    $process.Dispose()
} catch { }
$manifest = [ordered]@{
    version=1; scope='LOCAL_SANDBOX_RELEASE'; createdAt=[DateTimeOffset]::UtcNow.ToString('o'); gitRevision=$revision;
    javaVersion=$version.Trim(); schemaVersion=22; jarSha256=$jarHash;
    verification=[ordered]@{ executed=$executed; skipped=$skipped; failures=$failures; errors=$errors; suites=$suites };
    realFunds=$false; productionReadinessCertified=$false
}
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $bundle 'release-manifest.json'),($manifest | ConvertTo-Json -Depth 8),$utf8)
$files += 'release-manifest.json'
$checksums = foreach ($relative in $files) { (Get-FileHash -LiteralPath (Join-Path $bundle $relative) -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative }
[IO.File]::WriteAllText((Join-Path $bundle 'SHA256SUMS'),(($checksums -join "`n") + "`n"),$utf8)
& (Join-Path $bundle 'scripts/Verify-LocalRelease.ps1') -Directory $bundle | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle,(Join-Path $OutputDirectory 'fincore-local-release.zip'))
[ordered]@{status='LOCAL_RELEASE_VERIFIED';jarSha256=$jarHash;executed=$executed;skipped=$skipped;realFunds=$false} | ConvertTo-Json -Compress
