package com.gamehub.web.common.feign;

/**
 * ThreadLocal 存储原始 JWT Token，供 Feign 调用时使用
 * 主要用于 WebSocket 消息处理等场景，在这些场景中无法从 HTTP 请求 Header 中获取 Token
 */
public class JwtTokenHolder {
    private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();
    
    public static void setToken(String token) {
        TOKEN_HOLDER.set(token);
    }
    
    public static String getToken() {
        return TOKEN_HOLDER.get();
    }
    
    public static void clear() {
        TOKEN_HOLDER.remove();
    }
}

