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

    public TransactionService(TransactionMapper transactionMapper, FamilyMemberMapper memberMapper,
                              CategoryMapper categoryMapper, UserMapper userMapper) {
        this.transactionMapper = transactionMapper;
        this.memberMapper = memberMapper;
        this.categoryMapper = categoryMapper;
        this.userMapper = userMapper;
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
            throw new BizException(ErrorCode.BAD_REQUEST, "分类不存在");
        }
        tx.setCategoryId(c.getId());
        tx.setCategoryName(c.getName());
        tx.setCategoryIcon(c.getIcon());
        tx.setCategoryColor(c.getColor());
    }

    @Transactional
    public Long create(String openid, Long familyId, String type, Integer amount,
                       Long categoryId, LocalDate date, String note, List<String> images) {
        if (familyId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "familyId 必填");
        }
        requireMemberOfFamily(openid, familyId);
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
        tx.setNote(note);
        tx.setImages(images);
        tx.setStatus(TransactionRecord.STATUS_VALID);
        tx.setCreatedAt(TimeSupport.now());
        tx.setUpdatedAt(TimeSupport.now());
        fillCategory(tx, categoryId);
        transactionMapper.insert(tx);
        return tx.getId();
    }

    @Transactional
    public void update(String openid, Long id, String type, Integer amount,
                       Long categoryId, LocalDate date, String note, List<String> images) {
        TransactionRecord tx = requireValid(id);
        FamilyMember m = requireMemberOfFamily(openid, tx.getFamilyId());
        if (!"owner".equals(m.getRole()) && !openid.equals(tx.getOpenid())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权修改他人记录");
        }
        if (type != null) {
            if (!"income".equals(type) && !"expense".equals(type)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "type 必须是 income/expense");
            }
            tx.setType(type);
        }
        if (amount != null) {
            if (amount <= 0) throw new BizException(ErrorCode.BAD_REQUEST, "金额必须大于 0");
            tx.setAmount(amount);
        }
        if (categoryId != null) fillCategory(tx, categoryId);
        if (date != null) tx.setDate(date);
        if (note != null) tx.setNote(note);
        if (images != null) tx.setImages(images);
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
        transactionMapper.deleteById(id);
    }

    /**
     * 成员退出/被移除时，把其在该家庭的流水批量置为「已作废」。
     * 不物理删除：保留审计痕迹，但所有查询与统计默认只看有效流水。
     *
     * @param reason member_quit / member_removed
     */
    @Transactional
    public void voidByMember(Long familyId, String openid, String reason, String operator) {
        transactionMapper.update(null, Wrappers.<TransactionRecord>lambdaUpdate()
                .eq(TransactionRecord::getFamilyId, familyId)
                .eq(TransactionRecord::getOpenid, openid)
                .eq(TransactionRecord::getStatus, TransactionRecord.STATUS_VALID)
                .set(TransactionRecord::getStatus, TransactionRecord.STATUS_VOID)
                .set(TransactionRecord::getVoidedAt, TimeSupport.now())
                .set(TransactionRecord::getVoidReason, reason)
                .set(TransactionRecord::getVoidedBy, operator));
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

    public Map<String, Object> get(String openid, Long id) {
        TransactionRecord tx = requireValid(id);
        requireMemberOfFamily(openid, tx.getFamilyId());
        Map<String, String> nick = nicknameMap(tx.getFamilyId(), List.of(tx.getOpenid()));
        return toVo(tx, nick.get(tx.getOpenid()));
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
        q.orderByDesc(TransactionRecord::getDate).orderByDesc(TransactionRecord::getCreatedAt);

        IPage<TransactionRecord> p = transactionMapper.selectPage(new Page<>(page, size), q);
        List<String> openids = p.getRecords().stream().map(TransactionRecord::getOpenid).distinct().toList();
        Map<String, String> nickMap = nicknameMap(familyId, openids);
        List<Map<String, Object>> list = p.getRecords().stream()
                .map(tx -> toVo(tx, nickMap.get(tx.getOpenid()))).toList();
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
        Map<LocalDate, List<Map<String, Object>>> grouped = new java.util.LinkedHashMap<>();
        for (TransactionRecord tx : all) {
            grouped.computeIfAbsent(tx.getDate(), k -> new ArrayList<>()).add(toVo(tx, nickMap.get(tx.getOpenid())));
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
