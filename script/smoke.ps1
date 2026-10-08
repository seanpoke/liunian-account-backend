# 流年小账后端一键冒烟（依赖 WX_MOCK=true，本地 8445）
$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:8445/api"

function Call($method, $path, $token, $body) {
    $hdrs = @{}
    if ($token) { $hdrs["Authorization"] = "Bearer $token" }
    $hdrs["Content-Type"] = "application/json"
    $json = if ($body) { $body | ConvertTo-Json -Compress } else { $null }
    try {
        $r = Invoke-RestMethod -Method $method -Uri ($base + $path) -Headers $hdrs -Body $json
        return $r
    } catch {
        $msg = $_.ErrorDetails.Message
        if (-not $msg) { $msg = $_.Exception.Message }
        Write-Host "  [FAIL] $method $path -> $msg" -ForegroundColor Red
        return $null
    }
}

function Token($code) {
    $r = Call POST "/login" $null @{ code = $code }
    if ($r -and $r.data) { return $r.data.token }
    return $null
}

Write-Host "== 流年小账冒烟 start ==" -ForegroundColor Cyan

# 每次用带时间戳的唯一 openid，避免单家庭约束导致重复执行时建家庭被拒
$ts = (Get-Date).ToString("yyyyMMddHHmmssfff")
$ownerCode = ("mock:owner_" + $ts)
$memberCode = ("mock:member_" + $ts)

# 1. 创建者登录 + 建家庭
$owner = Token $ownerCode
Write-Host "owner token: $($owner.Substring(0,12))..." -ForegroundColor DarkGray
$f = Call POST "/family" $owner @{ name = "流年小账" }
$familyId = $f.data.familyId
Write-Host "family created: $familyId" -ForegroundColor Green

# 2. 查家庭
Call GET "/family/$familyId" $owner | Out-Null

# 3. 创建者生成邀请
$inv = Call POST "/invitation" $owner @{ familyId = $familyId; type = "share" }
$token = $inv.data.token
Write-Host "invite token: $token" -ForegroundColor Green

# 4. 校验邀请（公开，无账目）
Call GET "/invitation/$token" $null | Out-Null

# 5. 成员登录 + 申请
$member = Token $memberCode
$apply = Call POST "/invitation/$token/apply" $member @{ nickname = "小明"; avatar = "http://x/a.png" }
Write-Host "apply status: $($apply.data.status)" -ForegroundColor Green

# 6. 创建者看待审批 + 同意
$pending = Call GET "/family/$familyId/pending" $owner
$memberId = $pending.data[0].memberId
Call POST "/family/$familyId/member/$memberId/approve" $owner | Out-Null
Write-Host "approved member: $memberId" -ForegroundColor Green

# 7. 成员记账（categoryId 现为主键自增 Long，先取一个有效分类 id）
$cats = Call GET "/categories?familyId=$familyId&type=expense" $member
$catId = $cats.data[0].id
$tx = Call POST "/transaction" $member @{ type = "expense"; amount = 3500; categoryId = $catId; date = (Get-Date -Format "yyyy-MM-dd"); note = "午饭" }
Write-Host "transaction: $($tx.data.id) (categoryId=$catId)" -ForegroundColor Green

# 8. 成员查明细 + 统计 + 分类 + 预算
Call GET "/transactions" $member | Out-Null
Call GET "/stats?period=month" $member | Out-Null
Call GET "/categories?familyId=$familyId&type=expense" $member | Out-Null
Call PUT "/budget" $owner @{ familyId = $familyId; amount = 300000 } | Out-Null
Call GET "/budget?familyId=$familyId" $owner | Out-Null

# 9. 隐私授权
Call POST "/user/privacy" $owner @{ agreed = $true } | Out-Null

Write-Host "== 冒烟完成（FAIL 行已标红，无 FAIL 即通过）==" -ForegroundColor Cyan
