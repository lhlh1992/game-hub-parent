package com.gamehub.web.common;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * 当前用户信息 DTO
 * 从 JWT token 中提取的用户信息，统一封装
 */
public record CurrentUserInfo(
    /** 用户ID（Keycloak subject，UUID格式） */
    String userId,
    
    /** 用户名（preferred_username，如果没有则使用 userId） */
    String username,
    
    /** 昵称（优先从 JWT 的 name 或 preferred_username 获取，如果没有则为 null） */
    String nickname,
    
    /** 邮箱 */
    String email,
    
    /** Realm 角色列表 */
    Collection<String> realmRoles,
    
    /** Client 角色映射（client-id -> roles） */
    Map<String, Collection<String>> clientRoles
) {
    /**
     * 获取显示名称（用于UI展示）
     * 优先级：nickname > username > userId
     */
    public String getDisplayName() {
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        if (username != null && !username.isBlank()) {
            return username;
        }
        return userId;
    }
    
    /**
     * 检查用户是否有指定角色
     */
    public boolean hasRealmRole(String role) {
        return realmRoles != null && realmRoles.contains(role);
    }
    
    /**
     * 检查用户在指定 client 中是否有指定角色
     */
    public boolean hasClientRole(String clientId, String role) {
        if (clientRoles == null) {
            return false;
        }
        Collection<String> roles = clientRoles.get(clientId);
        return roles != null && roles.contains(role);
    }
}

