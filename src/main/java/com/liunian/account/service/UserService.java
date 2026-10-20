package com.liunian.account.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.TimeSupport;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户资料。
 *
 * <p>约定：user.nickname/avatar 是「全局身份」，family_member.nickname/avatar 是「家庭内显示名」，
 * 后者默认取自前者；用户在此更新时同步刷新其所有家庭下的显示名，避免「我的」与成员列表不一致。</p>
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final FamilyMemberMapper memberMapper;

    public UserService(UserMapper userMapper, FamilyMemberMapper memberMapper) {
        this.userMapper = userMapper;
        this.memberMapper = memberMapper;
    }

    public Map<String, Object> me(String openid) {
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenid, openid));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openid", openid);
        data.put("nickname", u == null || u.getNickname() == null ? "" : u.getNickname());
        data.put("avatar", u == null || u.getAvatar() == null ? "" : u.getAvatar());
        data.put("privacyAgreed", u != null && u.getPrivacyAgreedAt() != null);
        return data;
    }

    @Transactional
    public void updateProfile(String openid, String nickname, String avatar) {
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenid, openid));
        if (u == null) {
            u = new User();
            u.setOpenid(openid);
            u.setCreatedAt(TimeSupport.now());
        }
        boolean nickChanged = nickname != null && !nickname.isBlank();
        // avatar：null=不修改；空字符串=清空头像（存空串，兼容 MyBatis-Plus 忽略 null 的更新策略）
        boolean avatarChanged = avatar != null;
        String newAvatar = avatarChanged ? avatar.trim() : null;
        if (nickChanged) {
            u.setNickname(nickname.trim());
        }
        if (avatarChanged) {
            u.setAvatar(newAvatar);
        }
        u.setUpdatedAt(TimeSupport.now());
        if (u.getId() == null) {
            userMapper.insert(u);
        } else {
            userMapper.updateById(u);
        }

        if (!nickChanged && !avatarChanged) {
            return;
        }
        LambdaUpdateWrapper<FamilyMember> w = Wrappers.<FamilyMember>lambdaUpdate()
                .eq(FamilyMember::getOpenid, openid);
        if (nickChanged) {
            w.set(FamilyMember::getNickname, nickname.trim());
        }
        if (avatarChanged) {
            w.set(FamilyMember::getAvatar, newAvatar);
        }
        w.set(FamilyMember::getUpdatedAt, TimeSupport.now());
        memberMapper.update(null, w);
    }
}
