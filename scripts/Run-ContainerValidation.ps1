param(
    [string]$JavaHome,
    [string]$MavenCommand,
    [string]$MavenRepository,
    [string]$PrometheusBinary,
    [switch]$Offline,
    [string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$toolRoot=Join-Path (Split-Path -Parent $projectRoot) '.tools'
if(-not $OutputDirectory){$OutputDirectory=Join-Path $projectRoot ('release-artifacts/container-validation-'+[guid]::NewGuid().ToString('N'))}
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
$comparison=if($IsWindows){[StringComparison]::OrdinalIgnoreCase}else{[StringComparison]::Ordinal}
if(-not $OutputDirectory.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,$comparison)){throw 'Output must be inside this project.'}
if(Test-Path -LiteralPath $OutputDirectory){throw 'A new output directory is required.'}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$report=[ordered]@{status='STARTING';stage='prerequisites';startedAt=[DateTimeOffset]::UtcNow.ToString('o');mavenExitCode=$null;containerTestsExecuted=$false;acceptanceExecuted=$false;realFunds=$false;productionReadinessCertified=$false;cleanup='not_started'}
$utf8=[Text.UTF8Encoding]::new($false)
$reportFile=Join-Path $OutputDirectory 'container-validation.json'
$compose=@();$composeAttempted=$false;$succeeded=$false
$savedEnvironment=@{}
foreach($name in @('JAVA_HOME','PATH','FINCORE_TEST_PROMETHEUS_BIN','FINCORE_JAR_SHA256','FINCORE_DEMO_PORT')){$savedEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Invoke-Docker([string[]]$DockerArguments,[string]$LogName){
    & docker @DockerArguments *> (Join-Path $OutputDirectory $LogName)
    if($LASTEXITCODE -ne 0){throw "Docker step failed; inspect $LogName."}
}
function Save-FreshJUnitEvidence([string]$ProjectDirectory,[string]$RunDirectory,[DateTimeOffset]$StartedAt){
    $counts=[ordered]@{unit=0;integration=0;integrationSuites=0;skipped=0;failures=0;errors=0}
    $issues=@()
    foreach($kind in @('surefire-reports','failsafe-reports')){
        $directory=Join-Path $ProjectDirectory ('target/'+$kind)
        if(-not(Test-Path -LiteralPath $directory -PathType Container)){continue}
        $files=@(Get-ChildItem -LiteralPath $directory -File)
        $fresh=@($files|Where-Object {$_.LastWriteTimeUtc -ge $StartedAt.UtcDateTime})
        if($fresh.Count){
            $destination=Join-Path $RunDirectory ('junit/'+$kind)
            New-Item -ItemType Directory -Path $destination -Force|Out-Null
            foreach($file in $fresh){Copy-Item -LiteralPath $file.FullName -Destination $destination}
        }
        if($files|Where-Object {$_.Name -like 'TEST-*.xml' -and $_.LastWriteTimeUtc -lt $StartedAt.UtcDateTime}){$issues+='Stale JUnit evidence refused.'}
        foreach($file in $fresh|Where-Object Name -Like 'TEST-*.xml'){
            try{
                [xml]$xml=[IO.File]::ReadAllText($file.FullName);$suite=$xml.testsuite
                $ran=[int]$suite.tests-[int]$suite.skipped
                if($kind -eq 'failsafe-reports'){
                    if([string]$suite.name -notlike '*IntegrationIT'){throw 'Unexpected integration suite.'}
                    $counts.integration+=$ran;$counts.integrationSuites++
                }else{$counts.unit+=$ran}
                $counts.skipped+=[int]$suite.skipped;$counts.failures+=[int]$suite.failures;$counts.errors+=[int]$suite.errors
            }catch{$issues+=('JUnit report refused: '+$file.Name+'; '+$_.Exception.Message)}
        }
    }
    [pscustomobject]@{tests=$counts;containerTestsExecuted=($counts.integration -gt 0);issues=$issues}
}
try {
    if(-not(Get-Command docker -ErrorAction SilentlyContinue)){$report.status='BLOCKED_DOCKER_CLI_MISSING';throw 'A Linux Docker runtime is required.'}
    $engine=& docker info --format '{{.OSType}}' 2> (Join-Path $OutputDirectory 'docker-info.err.log')
    if($LASTEXITCODE -ne 0){$report.status='BLOCKED_DOCKER_DAEMON_UNAVAILABLE';throw 'Docker daemon is unavailable.'}
    if(($engine|Out-String).Trim() -cne 'linux'){$report.status='BLOCKED_LINUX_CONTAINERS_REQUIRED';throw 'Select a Linux container engine.'}
    Invoke-Docker -DockerArguments @('compose','version') -LogName 'compose-version.log'
    if(-not $JavaHome){$JavaHome=$env:JAVA_HOME}
    if(-not $JavaHome -and (Test-Path -LiteralPath (Join-Path $toolRoot 'jdk-21.0.12.1+1'))){$JavaHome=Join-Path $toolRoot 'jdk-21.0.12.1+1'}
    if(-not $JavaHome){throw 'Select Java21 with -JavaHome.'}
    $java=Join-Path $JavaHome ('bin/java'+$(if($IsWindows){'.exe'}else{''}))
    $version=(& $java -version 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $version -notmatch 'version\s+"21(?:\.|\")'){throw 'Java21 is required.'}
    $env:JAVA_HOME=$JavaHome;$env:PATH=(Split-Path -Parent $java)+[IO.Path]::PathSeparator+$env:PATH
    if(-not $MavenCommand){
        $portable=Join-Path $toolRoot 'apache-maven-3.9.11/bin/mvn.cmd'
        $MavenCommand=if($IsWindows -and (Test-Path -LiteralPath $portable)){$portable}else{Join-Path $projectRoot $(if($IsWindows){'mvnw.cmd'}else{'mvnw'})}
    }
    if(-not $MavenRepository -and (Test-Path -LiteralPath (Join-Path $toolRoot 'm2'))){$MavenRepository=Join-Path $toolRoot 'm2'}
    if(-not $PrometheusBinary){$PrometheusBinary=$env:FINCORE_TEST_PROMETHEUS_BIN}
    if(-not $PrometheusBinary -and $IsWindows){$PrometheusBinary=Join-Path $toolRoot 'prometheus-3.5.0.windows-amd64/prometheus.exe'}
    if(-not $PrometheusBinary -or -not(Test-Path -LiteralPath $PrometheusBinary -PathType Leaf)){throw 'Select real Prometheus with -PrometheusBinary so its integration checks cannot skip.'}
    $env:FINCORE_TEST_PROMETHEUS_BIN=[IO.Path]::GetFullPath($PrometheusBinary)
    $report.stage='integration_tests';$started=[DateTimeOffset]::UtcNow
    $arguments=@('-B','-ntp','-Dit.test=*IntegrationIT','clean','verify')
    if($Offline){$arguments=@('-o')+$arguments}
    if($MavenRepository){$arguments=@("-Dmaven.repo.local=$([IO.Path]::GetFullPath($MavenRepository))")+$arguments}
    Push-Location $projectRoot
    try {
        try{
            & $MavenCommand @arguments *> (Join-Path $OutputDirectory 'verify.log')
            $report.mavenExitCode=$LASTEXITCODE
        }catch{
            if($_.Exception.PSObject.Properties['ExitCode']){$report.mavenExitCode=$_.Exception.ExitCode}
            throw
        }
    } finally {
        Pop-Location
        $collected=Save-FreshJUnitEvidence -ProjectDirectory $projectRoot -RunDirectory $OutputDirectory -StartedAt $started
        $report.tests=$collected.tests;$report.containerTestsExecuted=$collected.containerTestsExecuted
        if($collected.issues.Count){$report.junitEvidenceIssues=$collected.issues}
    }
    if($report.mavenExitCode -ne 0){throw 'Container regression failed; inspect verify.log and retained JUnit evidence.'}
    if($collected.issues.Count){throw ($collected.issues -join ' ')}
    $tests=$report.tests
    if($tests.integration -lt 484 -or $tests.integrationSuites -lt 24 -or $tests.skipped -ne 0 -or $tests.failures -ne 0 -or $tests.errors -ne 0){throw 'Complete passing container coverage without skips was not established.'}
    $report.stage='packaged_acceptance'
    $runtime=Join-Path $OutputDirectory 'runtime';New-Item -ItemType Directory -Path $runtime|Out-Null
    foreach($relative in @('Dockerfile','.dockerignore','deployment/compose.yml','deployment/entrypoint.sh','target/fincore-payments-0.0.1-SNAPSHOT.jar')){
        $destination=Join-Path $runtime $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force|Out-Null
        Copy-Item -LiteralPath (Join-Path $projectRoot $relative) -Destination $destination
    }
    $jar=Join-Path $runtime 'target/fincore-payments-0.0.1-SNAPSHOT.jar'
    $env:FINCORE_JAR_SHA256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant();$report.jarSha256=$env:FINCORE_JAR_SHA256
    & $java -jar $jar init-local-sandbox "--directory=$(Join-Path $runtime 'deployment/.secrets')" *> (Join-Path $OutputDirectory 'init.log')
    if($LASTEXITCODE -ne 0){throw 'Private sandbox initialization failed.'}
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    try{$listener.Start();$env:FINCORE_DEMO_PORT=[string]$listener.LocalEndpoint.Port}finally{$listener.Stop()}
    $name='fincore-validation-'+[guid]::NewGuid().ToString('N').Substring(0,12)
    $report.composeProject=$name;$report.hostLoopbackPort=[int]$env:FINCORE_DEMO_PORT
    $compose=@('compose','-p',$name,'-f',(Join-Path $runtime 'deployment/compose.yml'),'--profile','acceptance')
    Invoke-Docker -DockerArguments ($compose+@('config','--quiet')) -LogName 'compose-config.log'
    $composeAttempted=$true
    Invoke-Docker -DockerArguments ($compose+@('up','-d','--build','--wait','api','provider','redis')) -LogName 'compose-startup.log'
    $report.acceptanceExecuted=$true
    Invoke-Docker -DockerArguments ($compose+@('run','--rm','acceptance')) -LogName 'acceptance-command.log'
    $acceptance=& docker @compose exec -T api cat /evidence/acceptance.json 2> (Join-Path $OutputDirectory 'acceptance-read.err.log')
    if($LASTEXITCODE -ne 0){throw 'Acceptance evidence could not be read.'}
    $raw=$acceptance -join "`n";$result=$raw|ConvertFrom-Json
    if($result.status -cne 'LOCAL_SANDBOX_ACCEPTANCE_PASSED' -or $result.riskValidated -ne $true -or $result.kafkaProjectionValidated -ne $true -or $result.realFunds -ne $false){throw 'Full Redis/Kafka acceptance was not established.'}
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'acceptance.json'),$raw,$utf8)
    $report.acceptance=[ordered]@{riskValidated=$true;kafkaProjectionValidated=$true;notificationReceipts=$result.notificationReceipts;load=$result.load}
    $report.status='CONTAINER_VALIDATION_PASSED';$report.stage='complete';$succeeded=$true
} catch {
    if($report.status -eq 'STARTING'){$report.status='CONTAINER_VALIDATION_FAILED'}
    $_|Out-String|Set-Content -LiteralPath (Join-Path $OutputDirectory 'failure.log') -Encoding utf8
} finally {
    if($composeAttempted){
        try{
            & docker @compose down *> (Join-Path $OutputDirectory 'compose-stop.log')
            if($LASTEXITCODE -ne 0){throw 'Docker cleanup failed; inspect compose-stop.log.'}
            $report.cleanup='project_stopped_volumes_retained'
        }catch{
            $report.cleanup='stop_failed_inspect_retained_logs';$report.cleanupError=$_.Exception.Message
            $succeeded=$false;$report.status='CONTAINER_VALIDATION_CLEANUP_FAILED'
            try{$_|Out-String|Add-Content -LiteralPath (Join-Path $OutputDirectory 'compose-stop.log') -Encoding utf8}
            catch{$report.cleanupLogError=$_.Exception.Message}
        }
    }
    foreach($entry in $savedEnvironment.GetEnumerator()){
        $value=if($null -eq $entry.Value){[NullString]::Value}else{$entry.Value}
        [Environment]::SetEnvironmentVariable($entry.Key,$value,'Process')
    }
    $report.finishedAt=[DateTimeOffset]::UtcNow.ToString('o')
    [IO.File]::WriteAllText($reportFile,($report|ConvertTo-Json -Depth 8),$utf8)
}
[ordered]@{status=$report.status;stage=$report.stage;containerTestsExecuted=$report.containerTestsExecuted;acceptanceExecuted=$report.acceptanceExecuted;realFunds=$false}|ConvertTo-Json -Compress
if(-not $succeeded){exit 2}
