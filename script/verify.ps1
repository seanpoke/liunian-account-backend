# liunian-account-backend full verification script (persistent)
# Covers all backend endpoints: owner + 3 members login -> family -> invitation
# flow -> category/reminder/privacy/wxacode -> member self -> transaction ->
# budget -> invitation revoke + member management.
# ASCII only (no Chinese) to avoid encoding corruption on Windows.
# Usage: powershell -ExecutionPolicy Bypass -File script/verify.ps1
#   optional: $env:API_BASE = "http://host:8445/api"

$base = if ($env:API_BASE) { $env:API_BASE } else { "http://127.0.0.1:8445/api" }
$ErrorActionPreference = "SilentlyContinue"
$script:pass = 0
$script:fail = 0

function Call($method, $path, $token, $body) {
    $hdrs = @{}
    if ($token) { $hdrs["Authorization"] = "Bearer $token" }
    $hdrs["Content-Type"] = "application/json"
    $json = if ($body) { $body | ConvertTo-Json -Compress } else { $null }
    try {
        return (Invoke-RestMethod -Method $method -Uri ($base + $path) -Headers $hdrs -Body $json)
    } catch {
        $m = $_.ErrorDetails.Message
        if (-not $m) { $m = $_.Exception.Message }
        try { return ($m | ConvertFrom-Json) } catch { return @{ code = 999; msg = $m } }
    }
}

function Record($name, $method, $path, $r, $expect=0) {
    $c = if ($r -and $null -ne $r.code) { $r.code } else { 999 }
    $ok = ($c -eq $expect)
    if ($ok) { $script:pass++ } else { $script:fail++ }
    Write-Host ("[{0}] {1,-18} {2,-6} {3} code={4}" -f $(if($ok){"OK  "}else{"FAIL"}), $name, $method, $path, $c)
    return $r
}

function Check($name, $method, $path, $token, $body, $expect=0) {
    return (Record $name $method $path (Call $method $path $token $body) $expect)
}

function Login($code) {
    $last = $null
    for ($i = 0; $i -lt 8; $i++) {
        $r = Call POST "/login" $null @{code=$code}
        if ($r -and $r.code -eq 0 -and $r.data -and $r.data.token) { return $r }
        $last = $r
        Start-Sleep -Seconds 1
    }
    Write-Host ("[WARN] login failed for " + $code)
    return $last
}

$ts = (Get-Date).ToString("yyyyMMddHHmmssfff")
$oi  = Login ("mock:vo_"  + $ts); $o = $oi.data.token;  $oOpenid = $oi.data.openid
$mi2 = Login ("mock:vm2_" + $ts); $m2 = $mi2.data.token; $m2Openid = $mi2.data.openid
$mi3 = Login ("mock:vm3_" + $ts); $m3 = $mi3.data.token
$mi4 = Login ("mock:vm4_" + $ts); $m4 = $mi4.data.token

Write-Output ("oL=" + $o.Length + " m2L=" + $(if($m2){$m2.Length}else{"NULL"}) + " m3L=" + $(if($m3){$m3.Length}else{"NULL"}) + " m4L=" + $(if($m4){$m4.Length}else{"NULL"}))

# member tokens must be valid (PowerShell is case-insensitive: $m2 != $mid2)
Check "m2-probe" GET "/user/privacy" $m2 $null

Write-Host "==== auth ===="
Check "login" POST "/login" $null @{code=("mock:vo_"+$ts)}
Check "health" GET "/health" $null $null

Write-Host "==== family + invitation flow ===="
$f  = (Check "family-create" POST "/family" $o @{name="verify"}).data.familyId
$T1 = (Call POST "/invitation" $o @{familyId=$f; type="share"}).data.token
Check "invitation-apply" POST ("/invitation/"+$T1+"/apply") $m2 @{nickname="M2"}
$mid1 = (Call GET ("/family/"+$f+"/pending") $o).data[0].memberId
Check "approve" POST ("/family/"+$f+"/member/"+$mid1+"/approve") $o $null
$Ta = (Call POST "/invitation" $o @{familyId=$f; type="share"}).data.token
Check "invitation-apply" POST ("/invitation/"+$Ta+"/apply") $m3 @{nickname="M3"}
$mid2 = (Call GET ("/family/"+$f+"/pending") $o).data[0].memberId
Check "approve" POST ("/family/"+$f+"/member/"+$mid2+"/approve") $o $null
$T2 = (Call POST "/invitation" $o @{familyId=$f; type="share"}).data.token
$Tr = (Call POST "/invitation" $o @{familyId=$f; type="share"}).data.token
Check "invitation-apply" POST ("/invitation/"+$Tr+"/apply") $m4 @{nickname="M4"}
$mid3 = (Call GET ("/family/"+$f+"/pending") $o).data[0].memberId
Check "invitation-get" GET ("/invitation/"+$T2) $null $null

Write-Host "==== family / members ===="
Check "family-get" GET ("/family/"+$f) $o $null
Check "family-patch" PATCH ("/family/"+$f) $o @{name="verify2"; currency="CNY"}
Check "members" GET ("/family/"+$f+"/members") $o $null
Check "pending" GET ("/family/"+$f+"/pending") $o $null

Write-Host "==== category ===="
Check "categories" GET ("/categories?familyId="+$f+"&type=expense") $o $null
$cres = Call POST "/category" $o @{familyId=$f; name="baby"; type="expense"; icon="baby"; color="#FFB74D"}
$CID = if ($cres.data) { $cres.data.id }
Record "category-create" "POST" "/category" $cres 0
Check "category-delete" DELETE ("/category/"+$CID) $o $null

Write-Host "==== reminder / privacy / wxacode ===="
Check "m2-probe2" GET "/user/privacy" $m2 $null
Check "reminder-get" GET ("/reminder?familyId="+$f) $m2 $null
Check "reminder-put" PUT "/reminder" $m2 @{familyId=$f; enabled=1; time="20:00"}
Check "wxacode" GET ("/wxacode?familyId="+$f) $o $null
Check "privacy-get" GET "/user/privacy" $o $null
Check "privacy-post" POST "/user/privacy" $o @{agreed=$true}

Write-Host "==== member self ===="
Check "member-me" PATCH ("/family/"+$f+"/member/me") $m2 @{nickname="Mb"}

Write-Host "==== transaction ===="
$txr = Call POST "/transaction" $m2 @{type="expense"; amount=3500; categoryId=1; date="2026-09-28"; note="lunch"; images=@()}
$TX = if ($txr.data) { $txr.data.id }
Record "transaction-create" "POST" "/transaction" $txr 0
Check "transaction-get" GET ("/transaction/"+$TX) $m2 $null
Check "transaction-put" PUT ("/transaction/"+$TX) $m2 @{amount=4000; note="dinner"}
Check "transaction-del" DELETE ("/transaction/"+$TX) $m2 $null
Check "transactions" GET ("/transactions?familyId="+$f+"&month=2026-09") $m2 $null
Check "transactions-byday" GET "/transactions/by-day?start=2026-09-01&end=2026-09-30" $m2 $null
Check "stats" GET ("/stats?familyId="+$f+"&month=2026-09") $m2 $null

Write-Host "==== budget ===="
Check "budget-get" GET ("/budget?familyId="+$f) $o $null
Check "budget-put" PUT "/budget" $o @{familyId=$f; month="2026-09"; amount=500000}

Write-Host "==== invitation revoke + member mgmt ===="
Check "invitation-revoke" POST "/invitation/revoke" $o @{token=$T2}
Check "member-delete" DELETE ("/family/"+$f+"/member/"+$mid2) $o $null
Check "transfer" POST ("/family/"+$f+"/transfer") $o @{toOpenid=$m2Openid}
Check "quit" POST ("/family/"+$f+"/quit") $o $null
Check "member-reject" POST ("/family/"+$f+"/member/"+$mid3+"/reject") $m2 $null

Write-Host "==== summary ===="
Write-Host ("PASS=" + $script:pass + " FAIL=" + $script:fail)
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
