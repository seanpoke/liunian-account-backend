package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.entity.Category;
import com.liunian.account.mapper.CategoryMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CategoryService {

    private final CategoryMapper categoryMapper;
    private final FamilyMemberMapper memberMapper;

    public CategoryService(CategoryMapper categoryMapper, FamilyMemberMapper memberMapper) {
        this.categoryMapper = categoryMapper;
        this.memberMapper = memberMapper;
    }

    private void requireMember(String openid, Long familyId) {
        if (memberMapper.selectCount(Wrappers.<com.liunian.account.entity.FamilyMember>lambdaQuery()
                .eq(com.liunian.account.entity.FamilyMember::getOpenid, openid)
                .eq(com.liunian.account.entity.FamilyMember::getFamilyId, familyId)
                .eq(com.liunian.account.entity.FamilyMember::getStatus, "active")) == 0) {
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
            m.put("icon", c.getIcon() == null ? "" : c.getIcon());
            m.put("color", c.getColor() == null ? "" : c.getColor());
            m.put("sort", c.getSort() == null ? 0 : c.getSort());
            m.put("isSystem", c.getIsSystem() == null ? 0 : c.getIsSystem());
            result.add(m);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> create(String openid, Long familyId, String name, String type, String icon, String color) {
        requireMember(openid, familyId);
        if (!"income".equals(type) && !"expense".equals(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 必须是 income/expense");
        }
        Integer maxSort = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .eq(Category::getFamilyId, familyId))
                .stream().map(Category::getSort).max(Integer::compareTo).orElse(0);
        Category c = new Category();
        c.setFamilyId(familyId);
        c.setName(name);
        c.setType(type);
        c.setIcon(icon);
        c.setColor(color);
        c.setSort(maxSort == null ? 1 : maxSort + 1);
        c.setIsSystem(0);
        categoryMapper.insert(c);
        return Map.of("id", c.getId(), "name", c.getName(), "type", c.getType(),
                "icon", c.getIcon() == null ? "" : c.getIcon(),
                "color", c.getColor() == null ? "" : c.getColor(), "isSystem", 0);
    }

    @Transactional
    public void delete(String openid, Long categoryId) {
        Category c = categoryMapper.selectById(categoryId);
        if (c == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分类不存在");
        }
        requireMember(openid, c.getFamilyId());
        if (c.getIsSystem() != null && c.getIsSystem() == 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "系统预置分类不可删除");
        }
        if (c.getFamilyId() != null) {
            // 自定义分类仅能删本家庭的
            if (memberMapper.selectCount(Wrappers.<com.liunian.account.entity.FamilyMember>lambdaQuery()
                    .eq(com.liunian.account.entity.FamilyMember::getOpenid, openid)
                    .eq(com.liunian.account.entity.FamilyMember::getFamilyId, c.getFamilyId())
                    .eq(com.liunian.account.entity.FamilyMember::getStatus, "active")) == 0) {
                throw new BizException(ErrorCode.FORBIDDEN, "无权限");
            }
        }
        categoryMapper.deleteById(categoryId);
    }
}
