package com.liunian.account.security;

/**
 * 请求级当前用户上下文（openid）。拦截器写入、完成后清理。
 */
public final class UserContext {

    private static final ThreadLocal<String> OPENID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(String openid) {
        OPENID.set(openid);
    }

    public static String get() {
        return OPENID.get();
    }

    public static void clear() {
        OPENID.remove();
    }
}
