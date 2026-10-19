package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", r.token());
        data.put("openid", r.openid());
        data.put("isMember", r.isMember());
        data.put("familyId", r.familyId() == null ? "" : r.familyId());
        data.put("privacyAgreed", r.privacyAgreed());
        return R.ok(data);
    }
}
