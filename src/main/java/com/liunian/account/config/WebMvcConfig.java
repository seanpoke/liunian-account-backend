package com.liunian.account.config;

import com.liunian.account.security.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RequestLogInterceptor requestLogInterceptor;
    private final String uploadDir;

    public WebMvcConfig(AuthInterceptor authInterceptor, RequestLogInterceptor requestLogInterceptor,
                        @Value("${app.upload.dir:./data/upload}") String uploadDir) {
        this.authInterceptor = authInterceptor;
        this.requestLogInterceptor = requestLogInterceptor;
        this.uploadDir = uploadDir;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 鉴权拦截器先注册：设置 UserContext(openid)
        // /files/** 为上传图片的静态资源：<image src> 无法携带 Authorization 头，故不校验 token。
        // 安全性由「UUID 随机文件名 + 不可列举目录」保证；若需严格管控，应改为签名 URL。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/login",
                        "/health",
                        "/error",
                        "/files/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/webjars/**");
        // 请求日志拦截器后注册：preHandle 在鉴权之后执行，可拿到 UserContext 的 openid
        registry.addInterceptor(requestLogInterceptor).addPathPatterns("/**");
        // 注：/invitation/{token}(GET) 与 /invitation/{token}/apply(POST) 的匿名放行
        // 在 AuthInterceptor 内按「路径+请求方法」精确判断，避免把 /invitation/revoke 误放行。
    }

    /** 上传图片的静态资源映射：/api/files/yyyy/MM/xxx.jpg → ${app.upload.dir}/yyyy/MM/xxx.jpg */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/files/**")
                .addResourceLocations(location)
                .setCachePeriod(86400);
    }
}
