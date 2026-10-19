package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.entity.Category;
import com.liunian.account.mapper.CategoryMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class CategoryService {

    /** code 仅用于系统语义编码：小写字母开头，允许小写字母/数字/下划线。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");

    private final CategoryMapper categoryMapper;
    private final FamilyMemberMapper memberMapper;

    public CategoryService(CategoryMapper categoryMapper, FamilyMemberMapper memberMapper) {
        this.categoryMapper = categoryMapper;
        this.memberMapper = memberMapper;
    }

    private void requireMember(String openid, Long familyId) {
        if (memberMapper.selectCount(Wrappers.<com.liunian.account.entity.FamilyMember>lambdaQuery()
                .eq(com.liunian.account.entity.FamilyMember::getOpenid, openid)
                .eq(com.liunian.account.entity.FamilyMember::getFamilyId, familyId)) == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "非家庭成员");
        }
    }

    public List<Map<String, Object>> list(String openid, Long familyId, String type) {
        requireMember(openid, familyId);
        List<Category> list = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .eq(type != null, Category::getType, type)
                .and(w -> w.eq(Category::getFamilyId, familyId).or().isNull(Category::getFamilyId))
                .orderByAsc(Category::getSort));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Category c : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("familyId", c.getFamilyId());
            m.put("name", c.getName());
            m.put("type", c.getType());
            m.put("code", c.getCode() == null ? "" : c.getCode());
            m.put("icon", c.getIcon() == null ? "" : c.getIcon());
            m.put("color", c.getColor() == null ? "" : c.getColor());
            m.put("sort", c.getSort() == null ? 0 : c.getSort());
            m.put("isSystem", c.getIsSystem() == null ? 0 : c.getIsSystem());
            result.add(m);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> create(String openid, Long familyId, String name, String type, String code, String icon, String color) {
        if (familyId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "familyId 必填");
        }
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分类名称不能为空");
        }
        requireMember(openid, familyId);
        if (!"income".equals(type) && !"expense".equals(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 必须是 income/expense");
        }
        String normalizedCode = normalizeCode(code);
        Integer maxSort = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .eq(Category::getFamilyId, familyId))
                .stream().map(Category::getSort).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(0);
        Category c = new Category();
        c.setFamilyId(familyId);
        c.setName(name);
        c.setType(type);
        c.setCode(normalizedCode);
        c.setIcon(icon);
        c.setColor(color);
        c.setSort(maxSort + 1);
        c.setIsSystem(0);
        try {
            categoryMapper.insert(c);
        } catch (DuplicateKeyException e) {
            // 并发下由数据库唯一索引兜底，避免 select-then-insert 竞态
            throw new BizException(ErrorCode.BAD_REQUEST, "分类编码已存在");
        }
        return Map.of("id", c.getId(), "name", c.getName(), "type", c.getType(),
                "code", c.getCode() == null ? "" : c.getCode(),
                "icon", c.getIcon() == null ? "" : c.getIcon(),
                "color", c.getColor() == null ? "" : c.getColor(), "isSystem", 0);
    }

    /**
     * 家庭自定义分类默认不带语义编码：空串/空白归一化为 NULL
     * （MySQL 唯一索引允许多个 NULL，因此不会与系统预置 code 冲突）。
     */
    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim();
        if (!CODE_PATTERN.matcher(c).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 需为小写字母开头，仅含小写字母/数字/下划线，长度 2-32");
        }
        return c;
    }

    @Transactional
    public void delete(String openid, Long categoryId) {
        Category c = categoryMapper.selectById(categoryId);
        if (c == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分类不存在");
        }
        if (c.getIsSystem() != null && c.getIsSystem() == 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "系统预置分类不可删除");
        }
        // 自定义分类仅能删本家庭的（系统分类 family_id 为 NULL，已在上一步拦掉）
        requireMember(openid, c.getFamilyId());
        categoryMapper.deleteById(categoryId);
    }
}
