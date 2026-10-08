package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.config.WxProperties;
import com.liunian.account.entity.Family;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.Invitation;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.FamilyMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.InvitationMapper;
import com.liunian.account.mapper.UserMapper;
import com.liunian.account.wx.WxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final InvitationMapper invitationMapper;
    private final FamilyMemberMapper memberMapper;
    private final FamilyMapper familyMapper;
    private final UserMapper userMapper;
    private final FamilyMemberService memberService;
    private final WxService wxService;
    private final WxProperties wx;

    private final int maxMembers;
    private final int expireHours;
    private final int blockHours;

    public InvitationService(InvitationMapper invitationMapper, FamilyMemberMapper memberMapper,
                             FamilyMapper familyMapper, UserMapper userMapper,
                             FamilyMemberService memberService, WxService wxService, WxProperties wx,
                             @Value("${invite.max-members:10}") int maxMembers,
                             @Value("${invite.expire-hours:168}") int expireHours,
                             @Value("${invite.block-hours:24}") int blockHours) {
        this.invitationMapper = invitationMapper;
        this.memberMapper = memberMapper;
        this.familyMapper = familyMapper;
        this.userMapper = userMapper;
        this.memberService = memberService;
        this.wxService = wxService;
        this.wx = wx;
        this.maxMembers = maxMembers;
        this.expireHours = expireHours;
        this.blockHours = blockHours;
    }

    @Transactional
    public Map<String, Object> create(String openid, Long familyId, String type) {
        memberService.requireOwner(openid, familyId);
        long activeCount = memberMapper.selectCount(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId).eq(FamilyMember::getStatus, "active"));
        if (activeCount >= maxMembers) {
            throw new BizException(ErrorCode.CONFLICT, "家庭人数已达上限");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        Invitation inv = new Invitation();
        inv.setFamilyId(familyId);
        inv.setInviterOpenid(openid);
        inv.setToken(token);
        inv.setType(type == null ? "share" : type);
        inv.setUsed(0);
        inv.setExpireAt(LocalDateTime.now().plusHours(expireHours));
        inv.setStatus("pending");
        inv.setCreatedAt(LocalDateTime.now());
        invitationMapper.insert(inv);

        String qrImageUrl = null;
        if ("qr".equals(inv.getType())) {
            qrImageUrl = wxService.getWxaCode(token, wx.getInvitePage());
        }
        return Map.of(
                "token", token,
                "expireAt", inv.getExpireAt().toString(),
                "qrImageUrl", qrImageUrl == null ? "" : qrImageUrl);
    }

    public Map<String, Object> getByToken(String token) {
        Invitation inv = invitationMapper.selectOne(Wrappers.<Invitation>lambdaQuery().eq(Invitation::getToken, token));
        if (inv == null || inv.getUsed() == 1 || !"pending".equals(inv.getStatus()) || inv.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.GONE, "邀请已失效");
        }
        Family f = familyMapper.selectById(inv.getFamilyId());
        FamilyMember inviter = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, inv.getFamilyId())
                .eq(FamilyMember::getOpenid, inv.getInviterOpenid())
                .eq(FamilyMember::getStatus, "active"));
        return Map.of(
                "familyName", f == null ? "" : f.getName(),
                "inviterNickname", inviter == null ? "" : inviter.getNickname(),
                "inviterAvatar", inviter == null || inviter.getAvatar() == null ? "" : inviter.getAvatar(),
                "status", inv.getStatus());
    }

    @Transactional
    public Map<String, Object> apply(String token, String openid, String nickname, String avatar) {
        Invitation inv = invitationMapper.selectOne(Wrappers.<Invitation>lambdaQuery().eq(Invitation::getToken, token));
        if (inv == null || inv.getUsed() == 1 || !"pending".equals(inv.getStatus()) || inv.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.GONE, "邀请已失效");
        }
        // 防骚扰：曾被拒绝且在拉黑窗口内
        long blocked = invitationMapper.selectCount(Wrappers.<Invitation>lambdaQuery()
                .eq(Invitation::getFamilyId, inv.getFamilyId())
                .eq(Invitation::getApplicantOpenid, openid)
                .eq(Invitation::getStatus, "rejected")
                .ge(Invitation::getReviewedAt, LocalDateTime.now().minusHours(blockHours)));
        if (blocked > 0) {
            throw new BizException(ErrorCode.CONFLICT, "申请被拒，请稍后再试");
        }
        FamilyMember dup = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, inv.getFamilyId())
                .eq(FamilyMember::getOpenid, openid)
                .in(FamilyMember::getStatus, "active", "pending"));
        if (dup != null) {
            throw new BizException(ErrorCode.CONFLICT, "你已在该家庭中");
        }

        FamilyMember existing = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, inv.getFamilyId()).eq(FamilyMember::getOpenid, openid));
        if (existing == null) {
            FamilyMember member = new FamilyMember();
            member.setFamilyId(inv.getFamilyId());
            member.setOpenid(openid);
            member.setNickname(nickname == null ? "新成员" : nickname);
            member.setAvatar(avatar);
            member.setRole("member");
            member.setStatus("pending");
            member.setSource("invite");
            member.setJoinedAt(null);
            member.setUpdatedAt(LocalDateTime.now());
            memberMapper.insert(member);
        } else {
            existing.setNickname(nickname == null ? "新成员" : nickname);
            existing.setAvatar(avatar);
            existing.setRole("member");
            existing.setStatus("pending");
            existing.setSource("invite");
            existing.setJoinedAt(null);
            existing.setUpdatedAt(LocalDateTime.now());
            memberMapper.updateById(existing);
        }

        inv.setUsed(1);
        inv.setApplicantOpenid(openid);
        invitationMapper.updateById(inv);

        notifyCreator(inv.getFamilyId(), inv.getInviterOpenid());
        return Map.of("status", "pending");
    }

    @Transactional
    public void revoke(String openid, String token) {
        Invitation inv = invitationMapper.selectOne(Wrappers.<Invitation>lambdaQuery().eq(Invitation::getToken, token));
        if (inv == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邀请不存在");
        }
        Family f = familyMapper.selectById(inv.getFamilyId());
        if (f == null || !openid.equals(f.getCreatorOpenid())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅创建者可撤销");
        }
        inv.setUsed(1);
        inv.setStatus("expired");
        invitationMapper.updateById(inv);
    }

    public List<Map<String, Object>> pending(String openid, Long familyId) {
        memberService.requireOwner(openid, familyId);
        List<FamilyMember> pending = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId).eq(FamilyMember::getStatus, "pending"));
        return pending.stream().map(m -> Map.<String, Object>of(
                "memberId", m.getId(),
                "nickname", m.getNickname(),
                "avatar", m.getAvatar() == null ? "" : m.getAvatar(),
                "applicantOpenid", m.getOpenid(),
                "applyAt", m.getUpdatedAt() == null ? "" : m.getUpdatedAt().toString(),
                "source", m.getSource())).toList();
    }

    @Transactional
    public void approve(String openid, Long familyId, Long memberId) {
        memberService.requireOwner(openid, familyId);
        FamilyMember member = memberMapper.selectById(memberId);
        if (member == null || !familyId.equals(member.getFamilyId()) || !"pending".equals(member.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "申请不存在");
        }
        member.setStatus("active");
        member.setJoinedAt(LocalDateTime.now());
        member.setUpdatedAt(LocalDateTime.now());
        memberMapper.updateById(member);

        Family f = familyMapper.selectById(familyId);
        if (f != null) {
            f.setMemberCount(f.getMemberCount() == null ? 1 : f.getMemberCount() + 1);
            f.setUpdatedAt(LocalDateTime.now());
            familyMapper.updateById(f);
        }
        invitationMapper.update(Wrappers.<Invitation>lambdaUpdate()
                .eq(Invitation::getFamilyId, familyId)
                .eq(Invitation::getApplicantOpenid, member.getOpenid())
                .eq(Invitation::getStatus, "pending")
                .set(Invitation::getStatus, "approved")
                .set(Invitation::getReviewedBy, openid)
                .set(Invitation::getReviewedAt, LocalDateTime.now()));

        notifyApplicant(member.getOpenid(), f);
    }

    @Transactional
    public void reject(String openid, Long familyId, Long memberId) {
        memberService.requireOwner(openid, familyId);
        FamilyMember member = memberMapper.selectById(memberId);
        if (member == null || !familyId.equals(member.getFamilyId()) || !"pending".equals(member.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "申请不存在");
        }
        member.setStatus("removed");
        member.setUpdatedAt(LocalDateTime.now());
        memberMapper.updateById(member);

        invitationMapper.update(Wrappers.<Invitation>lambdaUpdate()
                .eq(Invitation::getFamilyId, familyId)
                .eq(Invitation::getApplicantOpenid, member.getOpenid())
                .eq(Invitation::getStatus, "pending")
                .set(Invitation::getStatus, "rejected")
                .set(Invitation::getReviewedBy, openid)
                .set(Invitation::getReviewedAt, LocalDateTime.now()));
    }

    private void notifyCreator(Long familyId, String creatorOpenid) {
        try {
            Family f = familyMapper.selectById(familyId);
            wxService.sendSubscribeMessage(creatorOpenid, wx.getMsgTmplId(), wx.getInvitePage(),
                    Map.of("thing1", Map.of("value", "有新成员申请加入"),
                            "time2", Map.of("value", LocalDateTime.now().toString())));
        } catch (Exception e) {
            log.warn("通知创建者失败: {}", e.getMessage());
        }
    }

    private void notifyApplicant(String applicantOpenid, Family f) {
        try {
            wxService.sendSubscribeMessage(applicantOpenid, wx.getMsgTmplId(), "pages/index/index",
                    Map.of("thing1", Map.of("value", "你的加入申请已通过"),
                            "thing2", Map.of("value", f == null ? "家庭账本" : f.getName())));
        } catch (Exception e) {
            log.warn("通知申请人失败: {}", e.getMessage());
        }
    }
}
