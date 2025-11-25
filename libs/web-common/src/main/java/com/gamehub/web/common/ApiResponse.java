package com.gamehub.web.common;

import java.io.Serializable;

/**
 * 统一 API 响应格式
 * 
 * @param <T> 响应数据类型
 */
public record ApiResponse<T>(
    /**
     * 响应状态码
     * 200: 成功
     * 400: 客户端错误（参数错误等）
     * 401: 未认证
     * 403: 无权限
     * 404: 资源不存在
     * 409: 冲突（业务状态错误）
     * 500: 服务器错误
     */
    int code,
    
    /**
     * 响应消息
     */
    String message,
    
    /**
     * 响应数据
     */
    T data
) implements Serializable {
    
    /**
     * 成功响应（无数据）
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "success", null);
    }
    
    /**
     * 成功响应（带数据）
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }
    
    /**
     * 成功响应（带消息和数据）
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }
    
    /**
     * 失败响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
    
    /**
     * 失败响应（400 Bad Request）
     */
    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(400, message, null);
    }
    
    /**
     * 失败响应（401 Unauthorized）
     */
    public static <T> ApiResponse<T> unauthorized(String message) {
        return new ApiResponse<>(401, message, null);
    }
    
    /**
     * 失败响应（403 Forbidden）
     */
    public static <T> ApiResponse<T> forbidden(String message) {
        return new ApiResponse<>(403, message, null);
    }
    
    /**
     * 失败响应（404 Not Found）
     */
    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(404, message, null);
    }
    
    /**
     * 失败响应（409 Conflict）
     */
    public static <T> ApiResponse<T> conflict(String message) {
        return new ApiResponse<>(409, message, null);
    }
    
    /**
     * 失败响应（500 Internal Server Error）
     */
    public static <T> ApiResponse<T> serverError(String message) {
        return new ApiResponse<>(500, message, null);
    }
}

