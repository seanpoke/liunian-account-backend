package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.JoinRequestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 加入家庭申请/审批（由 invitation 拆分而来，1 条邀请 → N 条申请）。
 */
@RestController
public class JoinRequestController {

    private final JoinRequestService joinRequestService;

    public JoinRequestController(JoinRequestService joinRequestService) {
        this.joinRequestService = joinRequestService;
    }

    public record RejectReq(String remark) {
    }

    @GetMapping("/family/{familyId}/join-requests")
    public R<List<Map<String, Object>>> pending(@PathVariable Long familyId) {
        return R.ok(joinRequestService.pending(UserContext.get(), familyId));
    }

    @PostMapping("/family/{familyId}/join-request/{requestId}/approve")
    public R<Void> approve(@PathVariable Long familyId, @PathVariable Long requestId) {
        joinRequestService.approve(UserContext.get(), familyId, requestId);
        return R.ok();
    }

    @PostMapping("/family/{familyId}/join-request/{requestId}/reject")
    public R<Void> reject(@PathVariable Long familyId, @PathVariable Long requestId,
                          @RequestBody(required = false) RejectReq req) {
        joinRequestService.reject(UserContext.get(), familyId, requestId, req == null ? null : req.remark());
        return R.ok();
    }
}
