package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.common.TimeSupport;
import com.liunian.account.config.WxProperties;
import com.liunian.account.entity.Family;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.JoinRequest;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.FamilyMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.JoinRequestMapper;
import com.liunian.account.mapper.UserMapper;
import com.liunian.account.wx.WxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加入家庭申请/审批。
 *
 * <p>pending 的重复提交由数据库生成列唯一键兜底；审批通过后才写入 family_member。</p>
 */
@Service
public class JoinRequestService {

    private static final Logger log = LoggerFactory.getLogger(JoinRequestService.class);

    private final JoinRequestMapper joinRequestMapper;
    private final FamilyMemberMapper memberMapper;
    private final FamilyMapper familyMapper;
    private final UserMapper userMapper;
    private final FamilyMemberService memberService;
    private final WxService wxService;
    private final WxProperties wx;
    private final int maxMembers;

    public JoinRequestService(JoinRequestMapper joinRequestMapper, FamilyMemberMapper memberMapper,
                              FamilyMapper familyMapper, UserMapper userMapper,
                              FamilyMemberService memberService, WxService wxService, WxProperties wx,
                              @Value("${invite.max-members:10}") int maxMembers) {
        this.joinRequestMapper = joinRequestMapper;
        this.memberMapper = memberMapper;
        this.familyMapper = familyMapper;
        this.userMapper = userMapper;
        this.memberService = memberService;
        this.wxService = wxService;
        this.wx = wx;
        this.maxMembers = maxMembers;
    }

    public List<Map<String, Object>> pending(String openid, Long familyId) {
        memberService.requireOwner(openid, familyId);
        List<JoinRequest> list = joinRequestMapper.selectList(Wrappers.<JoinRequest>lambdaQuery()
                .eq(JoinRequest::getFamilyId, familyId)
                .eq(JoinRequest::getStatus, "pending")
                .orderByAsc(JoinRequest::getCreatedAt));
        return list.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requestId", r.getId());
            m.put("nickname", r.getNickname());
            m.put("avatar", r.getAvatar() == null ? "" : r.getAvatar());
            m.put("applicantOpenid", r.getApplicantOpenid());
            m.put("applyAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString());
            m.put("channel", r.getChannel());
            return m;
        }).toList();
    }

    @Transactional
    public void approve(String openid, Long familyId, Long requestId) {
        memberService.requireOwner(openid, familyId);
        JoinRequest jr = requirePending(familyId, requestId);
        if (memberService.countMembers(familyId) >= maxMembers) {
            throw new BizException(ErrorCode.CONFLICT, "家庭人数已达上限");
        }
        LocalDateTime now = TimeSupport.now();
        String nickname = resolveNickname(jr);

        // 审批通过：写入/刷新真实成员（退出后重新加入时按幂等 upsert 处理）
        FamilyMember member = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getOpenid, jr.getApplicantOpenid()));
        if (member == null) {
            member = new FamilyMember();
            member.setFamilyId(familyId);
            member.setOpenid(jr.getApplicantOpenid());
            member.setNickname(nickname);
            member.setAvatar(jr.getAvatar());
            member.setRole("member");
            member.setJoinedAt(now);
            member.setUpdatedAt(now);
            memberMapper.insert(member);
        } else {
            member.setNickname(nickname);
            if (jr.getAvatar() != null) member.setAvatar(jr.getAvatar());
            member.setUpdatedAt(now);
            memberMapper.updateById(member);
        }

        jr.setStatus("approved");
        jr.setReviewedBy(openid);
        jr.setReviewedAt(now);
        jr.setUpdatedAt(now);
        joinRequestMapper.updateById(jr);

        // 同一申请人在该家庭的其他待审申请作废，避免重复审批
        joinRequestMapper.update(null, Wrappers.<JoinRequest>lambdaUpdate()
                .eq(JoinRequest::getFamilyId, familyId)
                .eq(JoinRequest::getApplicantOpenid, jr.getApplicantOpenid())
                .eq(JoinRequest::getStatus, "pending")
                .ne(JoinRequest::getId, jr.getId())
                .set(JoinRequest::getStatus, "canceled")
                .set(JoinRequest::getUpdatedAt, now));

        // 兜底回填 user 资料
        upsertUserProfile(jr.getApplicantOpenid(), nickname, jr.getAvatar());

        Family f = familyMapper.selectById(familyId);
        notifyApplicant(jr.getApplicantOpenid(), f);
        log.info("[JOIN] 审批通过 requestId={} familyId={} applicant={} reviewer={}",
                requestId, familyId, jr.getApplicantOpenid(), openid);
    }

    @Transactional
    public void reject(String openid, Long familyId, Long requestId, String remark) {
        memberService.requireOwner(openid, familyId);
        JoinRequest jr = requirePending(familyId, requestId);
        LocalDateTime now = TimeSupport.now();
        jr.setStatus("rejected");
        jr.setReviewedBy(openid);
        jr.setReviewedAt(now);
        jr.setRemark(remark);
        jr.setUpdatedAt(now);
        joinRequestMapper.updateById(jr);
        log.info("[JOIN] 审批拒绝 requestId={} familyId={} applicant={} reviewer={}",
                requestId, familyId, jr.getApplicantOpenid(), openid);
    }

    private JoinRequest requirePending(Long familyId, Long requestId) {
        JoinRequest jr = joinRequestMapper.selectById(requestId);
        if (jr == null || !familyId.equals(jr.getFamilyId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "申请不存在");
        }
        if (!"pending".equals(jr.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "申请已处理");
        }
        return jr;
    }

    private String resolveNickname(JoinRequest jr) {
        if (jr.getNickname() != null && !jr.getNickname().isBlank()) {
            return jr.getNickname();
        }
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenid, jr.getApplicantOpenid()));
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
            return;
        }
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
