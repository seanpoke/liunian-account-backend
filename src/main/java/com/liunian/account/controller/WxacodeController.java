package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.InvitationService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class WxacodeController {

    private final InvitationService invitationService;

    public WxacodeController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @GetMapping("/wxacode")
    public R<Map<String, Object>> get(@RequestParam Long familyId) {
        Map<String, Object> r = invitationService.create(UserContext.get(), familyId, "qr");
        return R.ok(Map.of("qrImageUrl", r.get("qrImageUrl")));
    }
}
