package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.InvitationService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    public record CreateReq(String type, Long familyId) {
    }

    public record ApplyReq(String nickname, String avatar) {
    }

    public record RevokeReq(@NotBlank String token) {
    }

    /**
     * 创建邀请：若本家庭存在未过期且剩余有效期 ≥ 6 小时的邀请则直接复用，否则新建（有效期 24 小时）。
     */
    @PostMapping("/invitation")
    public R<Map<String, Object>> create(@RequestBody CreateReq req) {
        return R.ok(invitationService.create(UserContext.get(), req.familyId(), req.type()));
    }

    @GetMapping("/invitation/{token}")
    public R<Map<String, Object>> get(@PathVariable String token) {
        return R.ok(invitationService.getByToken(token));
    }

    @PostMapping("/invitation/{token}/apply")
    public R<Map<String, Object>> apply(@PathVariable String token, @RequestBody ApplyReq req) {
        return R.ok(invitationService.apply(token, UserContext.get(), req.nickname(), req.avatar()));
    }

    @PostMapping("/invitation/revoke")
    public R<Void> revoke(@RequestBody RevokeReq req) {
        invitationService.revoke(UserContext.get(), req.token());
        return R.ok();
    }
}
