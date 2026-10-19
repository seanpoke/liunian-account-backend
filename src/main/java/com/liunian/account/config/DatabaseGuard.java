package com.liunian.account.config;

import com.liunian.account.common.TimeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库环境守卫：启动后校验实际连接的 schema。
 *
 * <p>背景：开发环境必须使用 {@code liunian_account_dev}，{@code liunian_account} 是已有真实用户的
 * 生产库。历史上曾因连接串写错 + {@code createDatabaseIfNotExist} 静默落到 root 默认 schema，
 * 造成其他项目库被初始化脚本污染。</p>
 *
 * <p>规则：</p>
 * <ol>
 *   <li>实际库名必须与 {@code app.db.expected} 一致，否则拒绝启动；</li>
 *   <li>实际库名是生产库 {@code liunian_account} 时，必须显式 {@code DB_ALLOW_PROD=true}，否则拒绝启动。</li>
 * </ol>
 */
@Component
public class DatabaseGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseGuard.class);
    private static final String PROD_SCHEMA = "liunian_account";

    private final JdbcTemplate jdbcTemplate;
    private final String expected;
    private final boolean allowProd;

    public DatabaseGuard(JdbcTemplate jdbcTemplate,
                         @Value("${app.db.expected:liunian_account_dev}") String expected,
                         @Value("${app.db.allow-prod:false}") boolean allowProd) {
        this.jdbcTemplate = jdbcTemplate;
        this.expected = expected == null ? "" : expected.trim();
        this.allowProd = allowProd;
    }

    @Override
    public void run(ApplicationArguments args) {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (actual == null || actual.isBlank()) {
            throw new IllegalStateException("[DB-GUARD] 无法识别当前数据库（SELECT DATABASE() 返回空），已阻止启动");
        }
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalStateException("[DB-GUARD] 数据库不匹配：期望 " + expected + "，实际 " + actual
                    + "。已阻止启动，避免误操作生产库 " + PROD_SCHEMA + "。");
        }
        if (PROD_SCHEMA.equalsIgnoreCase(actual)) {
            if (!allowProd) {
                throw new IllegalStateException("[DB-GUARD] 检测到生产库 " + PROD_SCHEMA
                        + "，已阻止启动。确认要连接生产库请显式设置 DB_ALLOW_PROD=true。");
            }
            log.warn("[DB-GUARD] 已连接生产库 {}（DB_ALLOW_PROD=true），请确认这是预期行为", actual);
            return;
        }
        log.info("[DB-GUARD] 已连接开发库 {}，时区 {}", actual, TimeSupport.zone().getId());
    }
}
