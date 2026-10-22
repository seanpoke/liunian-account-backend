# liunian-account-backend saving module smoke test (ASCII only).
#
# Covers doc/储蓄功能设计.md acceptance points that are backend-observable:
#   preset accounts, deposit/withdraw (hard block), transfer (zero-sum, paired),
#   balance adjust (server decides direction, 24h confirm, amount immutable),
#   "pay from saving" / "income into saving" combos, paired edit/delete,
#   member removal keeps combo records, account disable/delete rules,
#   20 custom account limit, family dissolve cleanup.
#
# Modes:
#   1) external  : set API_BASE, run against a running instance with WX_MOCK=true
#   2) standalone: no API_BASE -> starts target/*.jar on port 8447
#
# Always reads .env (project root) or ci/.env and refuses unless MYSQL_DB is liunian_account_dev / liunian_account_test.
#
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less scripts as ANSI).
# Chinese account names are rebuilt from code points instead of being embedded.

param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$envFile = Join-Path $PSScriptRoot '..\.env'
if (-not (Test-Path $envFile)) { $envFile = Join-Path $PSScriptRoot '..\ci\.env' }
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

$external = $env:API_BASE
$port = 8447
$proc = $null
if ($external) {
    $base = $external.TrimEnd('/')
} else {
    $base = "http://127.0.0.1:$port/api"
    [Environment]::SetEnvironmentVariable('WX_MOCK', 'true', 'Process')
    $projRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
    if (-not $env:JAVA_HOME -and (Test-Path 'E:\JDK\jdk-17.0.19')) { $env:JAVA_HOME = 'E:\JDK\jdk-17.0.19' }
    if (-not $SkipBuild) {
        Write-Host 'building jar ...'
        & 'D:\Program Files\maven\bin\mvn.cmd' -B -q -f (Join-Path $projRoot 'pom.xml') -DskipTests package
        if ($LASTEXITCODE -ne 0) { Write-Host 'maven build failed' -ForegroundColor Red; exit 1 }
    }
    $jar = (Get-ChildItem (Join-Path $projRoot 'target') -Filter '*.jar' | Sort-Object LastWriteTime | Select-Object -First 1)
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path $java)) { $java = 'java' }
    $log = Join-Path $projRoot 'log\boot_saving.log'
    $err = Join-Path $projRoot 'log\boot_saving.err'
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
        Write-Host 'service not ready; tail of boot_saving.err:' -ForegroundColor Red
        if (Test-Path $err) { Get-Content $err -Encoding UTF8 -Tail 40 }
        if ($proc) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        exit 1
    }
}

$script:pass = 0
$script:fail = 0

# Windows PowerShell 5.1 decodes JSON responses as ISO-8859-1 when the server does not
# declare a charset, which turns Chinese fields into mojibake and breaks name comparisons.
# Read raw bytes and decode as UTF-8 explicitly.
function Decode($resp) {
    $text = [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    try { return ($text | ConvertFrom-Json) } catch { return @{ code = 999; msg = $text } }
}

function Call($method, $path, $token, $body) {
    $hdrs = @{}
    if ($token) { $hdrs["Authorization"] = "Bearer $token" }
    $bytes = $null
    if ($null -ne $body) {
        $hdrs["Content-Type"] = "application/json; charset=utf-8"
        $bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))
    }
    try {
        $resp = Invoke-WebRequest -Method $method -Uri ($base + $path) -Headers $hdrs -Body $bytes -UseBasicParsing
        return (Decode $resp)
    } catch {
        $r = $_.Exception.Response
        if ($r) {
            try {
                $sr = New-Object IO.StreamReader($r.GetResponseStream(), [Text.Encoding]::UTF8)
                $text = $sr.ReadToEnd()
                try { return ($text | ConvertFrom-Json) } catch { return @{ code = 999; msg = $text } }
            } catch {
                return @{ code = 999; msg = $_.Exception.Message }
            }
        }
        return @{ code = 999; msg = $_.Exception.Message }
    }
}

function Check($name, $method, $path, $token, $body, $expect = 0) {
    $r = Call $method $path $token $body
    $c = if ($r -and $null -ne $r.code) { $r.code } else { 999 }
    $ok = ($c -eq $expect)
    if ($ok) { $script:pass++ } else { $script:fail++ }
    Write-Host ("[{0}] {1,-30} {2,-6} {3} code={4}" -f $(if ($ok) { "OK  " } else { "FAIL" }), $name, $method, $path, $c)
    if (-not $ok) {
        if ($r -and $r.msg) { Write-Host ("       msg=" + $r.msg) -ForegroundColor DarkYellow }
        else { Write-Host ("       unexpected=" + ($r | ConvertTo-Json -Compress -Depth 3)) -ForegroundColor DarkYellow }
    }
    return $r
}

function Assert($name, $cond, $detail = "") {
    if ($cond) {
        $script:pass++
        Write-Host ("[OK  ] {0,-30} {1}" -f $name, $detail)
    } else {
        $script:fail++
        Write-Host ("[FAIL] {0,-30} {1}" -f $name, $detail) -ForegroundColor Red
    }
}

function Login($code) {
    for ($i = 0; $i -lt 5; $i++) {
        $r = Call POST "/login" $null @{ code = $code }
        if ($r -and $r.code -eq 0 -and $r.data -and $r.data.token) { return $r.data }
        Start-Sleep -Milliseconds 500
    }
    Write-Host ("[FATAL] login failed for " + $code + " (is WX_MOCK=true?)") -ForegroundColor Red
    if ($proc) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    exit 1
}

# overview helpers
function Total($token, $f) {
    $r = Call GET ("/saving/overview?familyId=" + $f) $token
    if (-not $r -or $r.code -ne 0) { return -999999 }
    return [long]$r.data.balance
}
function AccBal($token, $f, $aid) {
    $r = Call GET ("/saving/overview?familyId=" + $f + "&accountId=" + $aid) $token
    if (-not $r -or $r.code -ne 0) { return -999999 }
    return [long]$r.data.balance
}
function StatsOf($token, $f, $y, $m) {
    return (Call GET ("/stats?familyId=" + $f + "&year=" + $y + "&month=" + $m) $token)
}
function Today() { return (Get-Date).ToString('yyyy-MM-dd') }
function Yesterday() { return (Get-Date).AddDays(-1).ToString('yyyy-MM-dd') }

# preset account names rebuilt from code points (ASCII-only source)
$nCur = [string][char]0x6D3B + [string][char]0x671F    # 活期
$nFix = [string][char]0x5B9A + [string][char]0x671F    # 定期
$nBond = [string][char]0x56FD + [string][char]0x503A   # 国债
$nFund = [string][char]0x57FA + [string][char]0x91D1   # 基金
$nIns = [string][char]0x4FDD + [string][char]0x9669    # 保险
$wPreset = [string][char]0x9884 + [string][char]0x7F6E  # 预置

$ts = (Get-Date).ToString("yyyyMMddHHmmssfff")
$y = (Get-Date).Year
$mo = (Get-Date).Month
Write-Host ("== saving smoke start base=" + $base + " ==") -ForegroundColor Cyan

# ---------------------------------------------------------------- setup
$o = (Login ("mock:vo_sv" + $ts)).token
$m2d = Login ("mock:vm2_sv" + $ts)
$m2 = $m2d.token
$fc = Check "family-create" POST "/family" $o @{ name = "smokeS_$ts" }
$f = [long]$fc.data.familyId
$inv = Check "invite" POST "/invitation" $o @{ familyId = $f; type = "share" }
$tok = $inv.data.token
Check "apply-m2" POST ("/invitation/" + $tok + "/apply") $m2 @{ nickname = "M2" } | Out-Null
$pend = Call GET ("/family/" + $f + "/join-requests") $o
$rid = [long](@($pend.data) | Where-Object { $_.applicantOpenid -eq $m2d.openid } | Select-Object -First 1).requestId
Check "approve-m2" POST ("/family/" + $f + "/join-request/" + $rid + "/approve") $o $null | Out-Null

# ---------------------------------------------------------------- preset accounts
Write-Host "==== preset accounts ===="
$accR = Check "accounts" GET ("/saving/accounts?familyId=" + $f) $o $null
$acc = @($accR.data)
Assert "preset-count-5" ($acc.Count -eq 5) ("count=" + $acc.Count)
$names = (@($acc | ForEach-Object { $_.name }) -join ',')
$expectNames = (@($nCur, $nFix, $nBond, $nFund, $nIns) -join ',')
Assert "preset-names-default" ($names -eq $expectNames) ("names=" + $names)
Assert "preset-all-system" ((@($acc | Where-Object { $_.isSystem -ne 1 })).Count -eq 0) "all isSystem=1"
$curId = [long](@($acc | Where-Object { $_.name -eq $nCur } | Select-Object -First 1).id)
$bondId = [long](@($acc | Where-Object { $_.name -eq $nBond } | Select-Object -First 1).id)
Assert "preset-balance-zero" ((@($acc | Where-Object { $_.balance -ne 0 })).Count -eq 0) "all balance=0"

$ov = Check "overview-empty" GET ("/saving/overview?familyId=" + $f) $o $null
Assert "overview-accountCount-5" ([int]$ov.data.accountCount -eq 5) ("accountCount=" + $ov.data.accountCount)
Assert "overview-balance-0" ([long]$ov.data.balance -eq 0) ("balance=" + $ov.data.balance)

Check "member-cannot-create-account" POST "/saving/account" $m2 @{ familyId = $f; name = "X$ts" } 403 | Out-Null
$newAcc = Check "custom-create" POST "/saving/account" $o @{ familyId = $f; name = "TRAVEL$ts"; icon = "X"; color = "#123456" }
$travelId = [long]$newAcc.data.id
Assert "custom-create-fields" ([int]$newAcc.data.isSystem -eq 0 -and $newAcc.data.sort -gt 5) ("sort=" + $newAcc.data.sort)
Check "custom-dup-name" POST "/saving/account" $o @{ familyId = $f; name = "TRAVEL$ts" } 400 | Out-Null
$ren = Check "custom-rename" PUT ("/saving/account/" + $travelId) $o @{ name = "TRIP$ts" }
Assert "custom-rename-echo" ($ren.data.name -eq "TRIP$ts") ("name=" + $ren.data.name)
Check "custom-bad-color" PUT ("/saving/account/" + $travelId) $o @{ color = "red" } 400 | Out-Null
Check "preset-delete-forbidden" DELETE ("/saving/account/" + $bondId) $o $null 403 | Out-Null

# ---------------------------------------------------------------- deposit / withdraw
Write-Host "==== deposit / withdraw ===="
$d1 = Check "deposit-default-account" POST "/saving/deposit" $o @{ familyId = $f; amount = 300000; note = "first" }
Assert "deposit-lands-on-default" ($d1.data.accountId -eq $curId) ("accountId=" + $d1.data.accountId)
Assert "deposit-balance" ([long]$d1.data.balance -eq 300000) ("balance=" + $d1.data.balance)
$ov = Call GET ("/saving/overview?familyId=" + $f) $o
Assert "overview-monthIn" ([long]$ov.data.monthIn -eq 300000) ("monthIn=" + $ov.data.monthIn)
Assert "overview-monthOut-0" ([long]$ov.data.monthOut -eq 0) ("monthOut=" + $ov.data.monthOut)
Assert "overview-monthNet" ([long]$ov.data.monthNet -eq 300000) ("monthNet=" + $ov.data.monthNet)
$st = StatsOf $o $f $y $mo
Assert "stats-untouched-by-deposit" ([long]$st.data.income -eq 0 -and [long]$st.data.expense -eq 0) ("income=" + $st.data.income + " expense=" + $st.data.expense)

$w1 = Check "withdraw" POST "/saving/withdraw" $o @{ familyId = $f; accountId = $curId; amount = 120000 }
Assert "withdraw-balance" ([long]$w1.data.balance -eq 180000) ("balance=" + $w1.data.balance)
$w2 = Check "withdraw-over-balance" POST "/saving/withdraw" $o @{ familyId = $f; accountId = $curId; amount = 500000 } 400
Assert "withdraw-block-msg" ($w2.msg -match "1,800") ("msg=" + $w2.msg)
Check "deposit-no-income-required" POST "/saving/deposit" $o @{ familyId = $f; accountId = $curId; amount = 200000 } | Out-Null
Assert "balance-380000" ((AccBal $o $f $curId) -eq 380000) ("bal=" + (AccBal $o $f $curId))
Check "deposit-zero-rejected" POST "/saving/deposit" $o @{ familyId = $f; accountId = $curId; amount = 0 } 400 | Out-Null
Check "deposit-bad-date" POST "/saving/deposit" $o @{ familyId = $f; accountId = $curId; amount = 100; date = "2026/10/22" } 400 | Out-Null

# ---------------------------------------------------------------- preset read-only / custom disable
Write-Host "==== preset read-only / custom disable ===="
$pr = Check "preset-disable-forbidden" PUT ("/saving/account/" + $bondId) $o @{ status = 0 } 403
Assert "preset-disable-msg" ($pr.msg -match $wPreset) ("msg=" + $pr.msg)
Check "preset-rename-forbidden" PUT ("/saving/account/" + $bondId) $o @{ name = "BOND$ts" } 403 | Out-Null
Check "preset-color-forbidden" PUT ("/saving/account/" + $bondId) $o @{ color = "#000000" } 403 | Out-Null
Check "disable-custom" PUT ("/saving/account/" + $travelId) $o @{ status = 0 } | Out-Null
Check "deposit-to-disabled" POST "/saving/deposit" $o @{ familyId = $f; accountId = $travelId; amount = 1000 } 400 | Out-Null
$ov = Call GET ("/saving/overview?familyId=" + $f) $o
$travelVo = @($ov.data.accounts) | Where-Object { $_.id -eq $travelId } | Select-Object -First 1
Assert "disabled-still-listed" ($null -ne $travelVo -and [int]$travelVo.status -eq 0) ("status=" + $travelVo.status)
Assert "preset-all-enabled" ((@($ov.data.accounts | Where-Object { $_.isSystem -eq 1 -and $_.status -ne 1 })).Count -eq 0) "presets status=1"
Assert "preset-all-readonly" ((@($ov.data.accounts | Where-Object { $_.isSystem -eq 1 -and $_.deletable -ne $false })).Count -eq 0) "presets deletable=false"
Check "enable-custom" PUT ("/saving/account/" + $travelId) $o @{ status = 1 } | Out-Null

# ---------------------------------------------------------------- transfer
Write-Host "==== transfer ===="
Check "deposit-to-bond" POST "/saving/deposit" $o @{ familyId = $f; accountId = $bondId; amount = 100000 } | Out-Null
$totalBefore = Total $o $f
Check "transfer-same-account" POST "/saving/transfer" $o @{ familyId = $f; fromAccountId = $curId; toAccountId = $curId; amount = 1000 } 400 | Out-Null
$tr = Check "transfer" POST "/saving/transfer" $o @{ familyId = $f; fromAccountId = $bondId; toAccountId = $curId; amount = 60000; note = "move" }
Assert "transfer-two-ids" ($tr.data.outId -gt 0 -and $tr.data.inId -gt 0 -and $tr.data.linkId.Length -gt 10) ("linkId=" + $tr.data.linkId)
Assert "transfer-from-balance" ([long]$tr.data.fromBalance -eq 40000) ("from=" + $tr.data.fromBalance)
Assert "transfer-to-balance" ([long]$tr.data.toBalance -eq 440000) ("to=" + $tr.data.toBalance)
Assert "transfer-total-unchanged" ((Total $o $f) -eq $totalBefore) ("total=" + (Total $o $f))
$ov = Call GET ("/saving/overview?familyId=" + $f) $o
Assert "transfer-not-in-monthNet" ([long]$ov.data.monthNet -eq 480000) ("monthNet=" + $ov.data.monthNet)
Check "transfer-over-balance" POST "/saving/transfer" $o @{ familyId = $f; fromAccountId = $bondId; toAccountId = $curId; amount = 100000 } 400 | Out-Null

$up = Check "transfer-edit-amount" PUT ("/saving/record/" + $tr.data.outId) $o @{ amount = 70000 }
Assert "transfer-edit-synced-out" ((AccBal $o $f $bondId) -eq 30000) ("bond=" + (AccBal $o $f $bondId))
Assert "transfer-edit-synced-in" ((AccBal $o $f $curId) -eq 450000) ("cur=" + (AccBal $o $f $curId))
$inRec = Call GET ("/saving/record/" + $tr.data.inId) $o
Assert "transfer-partner-amount-synced" ([long]$inRec.data.amount -eq 70000) ("inAmount=" + $inRec.data.amount)
Check "transfer-edit-over-cap" PUT ("/saving/record/" + $tr.data.outId) $o @{ amount = 120000 } 400 | Out-Null
Check "transfer-edit-account-forbidden" PUT ("/saving/record/" + $tr.data.outId) $o @{ accountId = $curId } 400 | Out-Null

# filter by account
$recs = Call GET ("/saving/records?familyId=" + $f + "&accountId=" + $bondId) $o
Assert "records-filter-by-account" ([long]$recs.data.total -eq 2) ("total=" + $recs.data.total)
$ovb = Call GET ("/saving/overview?familyId=" + $f + "&accountId=" + $bondId) $o
Assert "overview-scoped-balance" ([long]$ovb.data.balance -eq 30000) ("balance=" + $ovb.data.balance)

Check "delete-transfer-one" DELETE ("/saving/record/" + $tr.data.outId) $o $null | Out-Null
Assert "delete-transfer-both" ((AccBal $o $f $bondId) -eq 100000 -and (AccBal $o $f $curId) -eq 380000) ("bond=" + (AccBal $o $f $bondId) + " cur=" + (AccBal $o $f $curId))
$recsLeft = Call GET ("/saving/records?familyId=" + $f + "&accountId=" + $bondId) $o
Assert "delete-transfer-both-rows" ([long]$recsLeft.data.total -eq 1) ("total=" + $recsLeft.data.total)

# ---------------------------------------------------------------- account delete rules
Write-Host "==== account delete ===="
Check "deposit-travel" POST "/saving/deposit" $o @{ familyId = $f; accountId = $travelId; amount = 50000 } | Out-Null
$del1 = Check "delete-account-nonzero" DELETE ("/saving/account/" + $travelId) $o $null 400
Assert "delete-account-nonzero-msg" ($del1.msg -match "500") ("msg=" + $del1.msg)
Check "withdraw-travel-all" POST "/saving/withdraw" $o @{ familyId = $f; accountId = $travelId; amount = 50000 } | Out-Null
Check "delete-account-zero" DELETE ("/saving/account/" + $travelId) $o $null | Out-Null
$accAfter = @((Call GET ("/saving/accounts?familyId=" + $f) $o).data)
Assert "deleted-account-hidden" ((@($accAfter | Where-Object { $_.id -eq $travelId })).Count -eq 0) ("count=" + $accAfter.Count)
$hist = Call GET ("/saving/records?familyId=" + $f + "&accountId=" + $travelId) $o
Assert "deleted-account-history-kept" ([long]$hist.data.total -eq 2) ("total=" + $hist.data.total)
Assert "deleted-account-name-kept" (@($hist.data.list)[0].accountName -eq "TRIP$ts") ("name=" + @($hist.data.list)[0].accountName)
# move bond balance to current so bond = 0 for later steps
Check "drain-bond" POST "/saving/transfer" $o @{ familyId = $f; fromAccountId = $bondId; toAccountId = $curId; amount = 100000 } | Out-Null
Assert "balance-before-adjust-480000" ((Total $o $f) -eq 480000) ("total=" + (Total $o $f))

# ---------------------------------------------------------------- adjust
Write-Host "==== adjust (balance calibration) ===="
$pv = Check "adjust-preview" GET ("/saving/adjust/preview?familyId=" + $f + "&accountId=" + $curId + "&actualBalance=482000") $o $null
Assert "adjust-preview-book" ([long]$pv.data.bookBalance -eq 480000) ("book=" + $pv.data.bookBalance)
Assert "adjust-preview-delta" ([long]$pv.data.delta -eq 2000 -and $pv.data.direction -eq "in") ("delta=" + $pv.data.delta + " dir=" + $pv.data.direction)
Assert "adjust-preview-no-confirm" ($pv.data.needsConfirm -eq $false) ("needsConfirm=" + $pv.data.needsConfirm)
$adj = Check "adjust-up" POST "/saving/adjust" $o @{ familyId = $f; accountId = $curId; actualBalance = 482000; note = "fund-gain" }
Assert "adjust-direction-in" ($adj.data.direction -eq "in" -and [long]$adj.data.amount -eq 2000) ("dir=" + $adj.data.direction + " amount=" + $adj.data.amount)
Assert "adjust-new-balance" ([long]$adj.data.balance -eq 482000) ("balance=" + $adj.data.balance)
$st = StatsOf $o $f $y $mo
Assert "adjust-not-in-stats" ([long]$st.data.income -eq 0 -and [long]$st.data.expense -eq 0) ("income=" + $st.data.income + " expense=" + $st.data.expense)
Check "adjust-zero-delta" POST "/saving/adjust" $o @{ familyId = $f; accountId = $curId; actualBalance = 482000 } 400 | Out-Null
Check "adjust-negative" POST "/saving/adjust" $o @{ familyId = $f; accountId = $curId; actualBalance = -1 } 400 | Out-Null
$adj2 = Check "adjust-24h-needs-confirm" POST "/saving/adjust" $o @{ familyId = $f; accountId = $curId; actualBalance = 483000 } 409
Assert "adjust-confirm-msg" ($adj2.msg -match "24") ("msg=" + $adj2.msg)
$adj3 = Check "adjust-confirmed" POST "/saving/adjust" $o @{ familyId = $f; accountId = $curId; actualBalance = 483000; confirmed = $true }
Assert "adjust-confirmed-balance" ([long]$adj3.data.balance -eq 483000) ("balance=" + $adj3.data.balance)
Check "adjust-amount-immutable" PUT ("/saving/record/" + $adj.data.id) $o @{ amount = 3000 } 400 | Out-Null
$adjRec = Call GET ("/saving/record/" + $adj.data.id) $o
Assert "adjust-source-flag" ($adjRec.data.source -eq "adjust") ("source=" + $adjRec.data.source)
$adjList = Call GET ("/saving/records?familyId=" + $f + "&source=adjust") $o
Assert "adjust-source-filter" ([long]$adjList.data.total -eq 2) ("total=" + $adjList.data.total)

# ---------------------------------------------------------------- pay from saving (combo)
Write-Host "==== pay from saving ===="
$tx = Check "expense-pay-from-saving" POST "/transaction" $o @{ familyId = $f; type = "expense"; amount = 100000; date = (Today); note = "sofa"; savingAccountId = $curId }
$txId = [long]$tx.data.id
Assert "combo-saving-balance" ((AccBal $o $f $curId) -eq 383000) ("bal=" + (AccBal $o $f $curId))
$st = StatsOf $o $f $y $mo
Assert "combo-expense-in-stats" ([long]$st.data.expense -eq 100000) ("expense=" + $st.data.expense)
$txg = Call GET ("/transaction/" + $txId) $o
Assert "combo-tx-marked" ($txg.data.savingLinked -eq $true -and $txg.data.savingDirection -eq "out") ("linked=" + $txg.data.savingLinked)
$comboRecId = [long]$txg.data.savingRecordId
Check "combo-pay-over-balance" POST "/transaction" $o @{ familyId = $f; type = "expense"; amount = 99999999; date = (Today); savingAccountId = $curId } 400 | Out-Null

Check "combo-edit-amount" PUT ("/transaction/" + $txId) $o @{ amount = 150000 } | Out-Null
Assert "combo-edit-synced" ((AccBal $o $f $curId) -eq 333000) ("bal=" + (AccBal $o $f $curId))
$r = Call GET ("/saving/record/" + $comboRecId) $o
Assert "combo-record-amount" ([long]$r.data.amount -eq 150000) ("amount=" + $r.data.amount)
Check "combo-edit-over-cap" PUT ("/transaction/" + $txId) $o @{ amount = 999999 } 400 | Out-Null
Check "combo-edit-account-forbidden" PUT ("/transaction/" + $txId) $o @{ savingAccountId = $bondId } 400 | Out-Null
Check "combo-edit-type-forbidden" PUT ("/transaction/" + $txId) $o @{ type = "income" } 400 | Out-Null
Check "combo-edit-date" PUT ("/transaction/" + $txId) $o @{ date = (Yesterday) } | Out-Null
$r = Call GET ("/saving/record/" + $comboRecId) $o
Assert "combo-date-synced" ($r.data.date -eq (Yesterday)) ("date=" + $r.data.date)
$tgr = Call GET ("/transaction/" + $txId) $o
Assert "combo-tx-date" ($tgr.data.date -eq (Yesterday)) ("date=" + $tgr.data.date)
# saving record edit syncs the transaction
Check "combo-edit-from-saving-side" PUT ("/saving/record/" + $comboRecId) $o @{ amount = 120000 } | Out-Null
$tgr = Call GET ("/transaction/" + $txId) $o
Assert "combo-tx-amount-synced-back" ([long]$tgr.data.amount -eq 120000) ("amount=" + $tgr.data.amount)
Assert "combo-balance-after-edit" ((AccBal $o $f $curId) -eq 363000) ("bal=" + (AccBal $o $f $curId))
# delete transaction -> saving row gone, balance restored
Check "combo-delete-tx" DELETE ("/transaction/" + $txId) $o $null | Out-Null
Assert "combo-delete-restores-balance" ((AccBal $o $f $curId) -eq 483000) ("bal=" + (AccBal $o $f $curId))
$r = Call GET ("/saving/record/" + $comboRecId) $o
Assert "combo-delete-removes-record" ($r.code -eq 400) ("code=" + $r.code)
$st = StatsOf $o $f $y $mo
Assert "combo-delete-restores-stats" ([long]$st.data.expense -eq 0) ("expense=" + $st.data.expense)

# ---------------------------------------------------------------- income into saving (combo)
Write-Host "==== income into saving ===="
$itx = Check "income-into-saving" POST "/transaction" $o @{ familyId = $f; type = "income"; amount = 800000; date = (Today); note = "salary"; savingAccountId = $curId }
$itxId = [long]$itx.data.id
Assert "income-saving-balance" ((AccBal $o $f $curId) -eq 1283000) ("bal=" + (AccBal $o $f $curId))
$st = StatsOf $o $f $y $mo
Assert "income-in-stats" ([long]$st.data.income -eq 800000) ("income=" + $st.data.income)
$ig = Call GET ("/transaction/" + $itxId) $o
Assert "income-combo-marked" ($ig.data.savingLinked -eq $true -and $ig.data.savingDirection -eq "in") ("dir=" + $ig.data.savingDirection)
Check "income-edit-amount" PUT ("/transaction/" + $itxId) $o @{ amount = 900000 } | Out-Null
Assert "income-edit-synced" ((AccBal $o $f $curId) -eq 1383000) ("bal=" + (AccBal $o $f $curId))
Check "income-combo-delete" DELETE ("/transaction/" + $itxId) $o $null | Out-Null
Assert "income-delete-restores" ((AccBal $o $f $curId) -eq 483000) ("bal=" + (AccBal $o $f $curId))

# ---------------------------------------------------------------- member removal keeps combos
Write-Host "==== member removal ===="
$mtx = Check "member-pay-from-saving" POST "/transaction" $m2 @{ familyId = $f; type = "expense"; amount = 20000; date = (Today); note = "m2-combo"; savingAccountId = $curId }
$mtxId = [long]$mtx.data.id
Check "member-plain-expense" POST "/transaction" $m2 @{ familyId = $f; type = "expense"; amount = 30000; date = (Today); note = "m2-plain" } | Out-Null
Assert "balance-before-remove" ((AccBal $o $f $curId) -eq 463000) ("bal=" + (AccBal $o $f $curId))
$members = @((Call GET ("/family/" + $f + "/members") $o).data)
$m2MemberId = [long](@($members | Where-Object { $_.openid -eq $m2d.openid } | Select-Object -First 1).memberId)
Check "remove-m2" DELETE ("/family/" + $f + "/member/" + $m2MemberId) $o $null | Out-Null
Assert "balance-kept-after-remove" ((AccBal $o $f $curId) -eq 463000) ("bal=" + (AccBal $o $f $curId))
$st = StatsOf $o $f $y $mo
Assert "plain-tx-voided" ([long]$st.data.expense -eq 20000) ("expense=" + $st.data.expense)
$comboStill = Call GET ("/transaction/" + $mtxId) $o
Assert "combo-tx-kept" ($comboStill.code -eq 0 -and [long]$comboStill.data.amount -eq 20000) ("code=" + $comboStill.code)
$comboRec = Call GET ("/saving/record/" + $comboStill.data.savingRecordId) $o
Assert "combo-record-operator-snapshot" ($comboRec.code -eq 0 -and $comboRec.data.operatorName -eq "M2") ("operator=" + $comboRec.data.operatorName)

# ---------------------------------------------------------------- custom account limit
Write-Host "==== custom account limit ===="
$created = 0
for ($i = 1; $i -le 20; $i++) {
    $r = Call POST "/saving/account" $o @{ familyId = $f; name = "C$i-$ts" }
    if ($r -and $r.code -eq 0) { $created++ }
}
Assert "custom-created-20" ($created -eq 20) ("created=" + $created)
$limit = Check "custom-limit-reached" POST "/saving/account" $o @{ familyId = $f; name = "OVER-$ts" } 400
Assert "custom-limit-msg" ($limit.msg -match "20") ("msg=" + $limit.msg)

# ---------------------------------------------------------------- dissolve cleanup
Write-Host "==== dissolve ===="
Check "dissolve" POST ("/family/" + $f + "/dissolve") $o $null | Out-Null
Check "overview-after-dissolve" GET ("/saving/overview?familyId=" + $f) $o $null 403 | Out-Null

Write-Host "==== summary ===="
Write-Host ("PASS=" + $script:pass + " FAIL=" + $script:fail)
if ($proc) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
