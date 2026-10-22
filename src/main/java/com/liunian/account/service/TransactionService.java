package com.liunian.account.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.common.TimeSupport;
import com.liunian.account.entity.Category;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.TransactionRecord;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.CategoryMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.TransactionMapper;
import com.liunian.account.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TransactionService {

    private final TransactionMapper transactionMapper;
    private final FamilyMemberMapper memberMapper;
    private final CategoryMapper categoryMapper;
    private final UserMapper userMapper;
    private final SavingService savingService;

    public TransactionService(TransactionMapper transactionMapper, FamilyMemberMapper memberMapper,
                              CategoryMapper categoryMapper, UserMapper userMapper,
                              SavingService savingService) {
        this.transactionMapper = transactionMapper;
        this.memberMapper = memberMapper;
        this.categoryMapper = categoryMapper;
        this.userMapper = userMapper;
        this.savingService = savingService;
    }

    private FamilyMember requireMemberOfFamily(String openid, Long familyId) {
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .eq(FamilyMember::getFamilyId, familyId));
        if (m == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非家庭成员");
        }
        return m;
    }

    private void fillCategory(TransactionRecord tx, Long categoryId) {
        if (categoryId == null) {
            return;
        }
        Category c = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery()
                .eq(Category::getId, categoryId)
                .and(w -> w.eq(Category::getFamilyId, tx.getFamilyId()).or().isNull(Category::getFamilyId)));
        if (c == null) {
            // 分类已逻辑删除时查询会自动过滤，故与「不存在」同一提示
            throw new BizException(ErrorCode.BAD_REQUEST, "分类不存在或已删除");
        }
        tx.setCategoryId(c.getId());
        tx.setCategoryName(c.getName());
        tx.setCategoryIcon(c.getIcon());
        tx.setCategoryColor(c.getColor());
    }

    /** 业务时间 HH:mm（精确到分钟）；空白返回 null，格式非法抛 400。 */
    private String normalizeTime(String time) {
        if (time == null) {
            return null;
        }
        String t = time.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return java.time.LocalTime.parse(t)
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "时间格式须为 HH:mm");
        }
    }

    /**
     * 记账。{@code savingAccountId} 非空表示记账页的两个联动开关：
     * <ul>
     *   <li>支出 → 「从储蓄支付」：同时写一笔支出 + 一笔储蓄取出（余额不足硬阻断）；</li>
     *   <li>收入 → 「到账即存」：同时写一笔收入 + 一笔储蓄存入。</li>
     * </ul>
     * 两者通过 {@code saving_record.linked_transaction_id} 绑定，成对增删改。
     */
    @Transactional
    public Long create(String openid, Long familyId, String type, Integer amount,
                       Long categoryId, LocalDate date, String occurTime, String note, List<String> images,
                       Long savingAccountId) {
        if (familyId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "familyId 必填");
        }
        FamilyMember m = requireMemberOfFamily(openid, familyId);
        if (!"income".equals(type) && !"expense".equals(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 必须是 income/expense");
        }
        if (amount == null || amount <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "金额必须大于 0");
        }
        if (date == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "日期必填");
        }
        TransactionRecord tx = new TransactionRecord();
        tx.setFamilyId(familyId);
        tx.setOpenid(openid);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setDate(date);
        tx.setOccurTime(normalizeTime(occurTime));
        tx.setNote(note);
        tx.setImages(images);
        tx.setStatus(TransactionRecord.STATUS_VALID);
        tx.setCreatedAt(TimeSupport.now());
        tx.setUpdatedAt(TimeSupport.now());
        fillCategory(tx, categoryId);
        transactionMapper.insert(tx);
        if (savingAccountId != null) {
            // 与账单同事务：余额不足 / 账户停用都在这里抛错并整体回滚
            savingService.createLinked(m, tx, savingAccountId);
        }
        return tx.getId();
    }

    @Transactional
    public void update(String openid, Long id, String type, Integer amount,
                       Long categoryId, LocalDate date, String occurTime, String note, List<String> images,
                       Long savingAccountId) {
        TransactionRecord tx = requireValid(id);
        FamilyMember m = requireMemberOfFamily(openid, tx.getFamilyId());
        if (!"owner".equals(m.getRole()) && !openid.equals(tx.getOpenid())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权修改他人记录");
        }
        boolean linked = savingService.hasLinked(id);
        if (type != null) {
            if (!"income".equals(type) && !"expense".equals(type)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "type 必须是 income/expense");
            }
            // 组合记录的方向决定储蓄流水是取出还是存入，改方向等于另一笔业务
            if (linked && !type.equals(tx.getType())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "组合记录不可修改收支方向，请删除后重新记录");
            }
            tx.setType(type);
        }
        if (amount != null) {
            if (amount <= 0) throw new BizException(ErrorCode.BAD_REQUEST, "金额必须大于 0");
            tx.setAmount(amount);
        }
        // 分类未变更时不重新校验：历史账单的分类可能已被删除，
        // 此时只改金额/备注等字段仍应可以保存（保留原有分类快照）
        if (categoryId != null && !categoryId.equals(tx.getCategoryId())) fillCategory(tx, categoryId);
        if (date != null) tx.setDate(date);
        if (occurTime != null) tx.setOccurTime(normalizeTime(occurTime));
        if (note != null) tx.setNote(note);
        if (images != null) tx.setImages(images);
        // 配对的储蓄流水：金额/日期强制同步、账户不可改（内部做余额上限校验）
        savingService.syncLinked(tx, amount, date, savingAccountId);
        tx.setUpdatedAt(TimeSupport.now());
        transactionMapper.updateById(tx);
    }

    @Transactional
    public void delete(String openid, Long id) {
        TransactionRecord tx = transactionMapper.selectById(id);
        if (tx == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记录不存在");
        }
        FamilyMember m = requireMemberOfFamily(openid, tx.getFamilyId());
        if (!"owner".equals(m.getRole()) && !openid.equals(tx.getOpenid())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权删除他人记录");
        }
        // 组合记录成对删除：删支出同时撤销对应的储蓄取出（余额自动回补）
        savingService.deleteLinkedForTransaction(tx);
        transactionMapper.deleteById(id);
    }

    /**
     * 成员退出/被移除时，把其在该家庭的流水批量置为「已作废」。
     * 不物理删除：保留审计痕迹，但所有查询与统计默认只看有效流水。
     *
     * <p><b>排除与储蓄配对的两个方向的记录</b>（「从储蓄支付」的支出、「到账即存」的收入）：
     * 原则是「记账行为可以作废，资金位置不能作废」——储蓄余额是 Σ存入−Σ取出 的派生值，
     * 作废其中一侧会造出「收入作废但存入还在」的错账。因此只要账单被
     * {@code saving_record.linked_transaction_id} 引用，就整对保留。</p>
     *
     * @param reason member_quit / member_removed
     */
    @Transactional
    public void voidByMember(Long familyId, String openid, String reason, String operator) {
        transactionMapper.voidByMemberExceptSavingLinked(familyId, openid, reason, operator, TimeSupport.now());
    }

    private TransactionRecord requireValid(Long id) {
        TransactionRecord tx = transactionMapper.selectById(id);
        if (tx == null || (tx.getStatus() != null && tx.getStatus() == TransactionRecord.STATUS_VOID)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记录不存在");
        }
        return tx;
    }

    private Map<String, Object> toVo(TransactionRecord tx, String nickname) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", tx.getId());
        m.put("familyId", tx.getFamilyId());
        m.put("openid", tx.getOpenid());
        m.put("nickname", nickname == null ? "" : nickname);
        m.put("type", tx.getType());
        m.put("amount", tx.getAmount());
        m.put("categoryId", tx.getCategoryId() == null ? "" : tx.getCategoryId());
        m.put("categoryName", tx.getCategoryName() == null ? "" : tx.getCategoryName());
        m.put("categoryIcon", tx.getCategoryIcon() == null ? "" : tx.getCategoryIcon());
        m.put("categoryColor", tx.getCategoryColor() == null ? "" : tx.getCategoryColor());
        m.put("date", tx.getDate() == null ? "" : tx.getDate().toString());
        m.put("time", tx.getOccurTime() == null ? "" : tx.getOccurTime());
        m.put("note", tx.getNote() == null ? "" : tx.getNote());
        m.put("images", tx.getImages() == null ? List.of() : tx.getImages());
        m.put("createdAt", tx.getCreatedAt() == null ? "" : tx.getCreatedAt().toString());
        m.put("updatedAt", tx.getUpdatedAt() == null ? "" : tx.getUpdatedAt().toString());
        return m;
    }

    /**
     * 昵称解析：家庭内显示名优先，缺失时回退 user 表（成员被移除后历史账单仍可显示昵称）。
     */
    private Map<String, String> nicknameMap(Long familyId, Collection<String> openids) {
        Map<String, String> map = new HashMap<>();
        if (openids == null || openids.isEmpty()) {
            return map;
        }
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery().in(User::getOpenid, openids));
        for (User u : users) {
            if (u.getNickname() != null) {
                map.put(u.getOpenid(), u.getNickname());
            }
        }
        List<FamilyMember> members = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId)
                .in(FamilyMember::getOpenid, openids));
        for (FamilyMember m : members) {
            if (m.getNickname() != null) {
                map.put(m.getOpenid(), m.getNickname());
            }
        }
        return map;
    }

    /**
     * 反查该账单是否属于「从储蓄支付 / 到账即存」的组合记录，供明细页打「储蓄」小标
     * 与前端判断编辑/删除规则（组合记录只能成对操作）。
     */
    private void attachSaving(Map<String, Object> vo, Map<Long, Map<String, Object>> info) {
        Object id = vo.get("id");
        Map<String, Object> s = id instanceof Long l ? info.get(l) : null;
        vo.put("savingLinked", s != null);
        vo.put("savingRecordId", s == null ? "" : s.get("savingRecordId"));
        vo.put("savingAccountId", s == null ? "" : s.get("savingAccountId"));
        vo.put("savingAccountName", s == null ? "" : s.get("savingAccountName"));
        vo.put("savingDirection", s == null ? "" : s.get("savingDirection"));
    }

    public Map<String, Object> get(String openid, Long id) {
        TransactionRecord tx = requireValid(id);
        requireMemberOfFamily(openid, tx.getFamilyId());
        Map<String, String> nick = nicknameMap(tx.getFamilyId(), List.of(tx.getOpenid()));
        Map<String, Object> vo = toVo(tx, nick.get(tx.getOpenid()));
        attachSaving(vo, savingService.linkedInfoByTxIds(tx.getFamilyId(), List.of(tx.getId())));
        return vo;
    }

    public Map<String, Object> list(String openid, Long familyId, LocalDate start, LocalDate end,
                                    String member, String type, Long category, long page, long size) {
        requireMemberOfFamily(openid, familyId);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord> q = Wrappers.lambdaQuery(TransactionRecord.class)
                .eq(TransactionRecord::getFamilyId, familyId)
                .eq(TransactionRecord::getStatus, TransactionRecord.STATUS_VALID);
        if (start != null) q.ge(TransactionRecord::getDate, start);
        if (end != null) q.le(TransactionRecord::getDate, end);
        if (member != null) q.eq(TransactionRecord::getOpenid, member);
        if (type != null) q.eq(TransactionRecord::getType, type);
        if (category != null) q.eq(TransactionRecord::getCategoryId, category);
        // id 兜底排序：同一天且同一创建秒的记录在分页边界上顺序稳定，避免翻页重复/漏项
        q.orderByDesc(TransactionRecord::getDate)
                .orderByDesc(TransactionRecord::getCreatedAt)
                .orderByDesc(TransactionRecord::getId);

        IPage<TransactionRecord> p = transactionMapper.selectPage(new Page<>(page, size), q);
        List<String> openids = p.getRecords().stream().map(TransactionRecord::getOpenid).distinct().toList();
        Map<String, String> nickMap = nicknameMap(familyId, openids);
        List<Long> txIds = p.getRecords().stream().map(TransactionRecord::getId).toList();
        Map<Long, Map<String, Object>> savingInfo = savingService.linkedInfoByTxIds(familyId, txIds);
        List<Map<String, Object>> list = new ArrayList<>();
        for (TransactionRecord tx : p.getRecords()) {
            Map<String, Object> vo = toVo(tx, nickMap.get(tx.getOpenid()));
            attachSaving(vo, savingInfo);
            list.add(vo);
        }
        return Map.of("list", list, "total", p.getTotal());
    }

    /** 按日分组 + 每日小计（首页最近 N 天流水用） */
    public Map<String, Object> listByDay(String openid, Long familyId, LocalDate start, LocalDate end) {
        requireMemberOfFamily(openid, familyId);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord> q = Wrappers.lambdaQuery(TransactionRecord.class)
                .eq(TransactionRecord::getFamilyId, familyId)
                .eq(TransactionRecord::getStatus, TransactionRecord.STATUS_VALID);
        if (start != null) q.ge(TransactionRecord::getDate, start);
        if (end != null) q.le(TransactionRecord::getDate, end);
        q.orderByDesc(TransactionRecord::getDate).orderByDesc(TransactionRecord::getCreatedAt);
        List<TransactionRecord> all = transactionMapper.selectList(q);
        List<String> openids = all.stream().map(TransactionRecord::getOpenid).distinct().toList();
        Map<String, String> nickMap = nicknameMap(familyId, openids);
        Map<Long, Map<String, Object>> savingInfo = savingService.linkedInfoByTxIds(familyId,
                all.stream().map(TransactionRecord::getId).toList());
        Map<LocalDate, List<Map<String, Object>>> grouped = new java.util.LinkedHashMap<>();
        for (TransactionRecord tx : all) {
            Map<String, Object> vo = toVo(tx, nickMap.get(tx.getOpenid()));
            attachSaving(vo, savingInfo);
            grouped.computeIfAbsent(tx.getDate(), k -> new ArrayList<>()).add(vo);
        }
        List<Map<String, Object>> days = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Map<String, Object>>> e : grouped.entrySet()) {
            int income = 0, expense = 0;
            for (Map<String, Object> r : e.getValue()) {
                int amt = (int) r.get("amount");
                if ("income".equals(r.get("type"))) income += amt;
                else expense += amt;
            }
            days.add(Map.of(
                    "date", e.getKey().toString(),
                    "income", income,
                    "expense", expense,
                    "balance", income - expense,
                    "list", e.getValue()));
        }
        return Map.of("days", days, "total", all.size());
    }
}
