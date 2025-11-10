package com.gamehub.systemservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {
    
    /**
     * Access Token（JWT）
     */
    private String accessToken;
    
    /**
     * Refresh Token
     */
    private String refreshToken;
    
    /**
     * Token 类型（通常是 Bearer）
     */
    private String tokenType;
    
    /**
     * Access Token 过期时间（秒）
     */
    private Integer expiresIn;
    
    /**
     * Refresh Token 过期时间（秒）
     */
    private Integer refreshExpiresIn;
    
    /**
     * 作用域
     */
    private String scope;
}

