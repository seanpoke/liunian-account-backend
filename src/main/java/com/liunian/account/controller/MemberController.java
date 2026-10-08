package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.FamilyMemberService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class MemberController {

    private final FamilyMemberService memberService;

    public MemberController(FamilyMemberService memberService) {
        this.memberService = memberService;
    }

    public record TransferReq(@NotBlank String toOpenid) {
    }

    public record MeReq(String nickname, String avatar) {
    }

    @GetMapping("/family/{familyId}/members")
    public R<List<Map<String, Object>>> members(@PathVariable Long familyId) {
        return R.ok(memberService.list(UserContext.get(), familyId));
    }

    @DeleteMapping("/family/{familyId}/member/{memberId}")
    public R<Void> remove(@PathVariable Long familyId, @PathVariable Long memberId) {
        memberService.remove(UserContext.get(), familyId, memberId);
        return R.ok();
    }

    @PostMapping("/family/{familyId}/transfer")
    public R<Void> transfer(@PathVariable Long familyId, @RequestBody TransferReq req) {
        memberService.transfer(UserContext.get(), familyId, req.toOpenid());
        return R.ok();
    }

    @PostMapping("/family/{familyId}/quit")
    public R<Void> quit(@PathVariable Long familyId) {
        memberService.quit(UserContext.get(), familyId);
        return R.ok();
    }

    @PatchMapping("/family/{familyId}/member/me")
    public R<Void> updateMe(@PathVariable Long familyId, @RequestBody MeReq req) {
        memberService.updateMe(UserContext.get(), familyId, req.nickname(), req.avatar());
        return R.ok();
    }
}
