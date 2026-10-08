package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.entity.Family;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.FamilyMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class FamilyService {

    private final FamilyMapper familyMapper;
    private final FamilyMemberMapper memberMapper;
    private final UserMapper userMapper;

    public FamilyService(FamilyMapper familyMapper, FamilyMemberMapper memberMapper, UserMapper userMapper) {
        this.familyMapper = familyMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
    }

    private FamilyMember activeMember(String openid) {
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid).eq(FamilyMember::getStatus, "active"));
        if (m == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非家庭成员");
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
        FamilyMember exist = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid).in(FamilyMember::getStatus, "active", "pending"));
        if (exist != null) {
            throw new BizException(ErrorCode.CONFLICT, "你已有所属家庭");
        }
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

    public Map<String, Object> get(String openid, Long familyId) {
        activeMember(openid);
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
        FamilyMember m = activeMember(openid);
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
}
