package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginReq(
            @NotBlank String code,
            String unionid,
            String nickname,
            String avatar) {
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginReq req) {
        AuthService.LoginResult r = authService.login(req.code(), req.unionid(), req.nickname(), req.avatar());
        return R.ok(Map.of(
                "token", r.token(),
                "openid", r.openid(),
                "isMember", r.isMember(),
                "familyId", r.familyId() == null ? "" : r.familyId()));
    }
}
