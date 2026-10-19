# liunian-account-backend family lifecycle smoke test (ASCII only)
#
# Covers: quit as member (own tx voided, owner tx kept), quit as owner (heir
# promoted, own tx voided), last member quit -> dissolve, explicit dissolve,
# invitation reuse, duplicate-pending guard, reject then re-apply.
#
# Modes:
#   1) external : set API_BASE (e.g. http://127.0.0.1:8447/api) and run against
#                 an already running instance with WX_MOCK=true.
#   2) standalone: no API_BASE -> builds target/*.jar and starts it on port 8447.
#
# Always reads ci/.env and refuses to run unless MYSQL_DB=liunian_account_dev.

$ErrorActionPreference = 'Stop'

# ---- load ci/.env ----
$envFile = Join-Path $PSScriptRoot '..\ci\.env'
if (-not (Test-Path $envFile)) { Write-Host 'ci/.env not found'; exit 1 }
Get-Content $envFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $i = $line.IndexOf('=')
    if ($i -le 0) { return }
    [Environment]::SetEnvironmentVariable($line.Substring(0, $i).Trim(), $line.Substring($i + 1).Trim(), 'Process')
}

# ---- dev DB guard ----
if ($env:MYSQL_DB -ne 'liunian_account_dev') {
    Write-Host "[DB-GUARD] MYSQL_DB=$env:MYSQL_DB but smoke requires liunian_account_dev. Refused." -ForegroundColor Red
    exit 1
}

$external = $env:API_BASE
$port = 8447
if ($external) {
    $base = $external.TrimEnd('/')
} else {
    $base = "http://127.0.0.1:$port/api"
    [Environment]::SetEnvironmentVariable('WX_MOCK', 'true', 'Process')
}
if ($external) {
    Write-Host "MYSQL_DB=$env:MYSQL_DB (DEV)  mode=external  base=$base"
} else {
    Write-Host "MYSQL_DB=$env:MYSQL_DB (DEV)  WX_MOCK=$env:WX_MOCK  base=$base"
}

$proc = $null
if (-not $external) {
    $projRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
    $jar = (Get-ChildItem (Join-Path $projRoot 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime | Select-Object -First 1)
    if (-not $jar) {
        Write-Host 'no target/*.jar found. Run: mvn -DskipTests package' -ForegroundColor Red
        exit 1
    }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    $log = Join-Path $projRoot 'log\boot_smoke.log'
    $err = Join-Path $projRoot 'log\boot_smoke.err'
    Write-Host "starting $($jar.Name) on port $port"
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
        if (Test-Path $err) { Get-Content $err -Encoding UTF8 -Tail 30 }
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        exit 1
    }
    Write-Host 'service ready'
}

# ---- helpers ----
function call($method, $path, $token, $body) {
    $h = @{}
    if ($token) { $h['Authorization'] = "Bearer $token" }
    $p = @{ Method = $method; Uri = "$base$path"; Headers = $h; ContentType = 'application/json' }
    if ($null -ne $body) { $p['Body'] = (ConvertTo-Json $body -Compress) }
    try { return Invoke-RestMethod @p } catch {
        $msg = ''
        if ($_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
        Write-Host "  [HTTP ERR] $method $path -> $msg" -ForegroundColor Red
        return $null
    }
}

$script:pass = 0
$script:fail = 0
function check($name, $cond, $detail = '') {
    if ($cond) { $script:pass++; Write-Host "  [PASS] $name $detail" }
    else { $script:fail++; Write-Host "  [FAIL] $name $detail" -ForegroundColor Red }
}

function login($code) {
    $r = call Post '/login' $null @{ code = $code }
    if ($null -eq $r -or $null -eq $r.data) { throw "login failed: $code (WX_MOCK must be true)" }
    return $r.data
}

function invite($ownerToken, $familyId) {
    $r = call Post '/invitation' $ownerToken @{ type = 'share'; familyId = $familyId }
    return $r.data
}

function joinViaInvite($ownerToken, $familyId, $memberToken, $memberOpenid, $nick) {
    $inv = invite $ownerToken $familyId
    call Post ("/invitation/" + $inv.token + "/apply") $memberToken @{ nickname = $nick; avatar = '' } | Out-Null
    $pend = call Get "/family/$familyId/join-requests" $ownerToken
    $rid = ($pend.data | Where-Object { $_.applicantOpenid -eq $memberOpenid } | Select-Object -First 1).requestId
    if ($null -eq $rid) { return $null }
    call Post "/family/$familyId/join-request/$rid/approve" $ownerToken $null | Out-Null
    return $rid
}

function addTx($token, $familyId, $amount) {
    return call Post '/transaction' $token @{ familyId = $familyId; type = 'expense'; amount = $amount; date = '2026-10-16' }
}

function txCount($token, $familyId) {
    $r = call Get "/transactions?familyId=$familyId" $token
    if ($null -eq $r -or $null -eq $r.data) { return -1 }
    return [int]$r.data.total
}

function expenseTotal($token, $familyId) {
    $r = call Get "/stats?familyId=$familyId&year=2026&month=10" $token
    if ($null -eq $r -or $null -eq $r.data) { return -1 }
    return [int64]$r.data.expense
}

$ts = (Get-Date -Format 'HHmmssfff')

Write-Host "`n=== Scenario 1: non-owner quits, family + owner history kept, own tx voided ==="
$uA = "mock:smk_${ts}_ownerA"; $uB = "mock:smk_${ts}_memB"
$a = login $uA; $b = login $uB
$famA = [long](call Post '/family' $a.token @{ name = "smokeA_$ts" }).data.familyId
check 'create family A' ($famA -gt 0) "familyId=$famA"
addTx $a.token $famA 100 | Out-Null
joinViaInvite $a.token $famA $b.token $b.openid 'memB' | Out-Null
addTx $b.token $famA 50 | Out-Null
$memA = call Get "/family/$famA/members" $a.token
check 'after join member count = 2' (@($memA.data).Count -eq 2) ("count=" + @($memA.data).Count)
check 'expense before quit = 150' ((expenseTotal $a.token $famA) -eq 150)
call Post "/family/$famA/quit" $b.token $null | Out-Null
$memA2 = call Get "/family/$famA/members" $a.token
check 'after memB quit member count = 1' (@($memA2.data).Count -eq 1) ("count=" + @($memA2.data).Count)
$famStill = call Get "/family/$famA" $a.token
check 'family A still exists' ($null -ne $famStill -and $null -ne $famStill.data)
check 'owner tx kept, memB tx voided (expense=100)' ((expenseTotal $a.token $famA) -eq 100) ("expense=" + (expenseTotal $a.token $famA))
$mineA = call Get '/families' $a.token
check 'ownerA /families role=owner' (@($mineA.data | Where-Object { [long]$_.familyId -eq $famA -and $_.role -eq 'owner' }).Count -eq 1)

Write-Host "`n=== Scenario 2: owner quits -> heir promoted, own history voided ==="
$uC = "mock:smk_${ts}_ownerC"; $uD = "mock:smk_${ts}_memD"
$c = login $uC; $d = login $uD
$famC = [long](call Post '/family' $c.token @{ name = "smokeC_$ts" }).data.familyId
addTx $c.token $famC 200 | Out-Null
joinViaInvite $c.token $famC $d.token $d.openid 'memD' | Out-Null
addTx $d.token $famC 30 | Out-Null
call Post "/family/$famC/quit" $c.token $null | Out-Null
$memC = call Get "/family/$famC/members" $d.token
$heir = $memC.data | Where-Object { $_.openid -eq $d.openid } | Select-Object -First 1
check 'memD promoted to owner' ($heir.role -eq 'owner')
$famCinfo = call Get "/family/$famC" $d.token
check 'creatorOpenid transferred' ($famCinfo.data.creatorOpenid -eq $d.openid)
check 'ex-owner tx voided (expense=30)' ((expenseTotal $d.token $famC) -eq 30) ("expense=" + (expenseTotal $d.token $famC))

Write-Host "`n=== Scenario 3: last member quits -> dissolve ==="
$uE = "mock:smk_${ts}_ownerE"
$e = login $uE
$famE = [long](call Post '/family' $e.token @{ name = "smokeE_$ts" }).data.familyId
addTx $e.token $famE 300 | Out-Null
call Post "/family/$famE/quit" $e.token $null | Out-Null
$gone = call Get "/family/$famE" $e.token
check 'family gone after last quit' (($null -eq $gone) -or ($gone.code -eq 403) -or ($gone.code -eq 400))
$mineE = call Get '/families' $e.token
check 'ownerE has no families left' (@($mineE.data).Count -eq 0)

Write-Host "`n=== Scenario 4: explicit dissolve ==="
$uF = "mock:smk_${ts}_ownerF"
$f = login $uF
$famF = [long](call Post '/family' $f.token @{ name = "smokeF_$ts" }).data.familyId
addTx $f.token $famF 999 | Out-Null
$txBefore = txCount $f.token $famF
call Post "/family/$famF/dissolve" $f.token $null | Out-Null
$goneF = call Get "/family/$famF" $f.token
check 'tx existed before dissolve' ($txBefore -ge 1)
check 'family gone after dissolve' (($null -eq $goneF) -or ($goneF.code -eq 403) -or ($goneF.code -eq 400))

Write-Host "`n=== Scenario 5: invitation reuse + 1:N + duplicate pending + reject/re-apply ==="
$uG = "mock:smk_${ts}_ownerG"; $uH = "mock:smk_${ts}_memH"; $uI = "mock:smk_${ts}_memI"
$g = login $uG; $h = login $uH; $i = login $uI
$famG = [long](call Post '/family' $g.token @{ name = "smokeG_$ts" }).data.familyId
$inv1 = invite $g.token $famG
$inv2 = invite $g.token $famG
check 'invitation reused (same token)' ($inv1.token -eq $inv2.token -and $inv2.reused -eq $true)
call Post ("/invitation/" + $inv1.token + "/apply") $h.token @{ nickname = 'memH' } | Out-Null
call Post ("/invitation/" + $inv1.token + "/apply") $h.token @{ nickname = 'memH2' } | Out-Null
$pendH = call Get "/family/$famG/join-requests" $g.token
check 'duplicate apply -> single pending' (@($pendH.data).Count -eq 1) ("count=" + @($pendH.data).Count)
$ridH = ($pendH.data | Where-Object { $_.applicantOpenid -eq $h.openid } | Select-Object -First 1).requestId
call Post "/family/$famG/join-request/$ridH/reject" $g.token @{ remark = 'later' } | Out-Null
call Post ("/invitation/" + $inv1.token + "/apply") $h.token @{ nickname = 'memH3' } | Out-Null
$pendH2 = call Get "/family/$famG/join-requests" $g.token
check 'rejected user can re-apply' (@($pendH2.data).Count -eq 1) ("count=" + @($pendH2.data).Count)
# one invitation serves a second applicant (1:N)
call Post ("/invitation/" + $inv1.token + "/apply") $i.token @{ nickname = 'memI' } | Out-Null
$pendAll = call Get "/family/$famG/join-requests" $g.token
check 'same invitation serves 2 applicants' (@($pendAll.data).Count -eq 2) ("count=" + @($pendAll.data).Count)

# ---- cleanup ----
call Post "/family/$famA/dissolve" $a.token $null | Out-Null
call Post "/family/$famC/dissolve" $d.token $null | Out-Null
call Post "/family/$famG/dissolve" $g.token $null | Out-Null
Write-Host 'test families cleaned up'

Write-Host "`n=== summary: PASS=$($script:pass) FAIL=$($script:fail) ==="
if ($proc) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Write-Host 'test service stopped'
}
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
