package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.entity.Budget;
import com.liunian.account.mapper.BudgetMapper;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.FamilyMemberService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class BudgetController {

    private final BudgetMapper budgetMapper;
    private final FamilyMemberService memberService;

    public BudgetController(BudgetMapper budgetMapper, FamilyMemberService memberService) {
        this.budgetMapper = budgetMapper;
        this.memberService = memberService;
    }

    public record PutReq(Long familyId, Integer amount) {
    }

    @GetMapping("/budget")
    public R<Map<String, Object>> get(@RequestParam Long familyId) {
        Budget b = budgetMapper.selectOne(Wrappers.<Budget>lambdaQuery().eq(Budget::getFamilyId, familyId));
        return R.ok(Map.of("amount", b == null ? 0 : (b.getAmount() == null ? 0 : b.getAmount())));
    }

    @PutMapping("/budget")
    public R<Map<String, Object>> put(@RequestBody PutReq req) {
        memberService.requireOwner(UserContext.get(), req.familyId());
        if (req.amount() == null || req.amount() < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "预算金额无效");
        }
        Budget existing = budgetMapper.selectOne(Wrappers.<Budget>lambdaQuery().eq(Budget::getFamilyId, req.familyId()));
        if (existing == null) {
            Budget b = new Budget();
            b.setFamilyId(req.familyId());
            b.setAmount(req.amount());
            b.setUpdatedBy(UserContext.get());
            b.setUpdatedAt(LocalDateTime.now());
            budgetMapper.insert(b);
        } else {
            existing.setAmount(req.amount());
            existing.setUpdatedBy(UserContext.get());
            existing.setUpdatedAt(LocalDateTime.now());
            budgetMapper.updateById(existing);
        }
        return R.ok(Map.of("amount", req.amount()));
    }
}
