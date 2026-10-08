package com.liunian.account.security;

import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String PREFIX = "auth:jti:";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    public AuthInterceptor(JwtUtil jwtUtil, StringRedisTemplate redis) {
        this.jwtUtil = jwtUtil;
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getServletPath();
        String method = request.getMethod();
        // 唯一匿名公开接口：GET /invitation/{token}（邀请落地页预览）
        if ("GET".equals(method) && uri.matches("^/invitation/[^/]+$")) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        String token = header.substring(7);
        String openid;
        String jti;
        try {
            openid = jwtUtil.openid(token);
            jti = jwtUtil.jti(token);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "鉴权失败");
        }
        if (Boolean.FALSE.equals(redis.hasKey(PREFIX + jti))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已失效");
        }
        UserContext.set(openid);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}