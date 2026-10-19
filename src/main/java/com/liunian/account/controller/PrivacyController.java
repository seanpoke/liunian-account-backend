package com.liunian.account.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.R;
import com.liunian.account.common.TimeSupport;
import com.liunian.account.entity.User;
import com.liunian.account.mapper.UserMapper;
import com.liunian.account.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 隐私授权状态。privacy_agreed_at 保留：用于合规留痕，且换机/重装后不丢失。
 */
@RestController
public class PrivacyController {

    private final UserMapper userMapper;

    public PrivacyController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public record AgreeReq(boolean agreed) {
    }

    @GetMapping("/user/privacy")
    public R<Map<String, Object>> get() {
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenid, UserContext.get()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agreed", u != null && u.getPrivacyAgreedAt() != null);
        data.put("agreedAt", u != null && u.getPrivacyAgreedAt() != null ? u.getPrivacyAgreedAt().toString() : "");
        return R.ok(data);
    }

    @PostMapping("/user/privacy")
    public R<Map<String, Object>> agree(@RequestBody AgreeReq req) {
        if (!req.agreed()) {
            return R.ok(Map.of("agreed", false));
        }
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenid, UserContext.get()));
        if (u == null) {
            u = new User();
            u.setOpenid(UserContext.get());
            u.setCreatedAt(TimeSupport.now());
        }
        u.setPrivacyAgreedAt(TimeSupport.now());
        u.setUpdatedAt(TimeSupport.now());
        if (u.getId() == null) {
            userMapper.insert(u);
        } else {
            userMapper.updateById(u);
        }
        return R.ok(Map.of("agreed", true));
    }
}
