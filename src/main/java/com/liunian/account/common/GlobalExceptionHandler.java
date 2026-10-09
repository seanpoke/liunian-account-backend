package com.liunian.account.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e, HttpServletRequest request) {
        // 业务异常：客户端能看到 msg，但服务端必须留痕，否则控制台无错误日志
        log.warn("[BizException] {} {} -> code={}, msg={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        for (FieldError err : e.getBindingResult().getFieldErrors()) {
            sb.append(err.getField()).append(":").append(err.getDefaultMessage()).append("; ");
        }
        String msg = sb.toString().trim();
        log.warn("[Validation] {} {} -> {}", request.getMethod(), request.getRequestURI(), msg);
        return R.fail(ErrorCode.BAD_REQUEST, msg);
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e, HttpServletRequest request) {
        // 未预期异常：对外只暴露通用文案，但必须打印完整堆栈，否则完全无法排查
        log.error("[UnhandledException] {} {} -> {}",
                request.getMethod(), request.getRequestURI(), e.getMessage(), e);
        return R.fail(ErrorCode.INTERNAL, "服务器内部错误");
    }
}
