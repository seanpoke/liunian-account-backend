# Apply a .sql file to the DEV database (ASCII only).
#
#   powershell -ExecutionPolicy Bypass -File script/apply_sql.ps1 -File ci/migration/20261022_saving.sql
#
#   1) loads .env (project root) or ci/.env
#   2) refuses to run unless MYSQL_DB is liunian_account_dev or liunian_account_test
#   3) compiles script/SqlRunner.java against mysql-connector-j from ~/.m2
#   4) executes the file (statements separated by ';')

param(
    [Parameter(Mandatory = $true)][string]$File
)

$ErrorActionPreference = 'Stop'
$projRoot = Resolve-Path (Join-Path $PSScriptRoot '..')

$envFile = Join-Path $projRoot '.env'
if (-not (Test-Path $envFile)) { $envFile = Join-Path $projRoot 'ci\.env' }
if (-not (Test-Path $envFile)) { Write-Host '.env not found'; exit 1 }
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

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'E:\JDK\jdk-17.0.19' }
$javac = Join-Path $javaHome 'bin\javac.exe'
$java = Join-Path $javaHome 'bin\java.exe'
if (-not (Test-Path $javac)) { Write-Host "javac not found under $javaHome" -ForegroundColor Red; exit 1 }

$repo = Join-Path $env:USERPROFILE '.m2\repository\com\mysql\mysql-connector-j'
$driver = Get-ChildItem -Path $repo -Recurse -Filter 'mysql-connector-j-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
    Sort-Object { try { [version]($_.BaseName -replace '^mysql-connector-j-', '') } catch { [version]'0.0.0' } } |
    Select-Object -Last 1
if (-not $driver) { Write-Host "mysql-connector-j jar not found under $repo" -ForegroundColor Red; exit 1 }

$sqlPath = $File
if (-not [IO.Path]::IsPathRooted($sqlPath)) { $sqlPath = Join-Path $projRoot $sqlPath }
if (-not (Test-Path $sqlPath)) { Write-Host "sql file not found: $sqlPath" -ForegroundColor Red; exit 1 }

$outDir = Join-Path $projRoot 'target\sqlrunner'
& $javac -encoding UTF-8 -cp $driver.FullName -d $outDir (Join-Path $PSScriptRoot 'SqlRunner.java')
if ($LASTEXITCODE -ne 0) { Write-Host 'javac failed' -ForegroundColor Red; exit 1 }

& $java -cp ($outDir + ';' + $driver.FullName) SqlRunner `
    $env:MYSQL_HOST $env:MYSQL_PORT $env:MYSQL_USER $env:MYSQL_PASSWORD $env:MYSQL_DB $sqlPath
exit $LASTEXITCODE
