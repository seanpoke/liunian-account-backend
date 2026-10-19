package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.common.TimeSupport;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.UserMapper;
import com.liunian.account.security.JwtUtil;
import com.liunian.account.wx.WxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final FamilyMemberMapper memberMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;
    private final WxService wxService;

    public AuthService(UserMapper userMapper, FamilyMemberMapper memberMapper, JwtUtil jwtUtil,
                       StringRedisTemplate redis, WxService wxService) {
        this.userMapper = userMapper;
        this.memberMapper = memberMapper;
        this.jwtUtil = jwtUtil;
        this.redis = redis;
        this.wxService = wxService;
    }

    public record LoginResult(String token, String openid, boolean isMember, Long familyId, boolean privacyAgreed) {
    }

    /**
     * 微信登录：code2Session 换 openid → upsert user（昵称/头像以最新授权值为准）→ 签发 JWT。
     * 返回 privacyAgreed 供小程序首次进入时决定是否弹隐私授权，省掉一次额外请求。
     */
    public LoginResult login(String code, String unionid, String nickname, String avatar) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 不能为空");
        }
        String openid = wxService.code2Session(code);
        if (openid == null || openid.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "获取 openid 失败");
        }
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenid, openid));
        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            user.setUnionid(unionid);
            user.setNickname(nickname);
            user.setAvatar(avatar);
            user.setCreatedAt(TimeSupport.now());
            user.setUpdatedAt(TimeSupport.now());
            userMapper.insert(user);
        } else {
            boolean changed = false;
            if (nickname != null && !nickname.isBlank() && !nickname.equals(user.getNickname())) {
                user.setNickname(nickname);
                changed = true;
            }
            if (avatar != null && !avatar.isBlank() && !avatar.equals(user.getAvatar())) {
                user.setAvatar(avatar);
                changed = true;
            }
            if (unionid != null && !unionid.isBlank() && !unionid.equals(user.getUnionid())) {
                user.setUnionid(unionid);
                changed = true;
            }
            if (changed) {
                user.setUpdatedAt(TimeSupport.now());
                userMapper.updateById(user);
            }
        }

        String token = jwtUtil.generate(openid);
        String jti = jwtUtil.jti(token);
        redis.opsForValue().set("auth:jti:" + jti, openid, Duration.ofHours(jwtUtil.getExpireHours()));

        // 允许多家庭：取最早加入的家庭作为默认家庭，不能再用 selectOne（多家庭会抛 TooManyResults）
        List<FamilyMember> members = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .orderByAsc(FamilyMember::getJoinedAt));
        boolean isMember = !members.isEmpty();
        Long familyId = isMember ? members.get(0).getFamilyId() : null;
        boolean privacyAgreed = user.getPrivacyAgreedAt() != null;
        log.info("[LOGIN] openid={} isMember={} familyId={} privacyAgreed={}", openid, isMember, familyId, privacyAgreed);
        return new LoginResult(token, openid, isMember, familyId, privacyAgreed);
    }
}
