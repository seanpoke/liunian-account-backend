# Run the full smoke suite against an isolated test instance (ASCII only).
#
#   1) loads ci/.env and refuses to run unless MYSQL_DB is liunian_account_dev / liunian_account_test
#   2) builds target/*.jar (mvn -DskipTests package)
#   3) starts the app on port 8447 with WX_MOCK=true (does NOT touch 8445)
#   4) runs verify.ps1 (all endpoints), smoke_family.ps1 (lifecycle) and
#      smoke_saving.ps1 (saving module)
#   5) stops the test instance
#   6) cleans up smoke residue: mock users + leftover verify/smoke families via
#      SmokeCleanup.java (JDBC, uses mysql-connector-j from ~/.m2, no mysql client
#      required), then prunes empty dirs under data/upload
#   7) prints the summary
#
# Usage: powershell -ExecutionPolicy Bypass -File script/run_smoke.ps1
#        add -SkipClean to keep the smoke data for debugging

param(
    [switch]$SkipClean
)

$ErrorActionPreference = 'Stop'
$projRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$port = 8447
$base = "http://127.0.0.1:$port/api"

# ---- ci/.env ----
$envFile = Join-Path $PSScriptRoot '..\.env'
if (-not (Test-Path $envFile)) { $envFile = Join-Path $PSScriptRoot '..\ci\.env' }
if (-not (Test-Path $envFile)) { Write-Host '.env not found (project root)'; exit 1 }
Get-Content $envFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $i = $line.IndexOf('=')
    if ($i -le 0) { return }
    [Environment]::SetEnvironmentVariable($line.Substring(0, $i).Trim(), $line.Substring($i + 1).Trim(), 'Process')
}
$allowedDb = @('liunian_account_dev', 'liunian_account_test')
if ($allowedDb -notcontains $env:MYSQL_DB) {
    Write-Host "[DB-GUARD] MYSQL_DB=$env:MYSQL_DB is not an allowed dev/test database. Refused." -ForegroundColor Red
    exit 1
}

# strip inherited SPRING_* so application.yml + .env are authoritative
Get-ChildItem env: | Where-Object { $_.Name -like 'SPRING_*' } | ForEach-Object {
    Remove-Item ("env:" + $_.Name)
}
[Environment]::SetEnvironmentVariable('WX_MOCK', 'true', 'Process')
[Environment]::SetEnvironmentVariable('MYSQL_DB', , 'Process')
if (-not $env:JAVA_HOME -and (Test-Path 'E:\JDK\jdk-17.0.19')) {
    $env:JAVA_HOME = 'E:\JDK\jdk-17.0.19'
}

Write-Host "MYSQL_DB=$env:MYSQL_DB  WX_MOCK=$env:WX_MOCK  port=$port"
Write-Host 'building jar ...'
& 'D:\Program Files\maven\bin\mvn.cmd' -B -f (Join-Path $projRoot 'pom.xml') -DskipTests package
if ($LASTEXITCODE -ne 0) { Write-Host 'maven build failed' -ForegroundColor Red; exit 1 }

$jar = (Get-ChildItem (Join-Path $projRoot 'target') -Filter '*.jar' | Sort-Object LastWriteTime | Select-Object -First 1)
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { $java = 'java' }
$log = Join-Path $projRoot 'log\boot_smoke.log'
$err = Join-Path $projRoot 'log\boot_smoke.err'
Write-Host "starting $($jar.Name) on port $port ..."
$proc = Start-Process -FilePath $java -ArgumentList @('-jar', $jar.FullName, "--server.port=$port") `
    -RedirectStandardOutput $log -RedirectStandardError $err -PassThru

$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "$base/health" -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch {}
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    Write-Host 'service not ready; tail of boot_smoke.err:' -ForegroundColor Red
    if (Test-Path $err) { Get-Content $err -Encoding UTF8 -Tail 40 }
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    exit 1
}

$env:API_BASE = $base
Write-Host "`n########## verify.ps1 ##########" -ForegroundColor Cyan
& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'verify.ps1')
$v = $LASTEXITCODE

Write-Host "`n########## smoke_family.ps1 ##########" -ForegroundColor Cyan
& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'smoke_family.ps1')
$s = $LASTEXITCODE

Write-Host "`n########## smoke_saving.ps1 ##########" -ForegroundColor Cyan
& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'smoke_saving.ps1') -SkipBuild
$sv = $LASTEXITCODE

Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue

# ---- cleanup: mock users / leftover families via JDBC (no mysql client needed) ----
$clean = 'SKIPPED'
if (-not $SkipClean) {
    Write-Host "`n########## cleanup ##########" -ForegroundColor Cyan
    $clean = 'FAIL'
    try {
        $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
        if (-not (Test-Path $javac)) { throw "javac not found under $env:JAVA_HOME" }

        $repo = Join-Path $env:USERPROFILE '.m2\repository\com\mysql\mysql-connector-j'
        $driver = Get-ChildItem -Path $repo -Recurse -Filter 'mysql-connector-j-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
            Sort-Object { try { [version]($_.BaseName -replace '^mysql-connector-j-', '') } catch { [version]'0.0.0' } } |
            Select-Object -Last 1
        if (-not $driver) { throw "mysql-connector-j jar not found under $repo" }

        $outDir = Join-Path $projRoot 'target\smoke-cleanup'
        & $javac -encoding UTF-8 -cp $driver.FullName -d $outDir (Join-Path $PSScriptRoot 'SmokeCleanup.java')
        if ($LASTEXITCODE -ne 0) { throw "javac failed" }

        & (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp ($outDir + ';' + $driver.FullName) SmokeCleanup `
            $env:MYSQL_HOST $env:MYSQL_PORT $env:MYSQL_USER $env:MYSQL_PASSWORD $env:MYSQL_DB
        if ($LASTEXITCODE -eq 0) { $clean = 'PASS' } else { throw "cleanup exit=$LASTEXITCODE" }
    } catch {
        Write-Host ("[CLEAN] WARN " + $_.Exception.Message) -ForegroundColor Yellow
    }

    # prune empty upload dirs left behind by verify.ps1
    $uploadRoot = Join-Path $projRoot 'data\upload'
    if (Test-Path $uploadRoot) {
        Get-ChildItem $uploadRoot -Recurse -Directory -ErrorAction SilentlyContinue |
            Sort-Object { ($_.FullName -split '\\').Count } -Descending |
            ForEach-Object {
                if ((Get-ChildItem $_.FullName -Force -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0) {
                    Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
                }
            }
    }
}

$vTxt = if ($v -eq 0) { 'PASS' } else { 'FAIL' }
$sTxt = if ($s -eq 0) { 'PASS' } else { 'FAIL' }
$svTxt = if ($sv -eq 0) { 'PASS' } else { 'FAIL' }
Write-Host "`n########## result ##########"
Write-Host ("verify=" + $vTxt + "  smoke_family=" + $sTxt + "  smoke_saving=" + $svTxt + "  cleanup=" + $clean)
Write-Host "logs: $log"
if ($v -ne 0 -or $s -ne 0 -or $sv -ne 0) { exit 1 } else { exit 0 }
