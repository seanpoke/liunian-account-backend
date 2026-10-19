package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户资料：昵称/头像的读取与更新（小程序「我的」页 chooseAvatar + 昵称输入框提交后调用）。
 */
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public record MeReq(String nickname, String avatar) {
    }

    @GetMapping("/user/me")
    public R<Map<String, Object>> me() {
        return R.ok(userService.me(UserContext.get()));
    }

    @PatchMapping("/user/me")
    public R<Void> update(@RequestBody MeReq req) {
        userService.updateProfile(UserContext.get(), req.nickname(), req.avatar());
        return R.ok();
    }
}
