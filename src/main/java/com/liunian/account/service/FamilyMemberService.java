package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.entity.Family;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.mapper.FamilyMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.TransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class FamilyMemberService {

    private final FamilyMemberMapper memberMapper;
    private final FamilyMapper familyMapper;
    private final TransactionMapper transactionMapper;

    public FamilyMemberService(FamilyMemberMapper memberMapper, FamilyMapper familyMapper, TransactionMapper transactionMapper) {
        this.memberMapper = memberMapper;
        this.familyMapper = familyMapper;
        this.transactionMapper = transactionMapper;
    }

    public FamilyMember requireActive(String openid) {
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid).eq(FamilyMember::getStatus, "active"));
        if (m == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非家庭成员");
        }
        return m;
    }

    public FamilyMember requireActive(String openid, Long familyId) {
        FamilyMember m = requireActive(openid);
        if (!familyId.equals(m.getFamilyId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "非本家庭成员");
        }
        return m;
    }

    public FamilyMember requireOwner(String openid, Long familyId) {
        FamilyMember m = requireActive(openid);
        if (!familyId.equals(m.getFamilyId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "非本家庭成员");
        }
        if (!"owner".equals(m.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅创建者可操作");
        }
        return m;
    }

    public List<Map<String, Object>> list(String openid, Long familyId) {
        requireActive(openid);
        List<FamilyMember> members = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getStatus, "active")
                .orderByDesc(FamilyMember::getJoinedAt));
        return members.stream().map(m -> {
            long monthCount = transactionMapper.selectCount(Wrappers.<com.liunian.account.entity.TransactionRecord>lambdaQuery()
                    .eq(com.liunian.account.entity.TransactionRecord::getFamilyId, familyId)
                    .eq(com.liunian.account.entity.TransactionRecord::getOpenid, m.getOpenid())
                    .apply("date >= DATE_FORMAT(NOW(), '%Y-%m-01')"));
            return Map.<String, Object>of(
                    "memberId", m.getId(),
                    "openid", m.getOpenid(),
                    "nickname", m.getNickname(),
                    "avatar", m.getAvatar() == null ? "" : m.getAvatar(),
                    "role", m.getRole(),
                    "status", m.getStatus(),
                    "joinedAt", m.getJoinedAt() == null ? "" : m.getJoinedAt().toString(),
                    "monthCount", monthCount);
        }).toList();
    }

    @Transactional
    public void remove(String ownerOpenid, Long familyId, Long memberId) {
        requireOwner(ownerOpenid, familyId);
        FamilyMember target = memberMapper.selectById(memberId);
        if (target == null || !familyId.equals(target.getFamilyId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "成员不存在");
        }
        if ("owner".equals(target.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能移除创建者");
        }
        target.setStatus("removed");
        target.setUpdatedAt(LocalDateTime.now());
        memberMapper.updateById(target);
        decCount(familyId);
    }

    @Transactional
    public void transfer(String ownerOpenid, Long familyId, String toOpenid) {
        FamilyMember owner = requireOwner(ownerOpenid, familyId);
        FamilyMember next = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getOpenid, toOpenid)
                .eq(FamilyMember::getStatus, "active"));
        if (next == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标成员不存在");
        }
        owner.setRole("member");
        owner.setUpdatedAt(LocalDateTime.now());
        next.setRole("owner");
        next.setUpdatedAt(LocalDateTime.now());
        memberMapper.updateById(owner);
        memberMapper.updateById(next);
    }

    @Transactional
    public void quit(String openid, Long familyId) {
        FamilyMember me = requireActive(openid);
        if (!familyId.equals(me.getFamilyId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "非本家庭成员");
        }
        if ("owner".equals(me.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "创建者须先转让身份");
        }
        me.setStatus("removed");
        me.setUpdatedAt(LocalDateTime.now());
        memberMapper.updateById(me);
        decCount(familyId);
    }

    public void updateMe(String openid, Long familyId, String nickname, String avatar) {
        FamilyMember me = requireActive(openid);
        if (!familyId.equals(me.getFamilyId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "非本家庭成员");
        }
        if (nickname != null) me.setNickname(nickname);
        if (avatar != null) me.setAvatar(avatar);
        me.setUpdatedAt(LocalDateTime.now());
        memberMapper.updateById(me);
    }

    private void decCount(Long familyId) {
        Family f = familyMapper.selectById(familyId);
        if (f != null && f.getMemberCount() != null && f.getMemberCount() > 0) {
            f.setMemberCount(f.getMemberCount() - 1);
            f.setUpdatedAt(LocalDateTime.now());
            familyMapper.updateById(f);
        }
    }
}
