package com.liunian.account.config;

import com.liunian.account.common.TimeSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 时区统一配置。
 *
 * <p>Bean 构造阶段即锁定 JVM 默认时区：影响 {@code @Scheduled} 的 cron 解析、日志时间、
 * JDBC 驱动对 Timestamp 的解释等；业务时间统一走 {@link TimeSupport}。</p>
 */
@Configuration
public class TimeConfig {

    public TimeConfig(@Value("${app.time-zone:Asia/Shanghai}") String zone) {
        TimeSupport.setZone(zone);
        // TimeZone.setDefault 使用的是 JDK 自带 tzdb，不依赖操作系统的 /usr/share/zoneinfo
        TimeZone.setDefault(TimeZone.getTimeZone(TimeSupport.zone()));
    }
}
