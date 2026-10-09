package com.liunian.account.config;

import com.liunian.account.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RequestLogInterceptor requestLogInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor, RequestLogInterceptor requestLogInterceptor) {
        this.authInterceptor = authInterceptor;
        this.requestLogInterceptor = requestLogInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 鉴权拦截器先注册：设置 UserContext(openid)
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/login",
                        "/health",
                        "/error",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/webjars/**");
        // 请求日志拦截器后注册：preHandle 在鉴权之后执行，可拿到 UserContext 的 openid
        registry.addInterceptor(requestLogInterceptor).addPathPatterns("/**");
        // 注：/invitation/{token}(GET) 与 /invitation/{token}/apply(POST) 的匿名放行
        // 在 AuthInterceptor 内按「路径+请求方法」精确判断，避免把 /invitation/revoke 误放行。
    }
}
