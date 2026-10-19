package com.liunian.account.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 统一时区工具。
 *
 * <p>全项目获取「当前时间」必须走这里，禁止直接使用 {@code LocalDateTime.now()} /
 * {@code LocalDate.now()} / {@code LocalTime.now()}。原因是这些方法取的是 JVM 默认时区，
 * 而容器（alpine 基础镜像）默认是 UTC，会导致落库时间比上海时间慢 8 小时、
 * {@code @Scheduled} 定时任务按 UTC 触发。</p>
 *
 * <p>时区由 {@code app.time-zone} 注入（默认 Asia/Shanghai），见 {@code TimeConfig}。</p>
 */
public final class TimeSupport {

    public static final String DEFAULT_ZONE = "Asia/Shanghai";

    private static volatile ZoneId zone = ZoneId.of(DEFAULT_ZONE);

    private TimeSupport() {
    }

    /** 由 TimeConfig 在容器启动最早阶段调用；其它地方不要调用。 */
    public static void setZone(String zoneId) {
        if (zoneId != null && !zoneId.isBlank()) {
            zone = ZoneId.of(zoneId.trim());
        }
    }

    public static ZoneId zone() {
        return zone;
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    public static LocalDate today() {
        return LocalDate.now(zone);
    }

    public static LocalTime nowTime() {
        return LocalTime.now(zone);
    }
}
