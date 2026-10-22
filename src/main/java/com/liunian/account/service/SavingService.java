package com.liunian.account.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.common.Money;
import com.liunian.account.common.TimeSupport;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.SavingAccount;
import com.liunian.account.entity.SavingRecord;
import com.liunian.account.entity.TransactionRecord;
import com.liunian.account.mapper.SavingRecordMapper;
import com.liunian.account.mapper.TransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 储蓄模块（第二本账）。核心约定见 doc/储蓄功能设计.md：
 *
 * <ul>
 *   <li>储蓄流水是唯一资金事实来源，余额永远派生，不落库；</li>
 *   <li>存入/取出/转账/更新余额<b>不产生任何收入或支出</b>；</li>
 *   <li>只有记账页的两个开关（从储蓄支付 / 到账即存）会同时写账单与储蓄流水，
 *       通过 {@code saving_record.linked_transaction_id} 绑定，必须成对增删改；</li>
 *   <li>组合记录（转账两条 / 账单配对一条）必须成对删除、成对修改日期与金额。</li>
 * </ul>
 *
 * <p><b>并发安全</b>：余额是派生值，写操作统一先
 * {@code SELECT ... FOR UPDATE} 锁住 saving_account 行，再在锁内 SUM 出余额做校验，
 * 等价于「余额原子扣减」，避免「先查后写」被两笔并发同时穿透。</p>
 */
@Service
public class SavingService {

    private static final int MAX_NOTE_LEN = 200;
    private static final int MAX_NAME_LEN = 32;

    private final SavingRecordMapper recordMapper;
    private final TransactionMapper transactionMapper;
    private final SavingAccountService accountService;

    public SavingService(SavingRecordMapper recordMapper, TransactionMapper transactionMapper,
                         SavingAccountService accountService) {
        this.recordMapper = recordMapper;
        this.transactionMapper = transactionMapper;
        this.accountService = accountService;
    }

    // ================================================================== 汇总 / 查询

    /**
     * 储蓄汇总：余额 + 本月存入/取出 + 账户列表。
     * accountId 为空表示「全部」，非空表示单账户视角（余额与本月存取都按该账户）。
     */
    public Map<String, Object> overview(String openid, Long familyId, Long accountId) {
        accountService.requireMember(openid, familyId);
        List<Map<String, Object>> accounts = accountService.buildVos(familyId);

        long total = 0L;
        for (Map<String, Object> a : accounts) {
            total += ((Number) a.get("balance")).longValue();
        }
        long scopeBalance = total;
        String scopeName = "";
        if (accountId != null) {
            Map<String, Object> hit = null;
            for (Map<String, Object> a : accounts) {
                if (((Number) a.get("id")).longValue() == accountId) {
                    hit = a;
                    break;
                }
            }
            if (hit == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "储蓄账户不存在");
            }
            scopeBalance = ((Number) hit.get("balance")).longValue();
            scopeName = String.valueOf(hit.get("name"));
        }

        LocalDate today = TimeSupport.today();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        Map<String, Object> ps = recordMapper.periodStats(familyId, accountId, monthStart, monthEnd);
        long monthIn = SavingAccountService.num(ps, "inSum");
        long monthOut = SavingAccountService.num(ps, "outSum");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", accountId == null ? "" : accountId);
        m.put("accountName", scopeName);
        m.put("balance", scopeBalance);
        m.put("month", today.toString().substring(0, 7));
        m.put("monthIn", monthIn);
        m.put("monthOut", monthOut);
        m.put("monthNet", monthIn - monthOut);
        m.put("accountCount", accounts.size());
        m.put("accounts", accounts);
        return m;
    }

    /** 流水分页（按 date desc, id desc）。 */
    public Map<String, Object> list(String openid, Long familyId, Long accountId, String source,
                                    LocalDate start, LocalDate end, long page, long size) {
        accountService.requireMember(openid, familyId);
        var q = Wrappers.<SavingRecord>lambdaQuery().eq(SavingRecord::getFamilyId, familyId);
        if (accountId != null) {
            q.eq(SavingRecord::getAccountId, accountId);
        }
        if (source != null && !source.isBlank()) {
            q.eq(SavingRecord::getSource, source);
        }
        if (start != null) {
            q.ge(SavingRecord::getDate, start);
        }
        if (end != null) {
            q.le(SavingRecord::getDate, end);
        }
        q.orderByDesc(SavingRecord::getDate).orderByDesc(SavingRecord::getId);

        IPage<SavingRecord> p = recordMapper.selectPage(new Page<>(page, size), q);
        List<Long> txIds = p.getRecords().stream()
                .map(SavingRecord::getLinkedTransactionId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Integer> txStatus = txStatusMap(txIds);
        Map<Long, SavingAccount> accounts = accountService.rawMap(familyId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (SavingRecord r : p.getRecords()) {
            list.add(toVo(r, accounts, isReadonly(r, txStatus)));
        }
        return Map.of("list", list, "total", p.getTotal());
    }

    public Map<String, Object> get(String openid, Long id) {
        SavingRecord r = requireRecord(id);
        accountService.requireMember(openid, r.getFamilyId());
        Map<Long, Integer> txStatus = txStatusMap(r.getLinkedTransactionId() == null
                ? List.of() : List.of(r.getLinkedTransactionId()));
        return toVo(r, accountService.rawMap(r.getFamilyId()), isReadonly(r, txStatus));
    }

    // ================================================================== 存入 / 取出

    @Transactional
    public Map<String, Object> deposit(String openid, Long familyId, Long accountId, Integer amount,
                                       LocalDate date, String note) {
        FamilyMember m = accountService.requireMember(openid, familyId);
        int amt = checkAmount(amount);
        SavingAccount a = accountService.lockActiveAccount(familyId, accountId);
        SavingRecord r = insertRecord(familyId, a.getId(), SavingRecord.DIR_IN, SavingRecord.SRC_NORMAL,
                amt, date, normalizeNote(note), m, null, null);
        return savedVo(r, a, accountService.balanceOf(familyId, a.getId()));
    }

    /** 取出：不产生收入或支出；超过该账户余额硬阻断。 */
    @Transactional
    public Map<String, Object> withdraw(String openid, Long familyId, Long accountId, Integer amount,
                                        LocalDate date, String note) {
        FamilyMember m = accountService.requireMember(openid, familyId);
        int amt = checkAmount(amount);
        SavingAccount a = accountService.lockActiveAccount(familyId, accountId);
        long balance = accountService.balanceOf(familyId, a.getId());
        if (amt > balance) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该账户最多可取出 " + Money.yuan(balance));
        }
        SavingRecord r = insertRecord(familyId, a.getId(), SavingRecord.DIR_OUT, SavingRecord.SRC_NORMAL,
                amt, date, normalizeNote(note), m, null, null);
        return savedVo(r, a, balance - amt);
    }

    // ================================================================== 更新余额（余额校准）

    /** 更新余额预览：账面余额 + δ 与方向 + 上次校准信息（24h 内二次校准需确认）。 */
    public Map<String, Object> adjustPreview(String openid, Long familyId, Long accountId, Integer actualBalance) {
        accountService.requireMember(openid, familyId);
        if (accountId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "accountId 必填");
        }
        SavingAccount a = accountService.rawMap(familyId).get(accountId);
        if (a == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "储蓄账户不存在");
        }
        long book = accountService.balanceOf(familyId, accountId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", accountId);
        m.put("accountName", a.getName());
        m.put("bookBalance", book);
        m.put("actualBalance", actualBalance == null ? "" : actualBalance);
        if (actualBalance != null) {
            long delta = actualBalance - book;
            m.put("delta", delta);
            m.put("direction", delta > 0 ? SavingRecord.DIR_IN : delta < 0 ? SavingRecord.DIR_OUT : "");
            m.put("changeAmount", Math.abs(delta));
        }
        SavingRecord last = lastAdjust(familyId, accountId);
        m.put("needsConfirm", last != null && within24h(last));
        if (last != null) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("id", last.getId());
            l.put("at", last.getCreatedAt() == null ? "" : last.getCreatedAt().toString());
            l.put("amount", last.getAmount());
            l.put("direction", last.getDirection());
            l.put("delta", SavingRecord.DIR_IN.equals(last.getDirection()) ? last.getAmount() : -last.getAmount());
            m.put("lastAdjust", l);
        }
        return m;
    }

    /**
     * 更新余额：只接收「当前实际余额」，差额方向由服务端判定，
     * 生成一条 {@code source=adjust} 的存入/取出流水——不产生收入或支出，累计结余不变。
     */
    @Transactional
    public Map<String, Object> adjust(String openid, Long familyId, Long accountId, Integer actualBalance,
                                      LocalDate date, String note, Boolean confirmed) {
        FamilyMember m = accountService.requireMember(openid, familyId);
        if (accountId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "accountId 必填");
        }
        if (actualBalance == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前实际余额必填");
        }
        if (actualBalance < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前实际余额不能为负数");
        }
        if (actualBalance > Money.MAX_AMOUNT) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单笔金额不能超过 " + Money.yuan(Money.MAX_AMOUNT));
        }
        SavingAccount a = accountService.lockActiveAccount(familyId, accountId);
        long book = accountService.balanceOf(familyId, a.getId());
        long delta = (long) actualBalance - book;
        if (delta == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "余额没有变化，无需更新");
        }
        // 24h 内二次校准需二次确认（δ=0 优先按「没有变化」拒绝，语义更清楚）
        SavingRecord last = lastAdjust(familyId, accountId);
        if (last != null && within24h(last) && !Boolean.TRUE.equals(confirmed)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该账户 24 小时内已校准过（上次 " + last.getCreatedAt() + "，差额 "
                            + Money.yuan(signedDelta(last)) + "），请确认后再提交");
        }
        String direction = delta > 0 ? SavingRecord.DIR_IN : SavingRecord.DIR_OUT;
        SavingRecord r = insertRecord(familyId, a.getId(), direction, SavingRecord.SRC_ADJUST,
                (int) Math.abs(delta), date, normalizeNote(note), m, null, null);
        Map<String, Object> m2 = savedVo(r, a, book + delta);
        m2.put("bookBalance", book);
        m2.put("delta", delta);
        return m2;
    }

    // ================================================================== 账户转账

    /** 账户间转账：写两条方向相反的流水、共享 link_id；储蓄总额与本月净增均不变。 */
    @Transactional
    public Map<String, Object> transfer(String openid, Long familyId, Long fromAccountId, Long toAccountId,
                                        Integer amount, LocalDate date, String note) {
        FamilyMember m = accountService.requireMember(openid, familyId);
        int amt = checkAmount(amount);
        if (fromAccountId == null || toAccountId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "转出/转入账户必填");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "转出与转入账户不能相同");
        }
        // 按 id 升序加锁，避免两笔反向转账互等死锁
        long low = Math.min(fromAccountId, toAccountId);
        long high = Math.max(fromAccountId, toAccountId);
        SavingAccount lowAcc = accountService.lockActiveAccount(familyId, low);
        SavingAccount highAcc = accountService.lockActiveAccount(familyId, high);
        SavingAccount from = fromAccountId == low ? lowAcc : highAcc;
        SavingAccount to = toAccountId == low ? lowAcc : highAcc;

        long fromBalance = accountService.balanceOf(familyId, from.getId());
        if (amt > fromBalance) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该账户最多可转出 " + Money.yuan(fromBalance));
        }
        long toBalance = accountService.balanceOf(familyId, to.getId());
        String linkId = UUID.randomUUID().toString().replace("-", "");
        LocalDate d = date == null ? TimeSupport.today() : date;
        String n = normalizeNote(note);
        SavingRecord out = insertRecord(familyId, from.getId(), SavingRecord.DIR_TRANSFER_OUT,
                SavingRecord.SRC_NORMAL, amt, d, n, m, linkId, null);
        SavingRecord in = insertRecord(familyId, to.getId(), SavingRecord.DIR_TRANSFER_IN,
                SavingRecord.SRC_NORMAL, amt, d, n, m, linkId, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("linkId", linkId);
        result.put("outId", out.getId());
        result.put("inId", in.getId());
        result.put("fromAccountId", from.getId());
        result.put("toAccountId", to.getId());
        result.put("amount", amt);
        result.put("fromBalance", fromBalance - amt);
        result.put("toBalance", toBalance + amt);
        // 转账零和：储蓄余额总额不变，不计入「本月净增」
        result.put("totalBalance", overviewTotal(familyId));
        return result;
    }

    private long overviewTotal(Long familyId) {
        long total = 0L;
        Map<Long, long[]> stats = accountService.statsMap(familyId);
        for (long[] s : stats.values()) {
            total += s[0] - s[1];
        }
        return total;
    }

    // ================================================================== 编辑 / 删除流水

    /**
     * 编辑储蓄流水。规则（对应设计文档 6.2/8.2/8.6）：
     * <ul>
     *   <li>普通存取：可改金额/日期/备注/账户；方向不可改；取出受账户余额非负校验；</li>
     *   <li>余额调整（adjust）：金额与账户不可改，只能改日期/备注；</li>
     *   <li>账户转账：金额与日期两条同步，账户不可改，方向不可改；</li>
     *   <li>组合记录（从储蓄支付 / 到账即存）：金额与日期两侧同步，账户不可改，
     *       关联账单已作废则只读。</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> update(String openid, Long id, Integer amount, LocalDate date,
                                     String note, Long accountId) {
        SavingRecord r = requireRecord(id);
        FamilyMember m = accountService.requireMember(openid, r.getFamilyId());
        requireEditable(m, r);
        Long familyId = r.getFamilyId();
        boolean transfer = r.getLinkId() != null && !r.getLinkId().isBlank();

        if (r.getLinkedTransactionId() != null) {
            TransactionRecord tx = transactionMapper.selectById(r.getLinkedTransactionId());
            if (tx == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "该储蓄流水关联的账目已不存在，请删除后重新记录");
            }
            if (tx.getStatus() != null && tx.getStatus() == TransactionRecord.STATUS_VOID) {
                throw new BizException(ErrorCode.BAD_REQUEST, "关联账目已作废，该储蓄流水不可修改");
            }
            if (accountId != null && !accountId.equals(r.getAccountId())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "组合记录不可更换储蓄账户，如需更换请删除后重新记录");
            }
            if (amount != null && !amount.equals(r.getAmount())) {
                int amt = checkAmount(amount);
                if (SavingRecord.DIR_OUT.equals(r.getDirection())) {
                    accountService.lockAccount(familyId, r.getAccountId());
                    long balance = accountService.balanceOf(familyId, r.getAccountId());
                    long cap = balance + r.getAmount();
                    if (amt > cap) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "最多可改为 " + Money.yuan(cap));
                    }
                }
                r.setAmount(amt);
                tx.setAmount(amt);
            }
            if (date != null && !date.equals(r.getDate())) {
                r.setDate(date);
                tx.setDate(date);
            }
            tx.setUpdatedAt(TimeSupport.now());
            transactionMapper.updateById(tx);
            if (note != null) {
                r.setNote(normalizeNote(note));
            }
        } else if (transfer) {
            if (accountId != null && !accountId.equals(r.getAccountId())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "转账记录不可更换账户，请删除后重新转账");
            }
            List<SavingRecord> pair = recordMapper.listByLinkId(r.getLinkId());
            SavingRecord partner = pair.stream().filter(x -> !x.getId().equals(r.getId())).findFirst().orElse(null);
            if (partner == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "转账记录不完整（配对记录不存在），请删除后重新转账");
            }
            int newAmount = r.getAmount();
            if (amount != null && !amount.equals(r.getAmount())) {
                newAmount = checkAmount(amount);
                if (SavingRecord.DIR_TRANSFER_OUT.equals(r.getDirection())) {
                    accountService.lockAccount(familyId, r.getAccountId());
                    long balance = accountService.balanceOf(familyId, r.getAccountId());
                    long cap = balance + r.getAmount();
                    if (newAmount > cap) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "最多可改为 " + Money.yuan(cap));
                    }
                }
            }
            LocalDate newDate = date != null ? date : r.getDate();
            r.setAmount(newAmount);
            r.setDate(newDate);
            partner.setAmount(newAmount);
            partner.setDate(newDate);
            partner.setUpdatedAt(TimeSupport.now());
            recordMapper.updateById(partner);
            if (note != null) {
                r.setNote(normalizeNote(note));
            }
        } else if (SavingRecord.SRC_ADJUST.equals(r.getSource())) {
            if (amount != null && !amount.equals(r.getAmount())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "余额调整的金额不可修改，请删除后重新校准");
            }
            if (accountId != null && !accountId.equals(r.getAccountId())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "余额调整记录不可更换账户");
            }
            if (date != null) {
                r.setDate(date);
            }
            if (note != null) {
                r.setNote(normalizeNote(note));
            }
        } else {
            int targetAmount = amount != null ? checkAmount(amount) : r.getAmount();
            boolean moving = accountId != null && !accountId.equals(r.getAccountId());
            if (moving) {
                SavingAccount target = accountService.lockActiveAccount(familyId, accountId);
                if (SavingRecord.DIR_OUT.equals(r.getDirection())) {
                    long balance = accountService.balanceOf(familyId, target.getId());
                    if (targetAmount > balance) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "该账户最多可取出 " + Money.yuan(balance));
                    }
                }
                r.setAccountId(target.getId());
            } else if (amount != null && !amount.equals(r.getAmount())
                    && SavingRecord.DIR_OUT.equals(r.getDirection())) {
                accountService.lockAccount(familyId, r.getAccountId());
                long balance = accountService.balanceOf(familyId, r.getAccountId());
                long cap = balance + r.getAmount();
                if (targetAmount > cap) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "该账户最多可取出 " + Money.yuan(cap));
                }
            }
            r.setAmount(targetAmount);
            if (date != null) {
                r.setDate(date);
            }
            if (note != null) {
                r.setNote(normalizeNote(note));
            }
        }

        r.setUpdatedAt(TimeSupport.now());
        recordMapper.updateById(r);
        SavingAccount a = accountService.rawMap(familyId).get(r.getAccountId());
        long balance = accountService.balanceOf(familyId, r.getAccountId());
        Map<String, Object> vo = toVo(r, accountService.rawMap(familyId), false);
        vo.put("balance", balance);
        if (a != null) {
            vo.put("accountName", a.getName());
        }
        return vo;
    }

    /**
     * 删除储蓄流水：组合记录成对删除。
     * 账单侧已作废时拒绝删除（否则余额会回退、账实不符）。
     */
    @Transactional
    public void delete(String openid, Long id) {
        SavingRecord r = requireRecord(id);
        FamilyMember m = accountService.requireMember(openid, r.getFamilyId());
        requireEditable(m, r);
        if (r.getLinkedTransactionId() != null) {
            TransactionRecord tx = transactionMapper.selectById(r.getLinkedTransactionId());
            if (tx != null) {
                if (tx.getStatus() != null && tx.getStatus() == TransactionRecord.STATUS_VOID) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "关联账目已作废，该储蓄流水不可删除");
                }
                transactionMapper.deleteById(tx.getId());
            }
            recordMapper.deleteById(r.getId());
            return;
        }
        if (r.getLinkId() != null && !r.getLinkId().isBlank()) {
            recordMapper.delete(Wrappers.<SavingRecord>lambdaQuery().eq(SavingRecord::getLinkId, r.getLinkId()));
            return;
        }
        recordMapper.deleteById(r.getId());
    }

    // ================================================================== 与账单的跨表联动

    /**
     * 记账页「从储蓄支付」/「到账即存」：账单写入后追加配对的储蓄流水。
     * 支出 → 取出（余额硬阻断）；收入 → 存入（不做任何余额校验）。
     */
    public void createLinked(FamilyMember member, TransactionRecord tx, Long accountId) {
        boolean expense = "expense".equals(tx.getType());
        SavingAccount a = accountService.lockActiveAccount(tx.getFamilyId(), accountId);
        long balance = accountService.balanceOf(tx.getFamilyId(), a.getId());
        if (expense && tx.getAmount() != null && tx.getAmount() > balance) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该账户最多可支付 " + Money.yuan(balance));
        }
        insertRecord(tx.getFamilyId(), a.getId(),
                expense ? SavingRecord.DIR_OUT : SavingRecord.DIR_IN, SavingRecord.SRC_NORMAL,
                tx.getAmount(), tx.getDate(), tx.getNote(), member, null, tx.getId());
    }

    /**
     * 编辑账单时同步配对的储蓄流水：
     * 金额同步（支出的上限 = 该账户当前余额 + 原取出金额）、日期两侧强制一致、账户不可改。
     * 若账单没有配对流水但请求里带了 savingAccountId → 拒绝（不能事后追加联动）。
     */
    public void syncLinked(TransactionRecord tx, Integer newAmount, LocalDate newDate, Long accountId) {
        SavingRecord r = findLinked(tx.getId());
        if (r == null) {
            if (accountId != null) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "该支出关联的储蓄取出已不存在，请删除后重新记录");
            }
            return;
        }
        if (accountId != null && !accountId.equals(r.getAccountId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "组合记录不可更换储蓄账户，如需更换请删除后重新记录");
        }
        boolean changed = false;
        if (newAmount != null && !newAmount.equals(r.getAmount())) {
            if ("expense".equals(tx.getType())) {
                accountService.lockAccount(tx.getFamilyId(), r.getAccountId());
                long balance = accountService.balanceOf(tx.getFamilyId(), r.getAccountId());
                long cap = balance + r.getAmount();
                if (newAmount > cap) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "最多可改为 " + Money.yuan(cap));
                }
            }
            r.setAmount(newAmount);
            changed = true;
        }
        if (newDate != null && !newDate.equals(r.getDate())) {
            r.setDate(newDate);
            changed = true;
        }
        if (changed) {
            r.setUpdatedAt(TimeSupport.now());
            recordMapper.updateById(r);
        }
    }

    /** 删除账单时连同删除配对的储蓄流水；账单已作废时其配对流水必须保留。 */
    public void deleteLinkedForTransaction(TransactionRecord tx) {
        SavingRecord r = findLinked(tx.getId());
        if (r == null) {
            return;
        }
        if (tx.getStatus() != null && tx.getStatus() == TransactionRecord.STATUS_VOID) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该账目已作废，其关联的储蓄流水需保留，不可删除");
        }
        recordMapper.deleteById(r.getId());
    }

    /** 该账单是否已有配对的储蓄流水（组合记录）。 */
    public boolean hasLinked(Long transactionId) {
        return findLinked(transactionId) != null;
    }

    /** 账单是否属于某个组合记录（反查 saving_record.linked_transaction_id）。 */
    public SavingRecord findLinked(Long transactionId) {
        if (transactionId == null) {
            return null;
        }
        List<SavingRecord> list = recordMapper.selectList(Wrappers.<SavingRecord>lambdaQuery()
                .eq(SavingRecord::getLinkedTransactionId, transactionId)
                .orderByAsc(SavingRecord::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 批量反查账单对应的储蓄流水（明细页「储蓄」小标 / 只读判定）。 */
    public Map<Long, Map<String, Object>> linkedInfoByTxIds(Long familyId, Collection<Long> txIds) {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        if (txIds == null || txIds.isEmpty()) {
            return result;
        }
        List<Long> ids = txIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return result;
        }
        List<SavingRecord> rows = recordMapper.selectList(Wrappers.<SavingRecord>lambdaQuery()
                .in(SavingRecord::getLinkedTransactionId, ids));
        Map<Long, SavingAccount> accounts = accountService.rawMap(familyId);
        for (SavingRecord r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("savingRecordId", r.getId());
            m.put("savingAccountId", r.getAccountId());
            SavingAccount a = accounts.get(r.getAccountId());
            m.put("savingAccountName", a == null ? "" : a.getName());
            m.put("savingDirection", r.getDirection());
            m.put("savingAmount", r.getAmount());
            result.put(r.getLinkedTransactionId(), m);
        }
        return result;
    }

    // ================================================================== 内部工具

    private SavingRecord requireRecord(Long id) {
        SavingRecord r = recordMapper.selectById(id);
        if (r == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "储蓄流水不存在");
        }
        return r;
    }

    /** 本人或 owner 可编辑/删除（与收支记录一致）。 */
    private void requireEditable(FamilyMember m, SavingRecord r) {
        if (!"owner".equals(m.getRole()) && !m.getOpenid().equals(r.getOpenid())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权修改他人储蓄记录");
        }
    }

    private int checkAmount(Integer amount) {
        if (amount == null || amount <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "金额必须大于 0");
        }
        if (amount > Money.MAX_AMOUNT) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单笔金额不能超过 " + Money.yuan(Money.MAX_AMOUNT));
        }
        return amount;
    }

    private String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String n = note.trim();
        if (n.isEmpty()) {
            return null;
        }
        if (n.length() > MAX_NOTE_LEN) {
            throw new BizException(ErrorCode.BAD_REQUEST, "备注最多 " + MAX_NOTE_LEN + " 个字符");
        }
        return n;
    }

    private String safeName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        return n.length() > MAX_NAME_LEN ? n.substring(0, MAX_NAME_LEN) : n;
    }

    private SavingRecord insertRecord(Long familyId, Long accountId, String direction, String source,
                                      Integer amount, LocalDate date, String note, FamilyMember operator,
                                      String linkId, Long linkedTxId) {
        SavingRecord r = new SavingRecord();
        r.setFamilyId(familyId);
        r.setAccountId(accountId);
        r.setDirection(direction);
        r.setSource(source);
        r.setAmount(amount);
        r.setDate(date == null ? TimeSupport.today() : date);
        r.setNote(note);
        r.setOpenid(operator.getOpenid());
        r.setOperatorName(safeName(operator.getNickname()));
        r.setLinkId(linkId);
        r.setLinkedTransactionId(linkedTxId);
        r.setCreatedAt(TimeSupport.now());
        r.setUpdatedAt(TimeSupport.now());
        recordMapper.insert(r);
        return r;
    }

    private Map<String, Object> savedVo(SavingRecord r, SavingAccount a, long balance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("familyId", r.getFamilyId());
        m.put("accountId", r.getAccountId());
        m.put("accountName", a.getName());
        m.put("direction", r.getDirection());
        m.put("source", r.getSource());
        m.put("amount", r.getAmount());
        m.put("date", r.getDate() == null ? "" : r.getDate().toString());
        m.put("note", r.getNote() == null ? "" : r.getNote());
        m.put("balance", balance);
        return m;
    }

    private Map<String, Object> toVo(SavingRecord r, Map<Long, SavingAccount> accounts, boolean readonly) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("familyId", r.getFamilyId());
        m.put("accountId", r.getAccountId());
        SavingAccount a = accounts.get(r.getAccountId());
        m.put("accountName", a == null ? "" : a.getName());
        m.put("accountIcon", a == null || a.getIcon() == null ? "" : a.getIcon());
        m.put("accountStatus", a == null || a.getStatus() == null
                ? SavingAccount.STATUS_ENABLED : a.getStatus());
        m.put("direction", r.getDirection());
        m.put("source", r.getSource() == null ? SavingRecord.SRC_NORMAL : r.getSource());
        m.put("amount", r.getAmount());
        m.put("date", r.getDate() == null ? "" : r.getDate().toString());
        m.put("note", r.getNote() == null ? "" : r.getNote());
        m.put("openid", r.getOpenid());
        m.put("operatorName", r.getOperatorName() == null ? "" : r.getOperatorName());
        m.put("linkId", r.getLinkId() == null ? "" : r.getLinkId());
        m.put("linkedTransactionId", r.getLinkedTransactionId() == null ? "" : r.getLinkedTransactionId());
        m.put("readonly", readonly);
        m.put("createdAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString());
        m.put("updatedAt", r.getUpdatedAt() == null ? "" : r.getUpdatedAt().toString());
        return m;
    }

    private Map<Long, Integer> txStatusMap(Collection<Long> txIds) {
        Map<Long, Integer> map = new HashMap<>();
        if (txIds == null || txIds.isEmpty()) {
            return map;
        }
        List<Long> ids = txIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        for (TransactionRecord tx : transactionMapper.selectBatchIds(ids)) {
            map.put(tx.getId(), tx.getStatus());
        }
        return map;
    }

    /** 关联账单已作废（或已不存在）时储蓄流水转只读。 */
    private boolean isReadonly(SavingRecord r, Map<Long, Integer> txStatus) {
        if (r.getLinkedTransactionId() == null) {
            return false;
        }
        Integer status = txStatus.get(r.getLinkedTransactionId());
        return status == null || status == TransactionRecord.STATUS_VOID;
    }

    private SavingRecord lastAdjust(Long familyId, Long accountId) {
        List<SavingRecord> list = recordMapper.selectList(Wrappers.<SavingRecord>lambdaQuery()
                .eq(SavingRecord::getFamilyId, familyId)
                .eq(SavingRecord::getAccountId, accountId)
                .eq(SavingRecord::getSource, SavingRecord.SRC_ADJUST)
                .orderByDesc(SavingRecord::getCreatedAt)
                .orderByDesc(SavingRecord::getId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private boolean within24h(SavingRecord adjust) {
        LocalDateTime at = adjust.getCreatedAt();
        return at != null && at.isAfter(TimeSupport.now().minusHours(24));
    }

    private long signedDelta(SavingRecord adjust) {
        return SavingRecord.DIR_IN.equals(adjust.getDirection())
                ? adjust.getAmount() : -adjust.getAmount();
    }
}
