# Quick happy-path smoke test (ASCII only).
# Requires a running service with WX_MOCK=true. Default base: http://127.0.0.1:8445/api
# Override with $env:API_BASE. For full coverage use script/run_smoke.ps1.

$ErrorActionPreference = "Stop"
$base = if ($env:API_BASE) { $env:API_BASE } else { "http://127.0.0.1:8445/api" }
$script:pass = 0
$script:fail = 0

function Call($method, $path, $token, $body) {
    $hdrs = @{}
    if ($token) { $hdrs["Authorization"] = "Bearer $token" }
    $hdrs["Content-Type"] = "application/json"
    $json = if ($null -ne $body) { $body | ConvertTo-Json -Compress } else { $null }
    try {
        return (Invoke-RestMethod -Method $method -Uri ($base + $path) -Headers $hdrs -Body $json)
    } catch {
        $msg = $_.ErrorDetails.Message
        if (-not $msg) { $msg = $_.Exception.Message }
        Write-Host "  [HTTP ERR] $method $path -> $msg" -ForegroundColor Red
        return $null
    }
}

function Check($name, $cond, $detail = "") {
    if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $name + " " + $detail) }
    else { $script:fail++; Write-Host ("  [FAIL] " + $name + " " + $detail) -ForegroundColor Red }
}

function Token($code) {
    $r = Call POST "/login" $null @{ code = $code }
    if ($r -and $r.data) { return $r.data.token }
    return $null
}

Write-Host "== quick smoke start base=$base ==" -ForegroundColor Cyan

$ts = (Get-Date).ToString("yyyyMMddHHmmssfff")
$owner = Token ("mock:owner_" + $ts)
$member = Token ("mock:member_" + $ts)
Check "owner login" ($null -ne $owner)
Check "member login" ($null -ne $member)

$f = Call POST "/family" $owner @{ name = "smoke_$ts" }
$familyId = if ($f -and $f.data) { [long]$f.data.familyId } else { 0 }
Check "family create" ($familyId -gt 0) ("familyId=" + $familyId)

$inv = Call POST "/invitation" $owner @{ familyId = $familyId; type = "share" }
$token = if ($inv -and $inv.data) { $inv.data.token } else { $null }
Check "invitation create" ($null -ne $token)

$pub = Call GET "/invitation/$token" $null $null
Check "invitation public view" ($pub -and $pub.code -eq 0 -and $pub.data.status -eq "active")

$apply = Call POST "/invitation/$token/apply" $member @{ nickname = "小明"; avatar = "http://x/a.png" }
Check "apply" ($apply -and $apply.data.status -eq "pending")

$pending = Call GET "/family/$familyId/join-requests" $owner
$requestId = if ($pending -and @($pending.data).Count -eq 1) { [long]$pending.data[0].requestId } else { 0 }
Check "pending list" ($requestId -gt 0) ("requestId=" + $requestId)

$ap = Call POST "/family/$familyId/join-request/$requestId/approve" $owner $null
Check "approve" ($ap -and $ap.code -eq 0)

$cats = Call GET "/categories?familyId=$familyId&type=expense" $member
$catId = if ($cats -and @($cats.data).Count -gt 0) { $cats.data[0].id } else { $null }
Check "categories" ($null -ne $catId)

$tx = Call POST "/transaction" $member @{ familyId = $familyId; type = "expense"; amount = 3500; categoryId = $catId; date = (Get-Date -Format "yyyy-MM-dd"); note = "午饭" }
$txId = if ($tx -and $tx.data) { [long]$tx.data.id } else { 0 }
Check "transaction create" ($txId -gt 0) ("id=" + $txId)

$list = Call GET "/transactions?familyId=$familyId" $member
Check "transactions list" ($list -and [int]$list.data.total -ge 1)
$stats = Call GET "/stats?familyId=$familyId&period=month" $member
Check "stats" ($stats -and [int64]$stats.data.expense -ge 3500) ("expense=" + $stats.data.expense)

$bud = Call PUT "/budget" $owner @{ familyId = $familyId; amount = 300000 }
Check "budget put" ($bud -and $bud.code -eq 0)
$budGet = Call GET "/budget?familyId=$familyId" $owner
Check "budget get" ($budGet -and $budGet.data.amount -eq 300000)

$pr = Call POST "/user/privacy" $owner @{ agreed = $true }
Check "privacy agree" ($pr -and $pr.code -eq 0)

$me = Call GET "/user/me" $owner
Check "user me" ($me -and $me.code -eq 0 -and $me.data.privacyAgreed -eq $true)

# cleanup
Call POST "/family/$familyId/dissolve" $owner $null | Out-Null
Write-Host "== quick smoke done: PASS=$($script:pass) FAIL=$($script:fail) ==" -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
