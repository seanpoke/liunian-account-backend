# liunian-account-backend launcher (Windows PowerShell, ASCII only)
# Reads ci/.env and injects every key=value as an ENVIRONMENT VARIABLE into the
# JVM launched by spring-boot:run. Env vars are inherited by the forked app JVM,
# and Spring relaxed binding maps MYSQL_HOST -> ${MYSQL_HOST}, WX_APPID -> wx.appid,
# JWT_SECRET -> ${JWT_SECRET}, etc. (Passing them via -Dspring-boot.run.jvmArguments
# breaks because mvn.cmd's %* re-splits the space-separated -D list.)
# Kills any process already bound to 8445, then starts the app in the background
# and polls /health until it is UP. Logs go to log/boot.log / log/boot.err.
# Usage: powershell -ExecutionPolicy Bypass -File script/start.ps1

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$envFile = Join-Path $PSScriptRoot "..\ci\.env"

if (-not (Test-Path $envFile)) {
    Write-Host "ci/.env not found. Copy ci/.env.example to ci/.env and fill in real values." -ForegroundColor Red
    exit 1
}

# Kill anything already listening on 8445 to avoid "address already in use".
$pids = (Get-NetTCPConnection -LocalPort 8445 -ErrorAction SilentlyContinue).OwningProcess | Sort-Object -Unique
$pids | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue; Write-Host ("killed old pid " + $_) }

# Inject .env keys as environment variables (inherited by the forked app JVM).
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    if ($line -match "^(?<k>[A-Za-z_][A-Za-z0-9_]*)=(?<v>.*)$") {
        Set-Item -Path ("env:" + $Matches["k"]) -Value $Matches["v"].Trim()
    }
}

# Spring Boot gives OS env vars HIGHER precedence than application.yml. A stale
# User-level SPRING_DATASOURCE_URL (e.g. left over from another project) would
# silently override the datasource URL and point the app at the wrong database.
# Strip every inherited SPRING_* var so config always comes from application.yml
# combined with the .env values above.
Get-ChildItem env: | Where-Object { $_.Name -like "SPRING_*" } | ForEach-Object {
    Write-Host ("stripping inherited " + $_.Name + " (would override application.yml)") -ForegroundColor Yellow
    Remove-Item ("env:" + $_.Name)
}

$env:JAVA_HOME = "E:\JDK\jdk-17.0.19"
$env:user_timezone = "Asia/Shanghai"
$env:file_encoding = "UTF-8"
$env:SERVER_PORT = "8445"

Write-Host "JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Cyan
Write-Host "SERVER_PORT = 8445" -ForegroundColor Cyan
Write-Host "WX_MOCK = $env:WX_MOCK  MYSQL_HOST = $env:MYSQL_HOST  REDIS_HOST = $env:REDIS_HOST" -ForegroundColor Cyan
Write-Host "Starting liunian-account-backend ..." -ForegroundColor Green

$bootLog = Join-Path $PSScriptRoot "..\log\boot.log"
$bootErr = Join-Path $PSScriptRoot "..\log\boot.err"
Start-Process -FilePath "D:\Program Files\maven\bin\mvn.cmd" `
    -ArgumentList @("-f", "$root\pom.xml", "-DskipTests", "spring-boot:run") `
    -WindowStyle Hidden -RedirectStandardOutput $bootLog -RedirectStandardError $bootErr

$up = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    try {
        $h = Invoke-RestMethod -Uri "http://127.0.0.1:8445/api/health" -TimeoutSec 2
        if ($h -and $h.code -eq 0) { $up = $true; break }
    } catch {}
}

if ($up) {
    Write-Host "Backend is UP at http://127.0.0.1:8445/api  (logs: log/boot.log)" -ForegroundColor Green
    exit 0
} else {
    Write-Host "Backend did NOT come up in time. Check log/boot.log / log/boot.err" -ForegroundColor Red
    exit 1
}
