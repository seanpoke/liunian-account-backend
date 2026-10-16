package com.liunian.account.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时自动读取 {@code ci/.env} 并把键值注册为最低优先级的属性源。
 *
 * <p>目的：IDE 直接 Run / {@code java -jar} 时无需手动配置环境变量，本项目约定的
 * {@code WX_APPID / WX_SECRET / JWT_SECRET ...} 即可自动生效。</p>
 *
 * <p>优先级保证（不影响 Docker）：
 * <ul>
 *   <li>仅当系统环境变量 / application.yml 未提供该值时才生效（{@code addLast} = 最低优先级）。</li>
 *   <li>文件不存在时静默跳过——Docker 镜像（.dockerignore 已排除 ci/）内无此文件，由
 *       docker-compose 的 {@code env_file} / {@code environment} 注入环境变量，互不干扰。</li>
 * </ul>
 *
 * <p>可通过 {@code -Denv.file=路径} 或环境变量 {@code ENV_FILE} 自定义位置，默认 {@code ci/.env}。</p>
 */
public class DotEnvInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(DotEnvInitializer.class);
    private static final String DEFAULT_ENV = "ci/.env";
    private static final String PROPERTY_SOURCE_NAME = "ciDotEnv";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();
        if (env.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        String location = env.getProperty("env.file", DEFAULT_ENV);
        Path path = Paths.get(location);
        if (!Files.exists(path)) {
            log.debug("[DotEnv] {} 不存在，跳过（可能运行在 Docker 中，已由 env_file 注入）", path);
            return;
        }
        try {
            Map<String, Object> props = new LinkedHashMap<>();
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int idx = trimmed.indexOf('=');
                    if (idx < 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, idx).trim();
                    String value = trimmed.substring(idx + 1).trim();
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        props.put(key, value);
                    }
                }
            }
            PropertySource<Map<String, Object>> source = new MapPropertySource(PROPERTY_SOURCE_NAME, props);
            env.getPropertySources().addLast(source);
            log.info("[DotEnv] 已从 {} 加载 {} 个变量（低优先级，不覆盖系统环境变量）", path, props.size());
        } catch (Exception e) {
            log.warn("[DotEnv] 读取 {} 失败，已忽略: {}", path, e.getMessage());
        }
    }
}
