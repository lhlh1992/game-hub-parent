package com.gamehub.gameservice.common;

import com.gamehub.web.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常映射处理器。
 */
@RestControllerAdvice
public class WebExceptionAdvice {
    /**
     * 处理参数不合法异常（IllegalArgumentException）。
     * 该异常通常出现在 Controller 或 Service 层的参数校验失败时。
     * @param e 参数非法异常
     * @return HTTP 400（Bad Request），响应体为异常信息
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.badRequest(e.getMessage()));
    }
    /**
     * 处理非法状态异常（IllegalStateException）。
     * 该异常通常用于业务状态不符合预期的场景，例如重复提交、流程冲突等。
     * @param e 状态非法异常
     * @return HTTP 409（Conflict），响应体为异常信息
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.conflict(e.getMessage()));
    }
}
