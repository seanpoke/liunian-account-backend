package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) String period,
                                        @RequestParam(required = false) Integer month,
                                        @RequestParam(required = false) Integer year,
                                        @RequestParam(required = false) String start,
                                        @RequestParam(required = false) String end,
                                        @RequestParam(required = false) String member) {
        return R.ok(statsService.stats(UserContext.get(), period, month, year,
                start == null ? null : LocalDate.parse(start),
                end == null ? null : LocalDate.parse(end), member));
    }
}
