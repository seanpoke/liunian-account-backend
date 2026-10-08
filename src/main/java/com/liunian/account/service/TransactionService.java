package com.liunian.account.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.entity.Category;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.TransactionRecord;
import com.liunian.account.mapper.CategoryMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.TransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class TransactionService {

    private final TransactionMapper transactionMapper;
    private final FamilyMemberMapper memberMapper;
    private final CategoryMapper categoryMapper;

    public TransactionService(TransactionMapper transactionMapper, FamilyMemberMapper memberMapper, CategoryMapper categoryMapper) {
        this.transactionMapper = transactionMapper;
        this.memberMapper = memberMapper;
        this.categoryMapper = categoryMapper;
    }

    private FamilyMember requireMemberOfFamily(String openid, Long familyId) {
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getStatus, "active"));
        if (m == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非家庭成员");
        }
        return m;
    }

    /** 取当前用户的（唯一）活跃家庭成员，v1 单家庭模型 */
    private FamilyMember requireMember(String openid) {
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .eq(FamilyMember::getStatus, "active"));
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
    public Long create(String openid, String type, Integer amount,
                       Long categoryId, LocalDate date, String note, List<String> images) {
        FamilyMember me = requireMember(openid);
        Long familyId = me.getFamilyId();
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
        tx.setCreatedAt(LocalDateTime.now());
        tx.setUpdatedAt(LocalDateTime.now());
        fillCategory(tx, categoryId);
        transactionMapper.insert(tx);
        return tx.getId();
    }

    @Transactional
    public void update(String openid, Long id, String type, Integer amount,
                       Long categoryId, LocalDate date, String note, List<String> images) {
        TransactionRecord tx = transactionMapper.selectById(id);
        if (tx == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记录不存在");
        }
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
        tx.setUpdatedAt(LocalDateTime.now());
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

    private Map<String, Object> toVo(TransactionRecord tx, String nickname) {
        Map<String, Object> m = new java.util.HashMap<>();
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

    public Map<String, Object> get(String openid, Long id) {
        TransactionRecord tx = transactionMapper.selectById(id);
        if (tx == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记录不存在");
        }
        requireMemberOfFamily(openid, tx.getFamilyId());
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, tx.getFamilyId()).eq(FamilyMember::getOpenid, tx.getOpenid()).eq(FamilyMember::getStatus, "active"));
        return toVo(tx, m == null ? null : m.getNickname());
    }

    public Map<String, Object> list(String openid, LocalDate start, LocalDate end,
                                    String member, String type, Long category, long page, long size) {
        FamilyMember me = requireMember(openid);
        Long familyId = me.getFamilyId();
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord> q = Wrappers.lambdaQuery(TransactionRecord.class)
                .eq(TransactionRecord::getFamilyId, familyId);
        if (start != null) q.ge(TransactionRecord::getDate, start);
        if (end != null) q.le(TransactionRecord::getDate, end);
        if (member != null) q.eq(TransactionRecord::getOpenid, member);
        if (type != null) q.eq(TransactionRecord::getType, type);
        if (category != null) q.eq(TransactionRecord::getCategoryId, category);
        q.orderByDesc(TransactionRecord::getDate).orderByDesc(TransactionRecord::getCreatedAt);

        IPage<TransactionRecord> p = transactionMapper.selectPage(new Page<>(page, size), q);
        List<FamilyMember> members = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId).eq(FamilyMember::getStatus, "active"));
        Map<String, String> nickMap = members.stream()
                .collect(java.util.stream.Collectors.toMap(FamilyMember::getOpenid, FamilyMember::getNickname, (a, b) -> a));
        List<Map<String, Object>> list = p.getRecords().stream()
                .map(tx -> toVo(tx, nickMap.get(tx.getOpenid()))).toList();
        return Map.of("list", list, "total", p.getTotal());
    }

    /** 按日分组 + 每日小计（首页最近 N 天流水用） */
    public Map<String, Object> listByDay(String openid, LocalDate start, LocalDate end) {
        FamilyMember me = requireMember(openid);
        Long familyId = me.getFamilyId();
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord> q = Wrappers.lambdaQuery(TransactionRecord.class)
                .eq(TransactionRecord::getFamilyId, familyId);
        if (start != null) q.ge(TransactionRecord::getDate, start);
        if (end != null) q.le(TransactionRecord::getDate, end);
        q.orderByDesc(TransactionRecord::getDate).orderByDesc(TransactionRecord::getCreatedAt);
        List<TransactionRecord> all = transactionMapper.selectList(q);
        List<FamilyMember> members = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId).eq(FamilyMember::getStatus, "active"));
        Map<String, String> nickMap = members.stream()
                .collect(java.util.stream.Collectors.toMap(FamilyMember::getOpenid, FamilyMember::getNickname, (a, b) -> a));
        Map<LocalDate, List<Map<String, Object>>> grouped = new java.util.LinkedHashMap<>();
        for (TransactionRecord tx : all) {
            grouped.computeIfAbsent(tx.getDate(), k -> new java.util.ArrayList<>()).add(toVo(tx, nickMap.get(tx.getOpenid())));
        }
        List<Map<String, Object>> days = new java.util.ArrayList<>();
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
