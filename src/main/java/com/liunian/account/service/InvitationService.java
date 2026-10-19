package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.common.TimeSupport;
import com.liunian.account.config.WxProperties;
import com.liunian.account.entity.Family;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.Invitation;
import com.liunian.account.entity.JoinRequest;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.FamilyMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.InvitationMapper;
import com.liunian.account.mapper.JoinRequestMapper;
import com.liunian.account.mapper.UserMapper;
import com.liunian.account.security.UserContext;
import com.liunian.account.wx.WxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 家庭邀请（1:N）。
 *
 * <p>邀请有效性 = 未撤销 且 未过期，不再有 used 字段；一条邀请可被多人使用。
 * 申请/审批信息落在 {@link JoinRequest}，审批通过后才写入 family_member。</p>
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final InvitationMapper invitationMapper;
    private final JoinRequestMapper joinRequestMapper;
    private final FamilyMemberMapper memberMapper;
    private final FamilyMapper familyMapper;
    private final UserMapper userMapper;
    private final FamilyMemberService memberService;
    private final WxService wxService;
    private final WxProperties wx;

    private final int maxMembers;
    private final int expireHours;
    private final int renewThresholdHours;

    public InvitationService(InvitationMapper invitationMapper, JoinRequestMapper joinRequestMapper,
                             FamilyMemberMapper memberMapper, FamilyMapper familyMapper, UserMapper userMapper,
                             FamilyMemberService memberService, WxService wxService, WxProperties wx,
                             @Value("${invite.max-members:10}") int maxMembers,
                             @Value("${invite.expire-hours:24}") int expireHours,
                             @Value("${invite.renew-threshold-hours:6}") int renewThresholdHours) {
        this.invitationMapper = invitationMapper;
        this.joinRequestMapper = joinRequestMapper;
        this.memberMapper = memberMapper;
        this.familyMapper = familyMapper;
        this.userMapper = userMapper;
        this.memberService = memberService;
        this.wxService = wxService;
        this.wx = wx;
        this.maxMembers = maxMembers;
        this.expireHours = expireHours;
        this.renewThresholdHours = renewThresholdHours;
    }

    /**
     * 创建或复用邀请：
     * 同家庭同类型若存在「未撤销、未过期且剩余有效期 ≥ renewThresholdHours」的邀请，直接返回它；
     * 否则新建一条，有效期 expireHours（默认 24 小时）。
     */
    @Transactional
    public Map<String, Object> create(String openid, Long familyId, String type) {
        memberService.requireOwner(openid, familyId);
        if (memberService.countMembers(familyId) >= maxMembers) {
            throw new BizException(ErrorCode.CONFLICT, "家庭人数已达上限");
        }
        String inviteType = (type == null || type.isBlank()) ? "share" : type;
        LocalDateTime now = TimeSupport.now();

        Invitation inv = invitationMapper.selectOne(Wrappers.<Invitation>lambdaQuery()
                .eq(Invitation::getFamilyId, familyId)
                .eq(Invitation::getType, inviteType)
                .isNull(Invitation::getRevokedAt)
                .gt(Invitation::getExpireAt, now.plusHours(renewThresholdHours))
                .orderByDesc(Invitation::getExpireAt)
                .last("LIMIT 1"));

        boolean reused = inv != null;
        if (!reused) {
            inv = new Invitation();
            inv.setFamilyId(familyId);
            inv.setInviterOpenid(openid);
            inv.setToken(UUID.randomUUID().toString().replace("-", ""));
            inv.setType(inviteType);
            inv.setExpireAt(now.plusHours(expireHours));
            inv.setCreatedAt(now);
            inv.setUpdatedAt(now);
            invitationMapper.insert(inv);
            log.info("[INVITATION] 新建邀请 token={} familyId={} type={} expireAt={}",
                    inv.getToken(), familyId, inviteType, inv.getExpireAt());
        } else {
            log.info("[INVITATION] 复用未过期邀请 token={} familyId={} type={} expireAt={}",
                    inv.getToken(), familyId, inviteType, inv.getExpireAt());
        }

        String qrImageUrl = null;
        if ("qr".equals(inviteType)) {
            qrImageUrl = wxService.getWxaCode(inv.getToken(), wx.getInvitePage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", inv.getToken());
        result.put("type", inv.getType());
        result.put("expireAt", inv.getExpireAt().toString());
        result.put("reused", reused);
        result.put("qrImageUrl", qrImageUrl == null ? "" : qrImageUrl);
        return result;
    }

    /** 邀请落地页：返回家庭/邀请人信息与当前用户的三态申请状态。 */
    public Map<String, Object> getByToken(String token) {
        Invitation inv = requireValid(token);
        Family f = familyMapper.selectById(inv.getFamilyId());
        FamilyMember inviter = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, inv.getFamilyId())
                .eq(FamilyMember::getOpenid, inv.getInviterOpenid()));

        String openid = UserContext.get();
        boolean applied = false;
        String applicationStatus = "none";
        if (openid != null) {
            FamilyMember me = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                    .eq(FamilyMember::getFamilyId, inv.getFamilyId())
                    .eq(FamilyMember::getOpenid, openid));
            if (me != null) {
                applied = true;
                applicationStatus = "approved";
            } else {
                JoinRequest jr = joinRequestMapper.selectOne(Wrappers.<JoinRequest>lambdaQuery()
                        .eq(JoinRequest::getFamilyId, inv.getFamilyId())
                        .eq(JoinRequest::getApplicantOpenid, openid)
                        .orderByDesc(JoinRequest::getCreatedAt)
                        .last("LIMIT 1"));
                if (jr != null) {
                    switch (jr.getStatus() == null ? "" : jr.getStatus()) {
                        case "pending" -> {
                            applied = true;
                            applicationStatus = "pending";
                        }
                        case "rejected" -> {
                            applied = true;
                            applicationStatus = "rejected";
                        }
                        case "approved" -> {
                            applied = true;
                            applicationStatus = "approved";
                        }
                        default -> {
                            // expired / canceled 视为未申请，可再次申请
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("familyName", f == null ? "" : f.getName());
        result.put("inviterNickname", inviter == null || inviter.getNickname() == null ? "" : inviter.getNickname());
        result.put("inviterAvatar", inviter == null || inviter.getAvatar() == null ? "" : inviter.getAvatar());
        result.put("status", "active");
        result.put("familyId", inv.getFamilyId());
        result.put("expireAt", inv.getExpireAt().toString());
        result.put("applied", applied);
        result.put("applicationStatus", applicationStatus);
        return result;
    }

    /**
     * 提交加入申请：只写 join_request，不再写 family_member，也不再消耗邀请（1:N）。
     */
    @Transactional
    public Map<String, Object> apply(String token, String openid, String nickname, String avatar) {
        Invitation inv = requireValid(token);
        LocalDateTime now = TimeSupport.now();

        FamilyMember exists = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, inv.getFamilyId())
                .eq(FamilyMember::getOpenid, openid));
        if (exists != null) {
            throw new BizException(ErrorCode.CONFLICT, "你已在该家庭中");
        }

        String nick = resolveNickname(openid, nickname);
        String av = (avatar == null || avatar.isBlank()) ? null : avatar.trim();

        JoinRequest pending = joinRequestMapper.selectOne(Wrappers.<JoinRequest>lambdaQuery()
                .eq(JoinRequest::getFamilyId, inv.getFamilyId())
                .eq(JoinRequest::getApplicantOpenid, openid)
                .eq(JoinRequest::getStatus, "pending")
                .last("LIMIT 1"));

        if (pending != null) {
            // 重复提交：刷新昵称头像，保持待审批（幂等）
            pending.setNickname(nick);
            if (av != null) {
                pending.setAvatar(av);
            }
            pending.setInvitationId(inv.getId());
            pending.setUpdatedAt(now);
            joinRequestMapper.updateById(pending);
        } else {
            JoinRequest jr = new JoinRequest();
            jr.setFamilyId(inv.getFamilyId());
            jr.setInvitationId(inv.getId());
            jr.setApplicantOpenid(openid);
            jr.setNickname(nick);
            jr.setAvatar(av);
            jr.setChannel("invite");
            jr.setStatus("pending");
            jr.setCreatedAt(now);
            jr.setUpdatedAt(now);
            try {
                joinRequestMapper.insert(jr);
            } catch (DuplicateKeyException e) {
                // 并发重复提交：唯一键 (family_id, applicant_openid, status) 兜底
                log.info("[JOIN] 重复申请已被唯一键拦截 openid={} familyId={}", openid, inv.getFamilyId());
            }
        }

        // 兜底回填 user 资料：成员被移除后历史账单仍能显示昵称
        upsertUserProfile(openid, nickname, avatar);
        notifyCreator(inv.getFamilyId(), inv.getInviterOpenid());
        return Map.of("status", "pending");
    }

    /** 撤销邀请：置 revoked_at，并把该邀请下待审批申请置为 expired。 */
    @Transactional
    public void revoke(String openid, String token) {
        Invitation inv = invitationMapper.selectOne(Wrappers.<Invitation>lambdaQuery()
                .eq(Invitation::getToken, token));
        if (inv == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邀请不存在");
        }
        memberService.requireOwner(openid, inv.getFamilyId());
        LocalDateTime now = TimeSupport.now();
        inv.setRevokedAt(now);
        inv.setUpdatedAt(now);
        invitationMapper.updateById(inv);

        joinRequestMapper.update(null, Wrappers.<JoinRequest>lambdaUpdate()
                .eq(JoinRequest::getInvitationId, inv.getId())
                .eq(JoinRequest::getStatus, "pending")
                .set(JoinRequest::getStatus, "expired")
                .set(JoinRequest::getUpdatedAt, now));
    }

    private Invitation requireValid(String token) {
        Invitation inv = invitationMapper.selectOne(Wrappers.<Invitation>lambdaQuery()
                .eq(Invitation::getToken, token));
        if (inv == null || inv.getRevokedAt() != null || inv.getExpireAt() == null
                || !inv.getExpireAt().isAfter(TimeSupport.now())) {
            throw new BizException(ErrorCode.GONE, "邀请已失效");
        }
        return inv;
    }

    private String resolveNickname(String openid, String nickname) {
        if (nickname != null && !nickname.isBlank()) {
            return nickname.trim();
        }
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenid, openid));
        if (u != null && u.getNickname() != null && !u.getNickname().isBlank()) {
            return u.getNickname();
        }
        return "新成员";
    }

    private void upsertUserProfile(String openid, String nickname, String avatar) {
        boolean hasNick = nickname != null && !nickname.isBlank();
        boolean hasAvatar = avatar != null && !avatar.isBlank();
        if (!hasNick && !hasAvatar) {
            return;
        }
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenid, openid));
        if (u == null) {
            u = new User();
            u.setOpenid(openid);
            if (hasNick) u.setNickname(nickname.trim());
            if (hasAvatar) u.setAvatar(avatar.trim());
            u.setCreatedAt(TimeSupport.now());
            u.setUpdatedAt(TimeSupport.now());
            userMapper.insert(u);
        } else {
            boolean changed = false;
            if (hasNick && !nickname.trim().equals(u.getNickname())) {
                u.setNickname(nickname.trim());
                changed = true;
            }
            if (hasAvatar && !avatar.trim().equals(u.getAvatar())) {
                u.setAvatar(avatar.trim());
                changed = true;
            }
            if (changed) {
                u.setUpdatedAt(TimeSupport.now());
                userMapper.updateById(u);
            }
        }
    }

    private void notifyCreator(Long familyId, String creatorOpenid) {
        try {
            wxService.sendSubscribeMessage(creatorOpenid, wx.getMsgTmplId(), wx.getInvitePage(),
                    Map.of("thing1", Map.of("value", "有新成员申请加入"),
                            "time2", Map.of("value", TimeSupport.now().toString())));
        } catch (Exception e) {
            log.warn("通知创建者失败: {}", e.getMessage());
        }
    }
}
