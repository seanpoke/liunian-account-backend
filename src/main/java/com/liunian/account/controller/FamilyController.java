package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.FamilyService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class FamilyController {

    private final FamilyService familyService;

    public FamilyController(FamilyService familyService) {
        this.familyService = familyService;
    }

    public record CreateReq(@NotBlank String name) {
    }

    public record PatchReq(String name, String currency, String avatar) {
    }

    @PostMapping("/family")
    public R<Map<String, Object>> create(@RequestBody CreateReq req) {
        Long familyId = familyService.create(UserContext.get(), req.name());
        return R.ok(Map.of("familyId", familyId));
    }

    @GetMapping("/family/{familyId}")
    public R<Map<String, Object>> get(@PathVariable Long familyId) {
        return R.ok(familyService.get(UserContext.get(), familyId));
    }

    @PatchMapping("/family/{familyId}")
    public R<Map<String, Object>> patch(@PathVariable Long familyId, @RequestBody PatchReq req) {
        return R.ok(familyService.patch(UserContext.get(), familyId, req.name(), req.currency(), req.avatar()));
    }
}
