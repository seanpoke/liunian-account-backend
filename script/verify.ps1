# liunian-account-backend API contract verification (ASCII only)
# Covers every endpoint: auth -> family -> invitation/join-request -> members ->
# category -> reminder/privacy/wxacode -> transaction/stats -> budget -> upload ->
# user profile -> quit(dissolve) etc.
#
# Requires a running service with WX_MOCK=true (mock login uses code "mock:<openid>").
# Default base: http://127.0.0.1:8445/api ; override with $env:API_BASE.
# Recommended: use script/run_smoke.ps1 (starts an isolated test instance).

$base = if ($env:API_BASE) { $env:API_BASE } else { "http://127.0.0.1:8445/api" }
$rootBase = $base -replace '/api/?$', ''
$ErrorActionPreference = "SilentlyContinue"
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
        $m = $_.ErrorDetails.Message
        if (-not $m) { $m = $_.Exception.Message }
        try { return ($m | ConvertFrom-Json) } catch { return @{ code = 999; msg = $m } }
    }
}

function Record($name, $method, $path, $r, $expect = 0) {
    $c = if ($r -and $null -ne $r.code) { $r.code } else { 999 }
    $ok = ($c -eq $expect)
    if ($ok) { $script:pass++ } else { $script:fail++ }
    Write-Host ("[{0}] {1,-24} {2,-6} {3} code={4}" -f $(if ($ok) { "OK  " } else { "FAIL" }), $name, $method, $path, $c)
    if (-not $ok -and $r -and $r.msg) { Write-Host ("       msg=" + $r.msg) -ForegroundColor DarkYellow }
    return $r
}

function Check($name, $method, $path, $token, $body, $expect = 0) {
    return (Record $name $method $path (Call $method $path $token $body) $expect)
}

function Assert($name, $cond, $detail = "") {
    if ($cond) {
        $script:pass++
        Write-Host ("[OK  ] {0,-24} {1}" -f $name, $detail)
    } else {
        $script:fail++
        Write-Host ("[FAIL] {0,-24} {1}" -f $name, $detail) -ForegroundColor Red
    }
}

function Login($code) {
    for ($i = 0; $i -lt 5; $i++) {
        $r = Call POST "/login" $null @{ code = $code }
        if ($r -and $r.code -eq 0 -and $r.data -and $r.data.token) { return $r.data }
        Start-Sleep -Milliseconds 500
    }
    Write-Host ("[FATAL] login failed for " + $code + " (is WX_MOCK=true?)") -ForegroundColor Red
    exit 1
}

# multipart upload without PowerShell 7's -Form
function UploadFile($token, $familyId, $filePath) {
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Authorization =
        New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $token)
    $content = New-Object System.Net.Http.MultipartFormDataContent
    $bytes = [IO.File]::ReadAllBytes($filePath)
    $fileContent = [System.Net.Http.ByteArrayContent]::new([byte[]]$bytes)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("image/png")
    $content.Add($fileContent, "file", [IO.Path]::GetFileName($filePath))
    if ($familyId) {
        $content.Add([System.Net.Http.StringContent]::new([string]$familyId), "familyId")
    }
    $resp = $client.PostAsync(($base + "/upload"), $content).Result
    $text = $resp.Content.ReadAsStringAsync().Result
    $client.Dispose()
    try { return ($text | ConvertFrom-Json) } catch { return @{ code = 999; msg = $text } }
}

$ts = (Get-Date).ToString("yyyyMMddHHmmssfff")
Write-Host ("== verify start base=" + $base + " ==") -ForegroundColor Cyan

# ---------- auth ----------
Write-Host "==== auth ===="
$oData = Login ("mock:vo_" + $ts)
$o = $oData.token
$m2Data = Login ("mock:vm2_" + $ts)
$m2 = $m2Data.token
$m3Data = Login ("mock:vm3_" + $ts)
$m3 = $m3Data.token
$m4Data = Login ("mock:vm4_" + $ts)
$m4 = $m4Data.token
Assert "login-owner" ($null -ne $o) ("isMember=" + $oData.isMember + " privacyAgreed=" + $oData.privacyAgreed)

Check "health" GET "/health" $null $null | Out-Null
Check "login-blank-code" POST "/login" $null @{ code = "" } 400 | Out-Null
Check "unauthorized" GET "/user/me" $null $null 401 | Out-Null

# ---------- family ----------
Write-Host "==== family ===="
$fc = Check "family-create" POST "/family" $o @{ name = "verify_$ts" }
$f = [long]$fc.data.familyId
Assert "family-create-id" ($f -gt 0) ("familyId=" + $f)
Check "family-create-blank" POST "/family" $o @{ name = "  " } 400 | Out-Null
$fg = Check "family-get" GET ("/family/" + $f) $o $null
Assert "family-member-count" ([int]$fg.data.memberCount -eq 1) ("memberCount=" + $fg.data.memberCount)
Check "family-patch" PATCH ("/family/" + $f) $o @{ name = "verify2_$ts"; currency = "CNY" } | Out-Null
Check "families" GET "/families" $o $null | Out-Null

# ---------- invitation / join request ----------
Write-Host "==== invitation / join-request ===="
$inv1 = Check "invitation-create" POST "/invitation" $o @{ familyId = $f; type = "share" }
$T = $inv1.data.token
Assert "invitation-reused-flag" ($null -ne $inv1.data.reused) ("reused=" + $inv1.data.reused)
$inv2 = Check "invitation-create-again" POST "/invitation" $o @{ familyId = $f; type = "share" }
Assert "invitation-reuse-same-token" ($inv2.data.token -eq $T) ("token2=" + $inv2.data.token)
$invGet = Check "invitation-get-anon" GET ("/invitation/" + $T) $null $null
Assert "invitation-status-active" ($invGet.data.status -eq "active") ("status=" + $invGet.data.status)

Check "invitation-apply-m2" POST ("/invitation/" + $T + "/apply") $m2 @{ nickname = "M2"; avatar = "" } | Out-Null
Check "invitation-apply-m2-idempotent" POST ("/invitation/" + $T + "/apply") $m2 @{ nickname = "M2b" } | Out-Null
$pend = Check "join-requests" GET ("/family/" + $f + "/join-requests") $o $null
$rid2 = [long]$pend.data[0].requestId
Assert "join-request-single-pending" (@($pend.data).Count -eq 1) ("count=" + @($pend.data).Count)
Check "join-request-approve" POST ("/family/" + $f + "/join-request/" + $rid2 + "/approve") $o $null | Out-Null
Check "join-request-approve-twice" POST ("/family/" + $f + "/join-request/" + $rid2 + "/approve") $o $null 400 | Out-Null
$members = Check "members" GET ("/family/" + $f + "/members") $o $null
Assert "members-count-2" (@($members.data).Count -eq 2) ("count=" + @($members.data).Count)
Assert "members-no-status-field" ($null -eq $members.data[0].status) "status field removed"

Check "apply-M3" POST ("/invitation/" + $T + "/apply") $m3 @{ nickname = "M3" } | Out-Null
$p3 = Call GET ("/family/" + $f + "/join-requests") $o
$rid3 = [long]($p3.data | Where-Object { $_.applicantOpenid -eq $m3Data.openid } | Select-Object -First 1).requestId
Check "approve-M3" POST ("/family/" + $f + "/join-request/" + $rid3 + "/approve") $o $null | Out-Null

Check "apply-M4" POST ("/invitation/" + $T + "/apply") $m4 @{ nickname = "M4" } | Out-Null
$p4 = Call GET ("/family/" + $f + "/join-requests") $o
$script:rid4 = [long]($p4.data | Where-Object { $_.applicantOpenid -eq $m4Data.openid } | Select-Object -First 1).requestId
$members3 = Check "members-after-M3" GET ("/family/" + $f + "/members") $o $null
Assert "members-count-3" (@($members3.data).Count -eq 3) ("count=" + @($members3.data).Count)

# ---------- category ----------
Write-Host "==== category ===="
$cats = Check "categories" GET ("/categories?familyId=" + $f + "&type=expense") $o $null
$food = $cats.data | Where-Object { $_.code -eq "food" } | Select-Object -First 1
Assert "category-system-food" ($null -ne $food -and $food.isSystem -eq 1) ("id=" + $food.id)
$cc = Check "category-create" POST "/category" $o @{ familyId = $f; name = "baby_$ts"; type = "expense"; icon = "x"; color = "#FFB74D" }
$cid = [long]$cc.data.id
Assert "category-code-empty" ($cc.data.code -eq "") ("code=" + $cc.data.code)
Check "category-duplicate-code" POST "/category" $o @{ familyId = $f; name = "dup_$ts"; type = "expense"; code = "food" } 400 | Out-Null
Check "category-bad-code" POST "/category" $o @{ familyId = $f; name = "bad_$ts"; type = "expense"; code = "Bad-Code" } 400 | Out-Null
Check "category-delete" DELETE ("/category/" + $cid) $o $null | Out-Null
Check "category-delete-system" DELETE ("/category/" + $food.id) $o $null 403 | Out-Null

# ---------- reminder / privacy / wxacode ----------
Write-Host "==== reminder / privacy / wxacode ===="
Check "reminder-get" GET ("/reminder?familyId=" + $f) $m2 $null | Out-Null
$rp = Check "reminder-put" PUT "/reminder" $m2 @{ familyId = $f; enabled = 1; time = "20:00" }
Assert "reminder-echo" ($rp.data.enabled -eq 1 -and $rp.data.time -eq "20:00") ("time=" + $rp.data.time)
Check "privacy-get" GET "/user/privacy" $o $null | Out-Null
Check "privacy-post" POST "/user/privacy" $o @{ agreed = $true } | Out-Null
$wx = Check "wxacode" GET ("/wxacode?familyId=" + $f) $o $null
Assert "wxacode-url" ($null -ne $wx.data.qrImageUrl -and $wx.data.qrImageUrl.Length -gt 0) "qrImageUrl present"

# ---------- member self ----------
Write-Host "==== member self ===="
Check "member-me" PATCH ("/family/" + $f + "/member/me") $m2 @{ nickname = "Mb" } | Out-Null

# ---------- transaction / stats ----------
Write-Host "==== transaction ===="
$txr = Check "transaction-create" POST "/transaction" $m2 @{ familyId = $f; type = "expense"; amount = 3500; categoryId = $food.id; date = "2026-09-28"; note = "lunch"; images = @() }
$TX = [long]$txr.data.id
Assert "transaction-id" ($TX -gt 0) ("id=" + $TX)
$txg = Check "transaction-get" GET ("/transaction/" + $TX) $m2 $null
Assert "transaction-fields" ($txg.data.amount -eq 3500 -and -not [string]::IsNullOrEmpty($txg.data.categoryName)) ("categoryId=" + $txg.data.categoryId)
Check "transaction-put" PUT ("/transaction/" + $TX) $m2 @{ amount = 4000; note = "dinner" } | Out-Null
$txg2 = Call GET ("/transaction/" + $TX) $m2
Assert "transaction-updated" ($txg2.data.amount -eq 4000 -and $txg2.data.note -eq "dinner") ("amount=" + $txg2.data.amount)
Check "transactions" GET ("/transactions?familyId=" + $f + "&start=2026-09-01&end=2026-09-30&page=1&size=20") $m2 $null | Out-Null
Check "transactions-byday" GET ("/transactions/by-day?familyId=" + $f + "&start=2026-09-01&end=2026-09-30") $m2 $null | Out-Null
$txOwner = Check "transaction-create-owner" POST "/transaction" $o @{ familyId = $f; type = "expense"; amount = 1000; date = "2026-09-28"; note = "owner" }
$s1 = Check "stats-month" GET ("/stats?familyId=" + $f + "&year=2026&month=9") $m2 $null
Assert "stats-expense-5000" ([int64]$s1.data.expense -eq 5000) ("expense=" + $s1.data.expense)
Check "stats-custom-missing-range" GET ("/stats?familyId=" + $f + "&period=custom") $m2 $null 400 | Out-Null
Check "stats-bad-month" GET ("/stats?familyId=" + $f + "&month=2026-09") $m2 $null 400 | Out-Null
Check "transaction-bad-date" POST "/transaction" $m2 @{ familyId = $f; type = "expense"; amount = 100; date = "2026/09/28" } 400 | Out-Null
Check "transaction-del" DELETE ("/transaction/" + $TX) $m2 $null | Out-Null
$s2 = Call GET ("/stats?familyId=" + $f + "&year=2026&month=9") $m2
Assert "stats-after-delete-1000" ([int64]$s2.data.expense -eq 1000) ("expense=" + $s2.data.expense)

# ---------- budget ----------
Write-Host "==== budget ===="
Check "budget-get" GET ("/budget?familyId=" + $f) $o $null | Out-Null
$bp = Check "budget-put" PUT "/budget" $o @{ familyId = $f; amount = 500000 }
Assert "budget-echo" ($bp.data.amount -eq 500000) ("amount=" + $bp.data.amount)

# ---------- upload ----------
Write-Host "==== upload ===="
$png = Join-Path $env:TEMP ("smk_" + $ts + ".png")
[IO.File]::WriteAllBytes($png, [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
$up = UploadFile $m2 $f $png
Remove-Item $png -Force -ErrorAction SilentlyContinue
Assert "upload-ok" ($up.code -eq 0 -and $null -ne $up.data.url) ("url=" + $up.data.url)
if ($up.code -eq 0) {
    $static = Invoke-WebRequest -Uri ($rootBase + $up.data.url) -UseBasicParsing -TimeoutSec 5
    Assert "upload-static-read" ($static.StatusCode -eq 200) ("status=" + $static.StatusCode)
    $localFile = Join-Path (Join-Path $PSScriptRoot '..') ("data/upload/" + $up.data.key)
    Remove-Item $localFile -Force -ErrorAction SilentlyContinue
}

# ---------- user profile ----------
Write-Host "==== user profile ===="
$me = Check "user-me-get" GET "/user/me" $m2 $null
Assert "user-me-fields" ($me.data.openid -eq $m2Data.openid) ("openid=" + $me.data.openid)
Check "user-me-patch" PATCH "/user/me" $m2 @{ nickname = "Nick_$ts"; avatar = "https://x/a.png" } | Out-Null
$me2 = Call GET "/user/me" $m2
Assert "user-me-updated" ($me2.data.nickname -eq "Nick_$ts") ("nickname=" + $me2.data.nickname)

# ---------- reject / remove / transfer / quit / revoke / dissolve ----------
Write-Host "==== member management ===="
Check "join-request-reject" POST ("/family/" + $f + "/join-request/" + $script:rid4 + "/reject") $o @{ remark = "no" } | Out-Null
Check "join-request-reject-twice" POST ("/family/" + $f + "/join-request/" + $script:rid4 + "/reject") $o @{ remark = "no" } 400 | Out-Null
$m2MemberId = [long](($members3.data | Where-Object { $_.openid -eq $m2Data.openid } | Select-Object -First 1).memberId)
Check "member-delete" DELETE ("/family/" + $f + "/member/" + $m2MemberId) $o $null | Out-Null
Check "transfer" POST ("/family/" + $f + "/transfer") $o @{ toOpenid = $m3Data.openid } | Out-Null
Check "quit-as-member" POST ("/family/" + $f + "/quit") $o $null | Out-Null
$s3 = Call GET ("/stats?familyId=" + $f + "&year=2026&month=9") $m3
Assert "quit-voids-own-tx" ([int64]$s3.data.expense -eq 0) ("expense=" + $s3.data.expense)
Check "invitation-revoke" POST "/invitation/revoke" $m3 @{ token = $T } | Out-Null
Check "invitation-get-after-revoke" GET ("/invitation/" + $T) $null $null 410 | Out-Null
Check "family-dissolve" POST ("/family/" + $f + "/dissolve") $m3 $null | Out-Null
Check "family-get-after-dissolve" GET ("/family/" + $f) $m3 $null 403 | Out-Null

Write-Host "==== summary ===="
Write-Host ("PASS=" + $script:pass + " FAIL=" + $script:fail)
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
