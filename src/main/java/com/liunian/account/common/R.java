package com.liunian.account.common;

/**
 * 统一响应结构：{ code, msg, data }，code=0 成功，非 0 为错误码。
 */
public record R<T>(int code, String msg, T data) {

    public static <T> R<T> ok(T data) {
        return new R<>(0, "", data);
    }

    public static R<Void> ok() {
        return new R<>(0, "", null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }
}
