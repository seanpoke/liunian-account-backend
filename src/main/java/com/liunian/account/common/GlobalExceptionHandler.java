package com.liunian.account.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        StringBuilder sb = new StringBuilder();
        for (FieldError err : e.getBindingResult().getFieldErrors()) {
            sb.append(err.getField()).append(":").append(err.getDefaultMessage()).append("; ");
        }
        return R.fail(ErrorCode.BAD_REQUEST, sb.toString().trim());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        return R.fail(ErrorCode.INTERNAL, "服务器内部错误");
    }
}
