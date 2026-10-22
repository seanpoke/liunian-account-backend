package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.SavingAccountService;
import com.liunian.account.service.SavingService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 储蓄模块接口（第二本账）。全部需要登录，且要求调用者是该家庭成员。
 *
 * <p>「从储蓄支付」「到账即存」不在这里——它们在记账接口
 * {@code POST /transaction} 里通过 {@code savingAccountId} 触发，保证一次写入成对记录。</p>
 */
@RestController
public class SavingController {

    private final SavingService savingService;
    private final SavingAccountService accountService;

    public SavingController(SavingService savingService, SavingAccountService accountService) {
        this.savingService = savingService;
        this.accountService = accountService;
    }

    /** 存入 / 取出。 */
    public record AmountReq(Long familyId, Long accountId, Integer amount, String date, String note) {
    }

    /** 更新余额：只传当前实际余额，方向与金额由服务端判定。 */
    public record AdjustReq(Long familyId, Long accountId, Integer actualBalance, String date,
                            String note, Boolean confirmed) {
    }

    /** 账户转账。 */
    public record TransferReq(Long familyId, Long fromAccountId, Long toAccountId, Integer amount,
                              String date, String note) {
    }

    /** 新建账户（仅 owner）。 */
    public record AccountCreateReq(Long familyId, String name, String icon, String color, Integer sort) {
    }

    /** 改名 / 改图标 / 改配色 / 调排序 / 停用启用（仅 owner）。 */
    public record AccountPatchReq(String name, String icon, String color, Integer sort, Integer status) {
    }

    /** 编辑流水（方向不可改）。 */
    public record RecordPatchReq(Integer amount, String date, String note, Long accountId) {
    }

    private LocalDate parse(String s) {
        return s == null ? null : LocalDate.parse(s);
    }

    // ------------------------------------------------------------------ 汇总 / 账户

    @GetMapping("/saving/overview")
    public R<Map<String, Object>> overview(@RequestParam Long familyId,
                                           @RequestParam(required = false) Long accountId) {
        return R.ok(savingService.overview(UserContext.get(), familyId, accountId));
    }

    @GetMapping("/saving/accounts")
    public R<List<Map<String, Object>>> accounts(@RequestParam Long familyId) {
        return R.ok(accountService.list(UserContext.get(), familyId));
    }

    @PostMapping("/saving/account")
    public R<Map<String, Object>> createAccount(@RequestBody AccountCreateReq req) {
        return R.ok(accountService.create(UserContext.get(), req.familyId(), req.name(), req.icon(),
                req.color(), req.sort()));
    }

    @PutMapping("/saving/account/{id}")
    public R<Map<String, Object>> updateAccount(@PathVariable Long id, @RequestBody AccountPatchReq req) {
        return R.ok(accountService.update(UserContext.get(), id, req.name(), req.icon(), req.color(),
                req.sort(), req.status()));
    }

    @DeleteMapping("/saving/account/{id}")
    public R<Void> deleteAccount(@PathVariable Long id) {
        accountService.delete(UserContext.get(), id);
        return R.ok();
    }

    // ------------------------------------------------------------------ 流水

    @GetMapping("/saving/records")
    public R<Map<String, Object>> records(@RequestParam Long familyId,
                                          @RequestParam(required = false) Long accountId,
                                          @RequestParam(required = false) String source,
                                          @RequestParam(required = false) String start,
                                          @RequestParam(required = false) String end,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return R.ok(savingService.list(UserContext.get(), familyId, accountId, source,
                parse(start), parse(end), page, size));
    }

    @GetMapping("/saving/record/{id}")
    public R<Map<String, Object>> record(@PathVariable Long id) {
        return R.ok(savingService.get(UserContext.get(), id));
    }

    @PostMapping("/saving/deposit")
    public R<Map<String, Object>> deposit(@RequestBody AmountReq req) {
        return R.ok(savingService.deposit(UserContext.get(), req.familyId(), req.accountId(),
                req.amount(), parse(req.date()), req.note()));
    }

    @PostMapping("/saving/withdraw")
    public R<Map<String, Object>> withdraw(@RequestBody AmountReq req) {
        return R.ok(savingService.withdraw(UserContext.get(), req.familyId(), req.accountId(),
                req.amount(), parse(req.date()), req.note()));
    }

    @GetMapping("/saving/adjust/preview")
    public R<Map<String, Object>> adjustPreview(@RequestParam Long familyId,
                                                @RequestParam Long accountId,
                                                @RequestParam(required = false) Integer actualBalance) {
        return R.ok(savingService.adjustPreview(UserContext.get(), familyId, accountId, actualBalance));
    }

    @PostMapping("/saving/adjust")
    public R<Map<String, Object>> adjust(@RequestBody AdjustReq req) {
        return R.ok(savingService.adjust(UserContext.get(), req.familyId(), req.accountId(),
                req.actualBalance(), parse(req.date()), req.note(), req.confirmed()));
    }

    @PostMapping("/saving/transfer")
    public R<Map<String, Object>> transfer(@RequestBody TransferReq req) {
        return R.ok(savingService.transfer(UserContext.get(), req.familyId(), req.fromAccountId(),
                req.toAccountId(), req.amount(), parse(req.date()), req.note()));
    }

    @PutMapping("/saving/record/{id}")
    public R<Map<String, Object>> updateRecord(@PathVariable Long id, @RequestBody RecordPatchReq req) {
        return R.ok(savingService.update(UserContext.get(), id, req.amount(), parse(req.date()),
                req.note(), req.accountId()));
    }

    @DeleteMapping("/saving/record/{id}")
    public R<Void> deleteRecord(@PathVariable Long id) {
        savingService.delete(UserContext.get(), id);
        return R.ok();
    }
}
