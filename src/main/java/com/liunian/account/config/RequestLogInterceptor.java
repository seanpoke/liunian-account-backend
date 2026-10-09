package com.liunian.account.config;

import com.liunian.account.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求级访问日志：打印 method + uri + 当前用户 openid，便于整体链路追踪。
 * 注册在 AuthInterceptor 之后，因此能拿到 UserContext 中的 openid。
 * 健康检查 / swagger 等高频或内部端点跳过，避免刷屏。
 */
@Component
public class RequestLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestLogInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/health") || uri.startsWith("/swagger")
                || uri.startsWith("/v3/api-docs") || uri.startsWith("/webjars")
                || uri.startsWith("/error")) {
            return true;
        }
        String openid = UserContext.get();
        log.info("[REQ] {} {} openid={}", request.getMethod(), uri, openid == null ? "-" : openid);
        return true;
    }
}
