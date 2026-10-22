package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.common.Money;
import com.liunian.account.common.TimeSupport;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.SavingAccount;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.SavingAccountMapper;
import com.liunian.account.mapper.SavingRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 储蓄账户（余额一律派生自 saving_record，本服务不落余额字段）。
 *
 * <p>预置账户模型：`family_id IS NULL` = 系统预置（全局 5 条，与 category.family_id 同套路），
 * **完全只读**——不可改名、不可停用、不可删除（所有权仅限系统）；家庭自定义账户
 * `is_system=0, family_id=该家庭`，可改名/排序/停用/删除。因此本服务的可见集合是
 * 「全局预置 + 本家庭自定义」。</p>
 */
@Service
public class SavingAccountService {

    /** 家庭自定义账户上限（另有 5 个预置，账户总数最多 25）。 */
    public static final int MAX_CUSTOM = 20;

    private static final int MAX_NAME_LEN = 32;
    private static final int MAX_TEXT_LEN = 32;
    private static final String DEFAULT_ICON = "💰";
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final SavingAccountMapper accountMapper;
    private final SavingRecordMapper recordMapper;
    private final FamilyMemberMapper memberMapper;

    public SavingAccountService(SavingAccountMapper accountMapper, SavingRecordMapper recordMapper,
                                FamilyMemberMapper memberMapper) {
        this.accountMapper = accountMapper;
        this.recordMapper = recordMapper;
        this.memberMapper = memberMapper;
    }

    // ------------------------------------------------------------------ 成员/权限

    public FamilyMember requireMember(String openid, Long familyId) {
        if (familyId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "familyId 必填");
        }
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .eq(FamilyMember::getFamilyId, familyId));
        if (m == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非家庭成员");
        }
        return m;
    }

    public void requireOwner(String openid, Long familyId) {
        FamilyMember m = requireMember(openid, familyId);
        if (!"owner".equals(m.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅创建者可管理储蓄账户");
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 储蓄页/管理页账户列表（全局预置 + 本家庭自定义，含停用账户，不含已删除）。 */
    public List<Map<String, Object>> list(String openid, Long familyId) {
        requireMember(openid, familyId);
        return buildVos(familyId);
    }

    public List<Map<String, Object>> buildVos(Long familyId) {
        // 可见集合 = 本家庭自定义 + 全局预置（family_id IS NULL）；预置固定排在最前
        List<SavingAccount> accounts = accountMapper.selectList(Wrappers.<SavingAccount>lambdaQuery()
                .and(w -> w.eq(SavingAccount::getFamilyId, familyId).or().isNull(SavingAccount::getFamilyId))
                .orderByDesc(SavingAccount::getIsSystem)
                .orderByAsc(SavingAccount::getSort)
                .orderByAsc(SavingAccount::getId));
        Map<Long, long[]> stats = statsMap(familyId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SavingAccount a : accounts) {
            long[] s = stats.getOrDefault(a.getId(), new long[]{0L, 0L, 0L});
            result.add(toVo(a, s[0] - s[1], s[2]));
        }
        return result;
    }

    public Map<String, Object> toVo(SavingAccount a, long balance, long recordCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("familyId", a.getFamilyId() == null ? "" : a.getFamilyId());
        m.put("name", a.getName());
        m.put("icon", a.getIcon() == null ? "" : a.getIcon());
        m.put("color", a.getColor() == null ? "" : a.getColor());
        m.put("sort", a.getSort() == null ? 0 : a.getSort());
        m.put("isSystem", a.getIsSystem() == null ? 0 : a.getIsSystem());
        m.put("status", a.getStatus() == null ? SavingAccount.STATUS_ENABLED : a.getStatus());
        m.put("balance", balance);
        m.put("recordCount", recordCount);
        // 预置账户完全只读（不可改名/停用/删除）；自定义账户余额为 0 才可删
        m.put("deletable", (a.getIsSystem() == null || a.getIsSystem() == SavingAccount.CUSTOM) && balance == 0);
        return m;
    }

    /** 含逻辑删除行的账户字典（全局预置 + 本家庭），供历史流水展示账户名。 */
    public Map<Long, SavingAccount> rawMap(Long familyId) {
        Map<Long, SavingAccount> map = new HashMap<>();
        for (SavingAccount a : accountMapper.listAllIncludingDeleted(familyId)) {
            map.put(a.getId(), a);
        }
        return map;
    }

    /** 单账户余额 = Σ(in + transfer_in) − Σ(out + transfer_out)。 */
    public long balanceOf(Long familyId, Long accountId) {
        Map<String, Object> row = recordMapper.balanceOf(familyId, accountId);
        return num(row, "totalIn") - num(row, "totalOut");
    }

    /** 按账户聚合的 [totalIn, totalOut, recordCount]。 */
    public Map<Long, long[]> statsMap(Long familyId) {
        Map<Long, long[]> map = new HashMap<>();
        for (Map<String, Object> row : recordMapper.statsByAccount(familyId)) {
            Object id = row.get("accountId");
            if (id instanceof Number n) {
                map.put(n.longValue(), new long[]{num(row, "totalIn"), num(row, "totalOut"), num(row, "recordCount")});
            }
        }
        return map;
    }

    public static long num(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    // ------------------------------------------------------------------ 写操作用的账户解析

    /**
     * 取账户并加行锁（不校验启用状态）。用于编辑既有流水——账户在流水产生后被停用，
     * 仍应允许改金额/日期/备注。
     */
    public SavingAccount lockAccount(Long familyId, Long accountId) {
        SavingAccount a = accountMapper.lockById(accountId);
        // familyId 为 NULL 的是全局预置账户，所有家庭共用
        if (a == null || (a.getFamilyId() != null && !familyId.equals(a.getFamilyId()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "储蓄账户不存在");
        }
        return a;
    }

    /**
     * 解析并锁定「可用于新增流水」的账户（停用/已删除/跨家庭一律拒绝）。
     * accountId 为空时落默认账户「活期」。
     */
    public SavingAccount lockActiveAccount(Long familyId, Long accountId) {
        Long id = accountId != null ? accountId : defaultAccountId(familyId);
        SavingAccount a = lockAccount(familyId, id);
        if (a.getStatus() != null && a.getStatus() == SavingAccount.STATUS_DISABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该储蓄账户已停用：" + a.getName());
        }
        return a;
    }

    /** 默认账户：全局预置「活期」；没有则取第一个启用账户。 */
    public Long defaultAccountId(Long familyId) {
        List<SavingAccount> accounts = accountMapper.selectList(Wrappers.<SavingAccount>lambdaQuery()
                .and(w -> w.eq(SavingAccount::getFamilyId, familyId).or().isNull(SavingAccount::getFamilyId))
                .orderByDesc(SavingAccount::getIsSystem)
                .orderByAsc(SavingAccount::getSort)
                .orderByAsc(SavingAccount::getId));
        return accounts.stream()
                .filter(a -> a.getStatus() == null || a.getStatus() == SavingAccount.STATUS_ENABLED)
                .filter(a -> SavingAccount.DEFAULT_NAME.equals(a.getName()))
                .findFirst()
                .or(() -> accounts.stream()
                        .filter(a -> a.getStatus() == null || a.getStatus() == SavingAccount.STATUS_ENABLED)
                        .findFirst())
                .map(SavingAccount::getId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有可用的储蓄账户"));
    }

    // ------------------------------------------------------------------ 账户 CRUD（仅 owner）

    @Transactional
    public Map<String, Object> create(String openid, Long familyId, String name, String icon,
                                      String color, Integer sort) {
        requireOwner(openid, familyId);
        String n = normalizeName(name);
        if (existsName(familyId, n, null)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该账户名已存在");
        }
        long customCount = accountMapper.selectCount(Wrappers.<SavingAccount>lambdaQuery()
                .eq(SavingAccount::getFamilyId, familyId)
                .eq(SavingAccount::getIsSystem, SavingAccount.CUSTOM));
        if (customCount >= MAX_CUSTOM) {
            throw new BizException(ErrorCode.BAD_REQUEST, "最多 " + MAX_CUSTOM + " 个自定义账户");
        }
        int maxSort = accountMapper.selectList(Wrappers.<SavingAccount>lambdaQuery()
                        .and(w -> w.eq(SavingAccount::getFamilyId, familyId).or().isNull(SavingAccount::getFamilyId)))
                .stream().map(SavingAccount::getSort).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(0);

        SavingAccount a = new SavingAccount();
        a.setFamilyId(familyId);
        a.setName(n);
        a.setIcon(normalizeText(icon, DEFAULT_ICON, "图标"));
        a.setColor(normalizeColor(color));
        a.setSort(sort != null ? sort : maxSort + 1);
        a.setIsSystem(SavingAccount.CUSTOM);
        a.setStatus(SavingAccount.STATUS_ENABLED);
        a.setCreatedAt(TimeSupport.now());
        a.setUpdatedAt(TimeSupport.now());
        accountMapper.insert(a);
        return toVo(a, 0L, 0L);
    }

    @Transactional
    public Map<String, Object> update(String openid, Long accountId, String name, String icon,
                                      String color, Integer sort, Integer status) {
        SavingAccount a = accountMapper.selectById(accountId);
        if (a == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "储蓄账户不存在");
        }
        // 预置账户是系统定义的全局行，任何家庭都不能改名/排序/停用
        if (a.getIsSystem() != null && a.getIsSystem() == SavingAccount.SYSTEM) {
            throw new BizException(ErrorCode.FORBIDDEN, "预置账户不可修改，仅自定义账户可改名/排序/停用");
        }
        requireOwner(openid, a.getFamilyId());
        if (name != null) {
            String n = normalizeName(name);
            if (!n.equals(a.getName()) && existsName(a.getFamilyId(), n, accountId)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "该账户名已存在");
            }
            a.setName(n);
        }
        if (icon != null) {
            a.setIcon(normalizeText(icon, DEFAULT_ICON, "图标"));
        }
        if (color != null) {
            a.setColor(normalizeColor(color));
        }
        if (sort != null) {
            a.setSort(sort);
        }
        if (status != null) {
            if (status != SavingAccount.STATUS_ENABLED && status != SavingAccount.STATUS_DISABLED) {
                throw new BizException(ErrorCode.BAD_REQUEST, "status 只能是 0（停用）/ 1（启用）");
            }
            a.setStatus(status);
        }
        a.setUpdatedAt(TimeSupport.now());
        accountMapper.updateById(a);
        long[] s = statsMap(a.getFamilyId()).getOrDefault(accountId, new long[]{0L, 0L, 0L});
        return toVo(a, s[0] - s[1], s[2]);
    }

    /**
     * 删除账户：预置账户不可删；余额 &gt; 0 禁止删除。
     * 余额为 0 时逻辑删除，历史流水的账户名仍由 rawMap 带出。
     */
    @Transactional
    public void delete(String openid, Long accountId) {
        SavingAccount a = accountMapper.selectById(accountId);
        if (a == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "储蓄账户不存在");
        }
        if (a.getIsSystem() != null && a.getIsSystem() == SavingAccount.SYSTEM) {
            throw new BizException(ErrorCode.FORBIDDEN, "预置账户不可删除");
        }
        requireOwner(openid, a.getFamilyId());
        long balance = balanceOf(a.getFamilyId(), accountId);
        if (balance > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该账户余额为 " + Money.yuan(balance) + "，请先转出到其他账户");
        }
        accountMapper.deleteById(accountId);
    }

    // ------------------------------------------------------------------ 校验与归一化

    private boolean existsName(Long familyId, String name, Long excludeId) {
        return accountMapper.selectCount(Wrappers.<SavingAccount>lambdaQuery()
                .eq(SavingAccount::getFamilyId, familyId)
                .eq(SavingAccount::getName, name)
                .ne(excludeId != null, SavingAccount::getId, excludeId)) > 0;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "账户名不能为空");
        }
        String n = name.trim();
        if (n.length() > MAX_NAME_LEN) {
            throw new BizException(ErrorCode.BAD_REQUEST, "账户名最多 " + MAX_NAME_LEN + " 个字符");
        }
        return n;
    }

    private String normalizeText(String value, String defaultValue, String label) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String v = value.trim();
        if (v.length() > MAX_TEXT_LEN) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最多 " + MAX_TEXT_LEN + " 个字符");
        }
        return v;
    }

    private String normalizeColor(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        String c = color.trim();
        if (!COLOR_PATTERN.matcher(c).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "配色需为 #RRGGBB 十六进制");
        }
        return c;
    }
}
