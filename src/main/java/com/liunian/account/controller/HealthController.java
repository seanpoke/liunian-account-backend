package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.common.TimeSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。
 *
 * <p>额外返回 serverNow 与 dbNow：两端口径不一致时说明时区配置被破坏，便于用一条请求定位。</p>
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("time", System.currentTimeMillis());
        data.put("zone", TimeSupport.zone().getId());
        data.put("serverNow", TimeSupport.now().toString());
        try {
            data.put("dbNow", jdbcTemplate.queryForObject("SELECT DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s')", String.class));
        } catch (Exception e) {
            data.put("dbNow", "unavailable");
        }
        return R.ok(data);
    }
}
