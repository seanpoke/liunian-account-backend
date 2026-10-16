# Liunian account - offline smoke test for family/quit/dissolve
# Requires: WX_MOCK=true (code=mock:<openid> logs in any user offline), DB/Redis from ci/.env
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8445/api'

# ---- load ci/.env into process env ----
Get-Content 'ci/.env' | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $i = $line.IndexOf('=')
    if ($i -le 0) { return }
    $k = $line.Substring(0, $i).Trim()
    $v = $line.Substring($i + 1).Trim()
    [Environment]::SetEnvironmentVariable($k, $v, 'Process')
}
[Environment]::SetEnvironmentVariable('WX_MOCK', 'true', 'Process')
Write-Host "WX_MOCK=$env:WX_MOCK  MYSQL_HOST=$env:MYSQL_HOST  REDIS_HOST=$env:REDIS_HOST"

# ---- start backend, stop after test ----
$jar = (Get-ChildItem target -Filter *.jar | Sort-Object LastWriteTime | Select-Object -First 1).FullName
if (-not $jar) { Write-Host 'no target/*.jar, run mvn package first'; exit 1 }
Write-Host "starting $jar"
$proc = Start-Process -FilePath 'java' -ArgumentList '-jar', $jar `
    -RedirectStandardOutput 'log/boot_smoke.log' -RedirectStandardError 'log/boot_smoke.err' -PassThru
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "$base/health" -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch {}
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    Write-Host 'service not ready, tail of boot_smoke.err:'
    Get-Content 'log/boot_smoke.err' -Tail 30
    Stop-Process -Id $proc.Id -Force
    exit 1
}
Write-Host 'service ready, running smoke tests'

# ---- helpers ----
function call($method, $path, $token, $body) {
    $h = @{}
    if ($token) { $h['Authorization'] = "Bearer $token" }
    $p = @{ Method = $method; Uri = "$base$path"; Headers = $h; ContentType = 'application/json' }
    if ($null -ne $body) { $p['Body'] = (ConvertTo-Json $body -Compress) }
    try { return Invoke-RestMethod @p } catch {
        $msg = ''
        if ($_.Exception.Response) {
            try {
                $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $msg = $sr.ReadToEnd()
            } catch {}
        }
        Write-Host "  [HTTP ERR] $method $path -> $($_.Exception.Response.StatusCode): $msg"
        return $null
    }
}

function check($name, $cond) {
    if ($cond) { Write-Host "  [PASS] $name" } else { Write-Host "  [FAIL] $name" }
}

function login($code) {
    $r = call Post '/login' $null @{ code = $code }
    if ($null -eq $r -or $null -eq $r.data) { throw "login failed: $code" }
    return $r.data
}

function joinViaInvite($ownerToken, $ownerOpenid, $familyId, $memberToken, $memberOpenid, $nick) {
    $inv = call Post '/invitation' $ownerToken @{ type = 'link'; familyId = $familyId }
    if ($null -eq $inv -or $null -eq $inv.data) { return }
    $token = $inv.data.token
    call Post "/invitation/$token/apply" $memberToken @{ nickname = $nick; avatar = '' } | Out-Null
    $pend = call Get "/family/$familyId/pending" $ownerToken
    $mid = ($pend.data | Where-Object { $_.applicantOpenid -eq $memberOpenid } | Select-Object -First 1).memberId
    if ($null -eq $mid) { return }
    call Post "/family/$familyId/member/$mid/approve" $ownerToken $null | Out-Null
}

function addTx($token, $familyId, $amount) {
    call Post '/transaction' $token @{ familyId = $familyId; type = 'expense'; amount = $amount; date = '2026-10-16' } | Out-Null
}

function txCount($token, $familyId) {
    $r = call Get "/transactions?familyId=$familyId" $token
    if ($null -eq $r -or $null -eq $r.data) { return -1 }
    return $r.data.total
}

$ts = (Get-Date -Format 'HHmmss')

Write-Host "`n=== Scenario 2: non-owner quits, family kept + history kept ==="
$uA = "mock:smk_${ts}_ownerA"; $uB = "mock:smk_${ts}_memB"
$a = login $uA; $b = login $uB
$famA = (call Post '/family' $a.token @{ name = "smokeA_$ts" }).data.familyId
check 'create family A returns id' ($famA -gt 0)
addTx $a.token $famA 100
joinViaInvite $a.token $a.openid $famA $b.token $b.openid 'memB'
$memA = call Get "/family/$famA/members" $a.token
check 'after join member count = 2' ($memA.data.Count -eq 2)
call Post "/family/$famA/quit" $b.token $null | Out-Null
$memA2 = call Get "/family/$famA/members" $a.token
check 'after memB quit member count = 1' ($memA2.data.Count -eq 1)
$famStill = call Get "/family/$famA" $a.token
check 'family A still exists' ($null -ne $famStill -and $null -ne $famStill.data)
check 'history kept (tx total >= 1)' ((txCount $a.token $famA) -ge 1)
$mineA = call Get '/families' $a.token
check 'ownerA /families contains A role=owner' (@($mineA.data | Where-Object { [long]$_.familyId -eq [long]$famA -and $_.role -eq 'owner' }).Count -eq 1)

Write-Host "`n=== Scenario 3: owner quits, auto transfer + history kept ==="
$uC = "mock:smk_${ts}_ownerC"; $uD = "mock:smk_${ts}_memD"
$c = login $uC; $d = login $uD
$famC = (call Post '/family' $c.token @{ name = "smokeC_$ts" }).data.familyId
addTx $c.token $famC 200
joinViaInvite $c.token $c.openid $famC $d.token $d.openid 'memD'
call Post "/family/$famC/quit" $c.token $null | Out-Null
$memC = call Get "/family/$famC/members" $d.token
$heir = $memC.data | Where-Object { $_.openid -eq $d.openid } | Select-Object -First 1
check 'after owner quit, memD promoted to owner' ($heir.role -eq 'owner')
$famCinfo = call Get "/family/$famC" $d.token
check 'family creatorOpenid transferred to memD' ($famCinfo.data.creatorOpenid -eq $d.openid)
check 'new owner sees history (tx total >= 1)' ((txCount $d.token $famC) -ge 1)

Write-Host "`n=== Scenario 1: last member quits -> dissolve family ==="
$uE = "mock:smk_${ts}_ownerE"
$e = login $uE
$famE = (call Post '/family' $e.token @{ name = "smokeE_$ts" }).data.familyId
addTx $e.token $famE 300
call Post "/family/$famE/quit" $e.token $null | Out-Null
$gone = call Get "/family/$famE" $e.token
check 'after quit family gone (dissolved)' (($null -eq $gone) -or ($gone.code -eq 403) -or ($gone.code -eq 400))
$mineE = call Get '/families' $e.token
check 'ownerE has no families left' ($mineE.data.Count -eq 0)

Write-Host "`n=== Extra: explicit dissolve endpoint ==="
$uF = "mock:smk_${ts}_ownerF"
$f = login $uF
$famF = (call Post '/family' $f.token @{ name = "smokeF_$ts" }).data.familyId
addTx $f.token $famF 999
$txBefore = txCount $f.token $famF
$diss = call Post "/family/$famF/dissolve" $f.token $null
$goneF = call Get "/family/$famF" $f.token
check 'tx existed before dissolve' ($txBefore -ge 1)
check 'after dissolve family gone (403/400/null)' (($null -eq $goneF) -or ($goneF.code -eq 403) -or ($goneF.code -eq 400))

# ---- cleanup: dissolve leftover test families (scenarios 2 & 3) so dev DB stays clean ----
call Post "/family/$famA/dissolve" $a.token $null | Out-Null
call Post "/family/$famC/dissolve" $d.token $null | Out-Null
Write-Host 'test families cleaned up'

Write-Host "`n=== smoke done ==="
Stop-Process -Id $proc.Id -Force
Write-Host 'test service stopped'
