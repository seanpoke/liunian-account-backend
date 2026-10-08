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

import java.util.List;
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

    @GetMapping("/family/{familyId}/pending")
    public R<List<Map<String, Object>>> pending(@PathVariable Long familyId) {
        return R.ok(invitationService.pending(UserContext.get(), familyId));
    }

    @PostMapping("/family/{familyId}/member/{memberId}/approve")
    public R<Void> approve(@PathVariable Long familyId, @PathVariable Long memberId) {
        invitationService.approve(UserContext.get(), familyId, memberId);
        return R.ok();
    }

    @PostMapping("/family/{familyId}/member/{memberId}/reject")
    public R<Void> reject(@PathVariable Long familyId, @PathVariable Long memberId) {
        invitationService.reject(UserContext.get(), familyId, memberId);
        return R.ok();
    }
}
