package com.liunian.account.common;

/**
 * 业务错误码。401 未登录/鉴权失败；403 越权；409 重复操作；410 已失效。
 */
public final class ErrorCode {

    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int CONFLICT = 409;
    public static final int GONE = 410;
    public static final int BAD_REQUEST = 400;
    public static final int INTERNAL = 500;

    private ErrorCode() {
    }
}
