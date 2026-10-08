package com.liunian.account.common;

/**
 * 业务异常：携带错误码，由 GlobalExceptionHandler 转换为统一响应。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public BizException(String msg) {
        this(ErrorCode.CONFLICT, msg);
    }

    public int getCode() {
        return code;
    }
}
