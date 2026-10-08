package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.config.WxProperties;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.UserMapper;
import com.liunian.account.security.JwtUtil;
import com.liunian.account.wx.WxService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final FamilyMemberMapper memberMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;
    private final WxProperties wx;
    private final WxService wxService;

    public AuthService(UserMapper userMapper, FamilyMemberMapper memberMapper, JwtUtil jwtUtil,
                       StringRedisTemplate redis, WxProperties wx, WxService wxService) {
        this.userMapper = userMapper;
        this.memberMapper = memberMapper;
        this.jwtUtil = jwtUtil;
        this.redis = redis;
        this.wx = wx;
        this.wxService = wxService;
    }

    public record LoginResult(String token, String openid, boolean isMember, Long familyId) {
    }

    public LoginResult login(String code, String unionid, String nickname, String avatar) {
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
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);
        } else {
            boolean changed = false;
            if (nickname != null && !nickname.equals(user.getNickname())) {
                user.setNickname(nickname);
                changed = true;
            }
            if (avatar != null && !avatar.equals(user.getAvatar())) {
                user.setAvatar(avatar);
                changed = true;
            }
            if (unionid != null && !unionid.equals(user.getUnionid())) {
                user.setUnionid(unionid);
                changed = true;
            }
            if (changed) {
                user.setUpdatedAt(LocalDateTime.now());
                userMapper.updateById(user);
            }
        }

        String token = jwtUtil.generate(openid);
        String jti = jwtUtil.jti(token);
        redis.opsForValue().set("auth:jti:" + jti, openid, Duration.ofHours(jwtUtil.getExpireHours()));

        FamilyMember member = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .in(FamilyMember::getStatus, "active", "pending"));
        boolean isMember = member != null;
        Long familyId = isMember ? member.getFamilyId() : null;
        return new LoginResult(token, openid, isMember, familyId);
    }
}
