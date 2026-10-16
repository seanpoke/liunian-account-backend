package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.entity.Budget;
import com.liunian.account.entity.Category;
import com.liunian.account.entity.Family;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.Invitation;
import com.liunian.account.entity.ReminderSetting;
import com.liunian.account.entity.TransactionRecord;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.BudgetMapper;
import com.liunian.account.mapper.CategoryMapper;
import com.liunian.account.mapper.FamilyMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.InvitationMapper;
import com.liunian.account.mapper.ReminderSettingMapper;
import com.liunian.account.mapper.TransactionMapper;
import com.liunian.account.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FamilyService {

    private final FamilyMapper familyMapper;
    private final FamilyMemberMapper memberMapper;
    private final UserMapper userMapper;
    private final TransactionMapper transactionMapper;
    private final InvitationMapper invitationMapper;
    private final CategoryMapper categoryMapper;
    private final ReminderSettingMapper reminderSettingMapper;
    private final BudgetMapper budgetMapper;

    public FamilyService(FamilyMapper familyMapper, FamilyMemberMapper memberMapper, UserMapper userMapper,
                         TransactionMapper transactionMapper, InvitationMapper invitationMapper,
                         CategoryMapper categoryMapper, ReminderSettingMapper reminderSettingMapper,
                         BudgetMapper budgetMapper) {
        this.familyMapper = familyMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
        this.transactionMapper = transactionMapper;
        this.invitationMapper = invitationMapper;
        this.categoryMapper = categoryMapper;
        this.reminderSettingMapper = reminderSettingMapper;
        this.budgetMapper = budgetMapper;
    }

    private FamilyMember activeMember(String openid) {
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid).eq(FamilyMember::getStatus, "active"));
        if (m == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非家庭成员");
        }
        return m;
    }

    private FamilyMember activeMemberInFamily(String openid, Long familyId) {
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getStatus, "active"));
        if (m == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非本家庭成员");
        }
        return m;
    }

    private Family requireFamily(Long familyId) {
        Family f = familyMapper.selectById(familyId);
        if (f == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "家庭不存在");
        }
        return f;
    }

    @Transactional
    public Long create(String openid, String name) {
        // 允许多家庭：不限制用户只能有一个家庭
        Family family = new Family();
        family.setName(name);
        family.setCreatorOpenid(openid);
        family.setCurrency("CNY");
        family.setMemberCount(1);
        family.setStatus("active");
        family.setCreatedAt(LocalDateTime.now());
        family.setUpdatedAt(LocalDateTime.now());
        familyMapper.insert(family);

        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenid, openid));
        FamilyMember self = new FamilyMember();
        self.setFamilyId(family.getId());
        self.setOpenid(openid);
        self.setNickname(u != null && u.getNickname() != null ? u.getNickname() : "创建者");
        self.setAvatar(u != null ? u.getAvatar() : null);
        self.setRole("owner");
        self.setStatus("active");
        self.setSource("self");
        self.setJoinedAt(LocalDateTime.now());
        self.setUpdatedAt(LocalDateTime.now());
        memberMapper.insert(self);
        return family.getId();
    }

    /** 查询当前用户已加入（active）的全部家庭，供小程序「选择家庭」页使用 */
    public List<Map<String, Object>> myFamilies(String openid) {
        List<FamilyMember> members = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .eq(FamilyMember::getStatus, "active"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (FamilyMember m : members) {
            Family f = familyMapper.selectById(m.getFamilyId());
            Map<String, Object> item = new HashMap<>();
            item.put("familyId", m.getFamilyId());
            item.put("role", m.getRole());
            item.put("name", f != null ? f.getName() : "");
            item.put("avatar", f != null && f.getAvatar() != null ? f.getAvatar() : "");
            item.put("currency", f != null ? f.getCurrency() : "");
            item.put("memberCount", f != null ? f.getMemberCount() : 0);
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> get(String openid, Long familyId) {
        activeMemberInFamily(openid, familyId);
        Family f = requireFamily(familyId);
        return Map.of(
                "id", f.getId(),
                "name", f.getName(),
                "creatorOpenid", f.getCreatorOpenid(),
                "currency", f.getCurrency(),
                "avatar", f.getAvatar() == null ? "" : f.getAvatar(),
                "memberCount", f.getMemberCount(),
                "status", f.getStatus());
    }

    @Transactional
    public Map<String, Object> patch(String openid, Long familyId, String name, String currency, String avatar) {
        FamilyMember m = activeMemberInFamily(openid, familyId);
        if (!"owner".equals(m.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅创建者可修改账本");
        }
        Family f = requireFamily(familyId);
        if (name != null) f.setName(name);
        if (currency != null) f.setCurrency(currency);
        if (avatar != null) f.setAvatar(avatar);
        f.setUpdatedAt(LocalDateTime.now());
        familyMapper.updateById(f);
        return Map.of(
                "id", f.getId(), "name", f.getName(), "currency", f.getCurrency(),
                "avatar", f.getAvatar() == null ? "" : f.getAvatar());
    }

    @Transactional
    public void dissolve(String openid, Long familyId) {
        FamilyMember me = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getStatus, "active"));
        if (me == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非本家庭成员");
        }
        if (!"owner".equals(me.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅创建者可解散家庭");
        }
        requireFamily(familyId);
        dissolveFamily(familyId);
    }

    /**
     * 彻底解散家庭：删除该家庭及其全部关联数据（账单、邀请、家庭自定义分类、
     * 提醒设置、预算、成员绑定）。系统预置分类（family_id=NULL）不受影响。
     * 解散与「退出后无成员」共用此方法，避免重复实现与行为不一致。
     */
    @Transactional
    public void dissolveFamily(Long familyId) {
        transactionMapper.delete(Wrappers.<TransactionRecord>lambdaQuery().eq(TransactionRecord::getFamilyId, familyId));
        invitationMapper.delete(Wrappers.<Invitation>lambdaQuery().eq(Invitation::getFamilyId, familyId));
        categoryMapper.delete(Wrappers.<Category>lambdaQuery().eq(Category::getFamilyId, familyId));
        reminderSettingMapper.delete(Wrappers.<ReminderSetting>lambdaQuery().eq(ReminderSetting::getFamilyId, familyId));
        budgetMapper.delete(Wrappers.<Budget>lambdaQuery().eq(Budget::getFamilyId, familyId));
        memberMapper.delete(Wrappers.<FamilyMember>lambdaQuery().eq(FamilyMember::getFamilyId, familyId));
        familyMapper.deleteById(familyId);
    }
}
