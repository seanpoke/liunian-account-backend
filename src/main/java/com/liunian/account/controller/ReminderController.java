package com.liunian.account.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.R;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.entity.ReminderSetting;
import com.liunian.account.mapper.ReminderSettingMapper;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.FamilyMemberService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.Map;

@RestController
public class ReminderController {

    private final ReminderSettingMapper reminderMapper;
    private final FamilyMemberService memberService;

    public ReminderController(ReminderSettingMapper reminderMapper, FamilyMemberService memberService) {
        this.reminderMapper = reminderMapper;
        this.memberService = memberService;
    }

    public record PutReq(Long familyId, Integer enabled, String time, String tmplId) {
    }

    @GetMapping("/reminder")
    public R<Map<String, Object>> get(@RequestParam Long familyId) {
        memberService.requireActive(UserContext.get(), familyId);
        ReminderSetting r = reminderMapper.selectOne(Wrappers.<ReminderSetting>lambdaQuery()
                .eq(ReminderSetting::getFamilyId, familyId).eq(ReminderSetting::getOpenid, UserContext.get()));
        if (r == null) {
            return R.ok(Map.of("enabled", 0, "time", "", "tmplId", ""));
        }
        return R.ok(Map.of(
                "enabled", r.getEnabled() == null ? 0 : r.getEnabled(),
                "time", r.getTime() == null ? "" : r.getTime().toString(),
                "tmplId", r.getTmplId() == null ? "" : r.getTmplId()));
    }

    @PutMapping("/reminder")
    public R<Map<String, Object>> put(@RequestBody PutReq req) {
        memberService.requireActive(UserContext.get(), req.familyId());
        ReminderSetting r = reminderMapper.selectOne(Wrappers.<ReminderSetting>lambdaQuery()
                .eq(ReminderSetting::getFamilyId, req.familyId()).eq(ReminderSetting::getOpenid, UserContext.get()));
        if (r == null) {
            r = new ReminderSetting();
            r.setFamilyId(req.familyId());
            r.setOpenid(UserContext.get());
            r.setSubscribeCount(0);
        }
        if (req.enabled() != null) r.setEnabled(req.enabled());
        if (req.time() != null) r.setTime(LocalTime.parse(req.time()));
        if (req.tmplId() != null) r.setTmplId(req.tmplId());
        if (r.getId() == null) {
            reminderMapper.insert(r);
        } else {
            reminderMapper.updateById(r);
        }
        return R.ok(Map.of(
                "enabled", r.getEnabled() == null ? 0 : r.getEnabled(),
                "time", r.getTime() == null ? "" : r.getTime().toString(),
                "tmplId", r.getTmplId() == null ? "" : r.getTmplId()));
    }
}
