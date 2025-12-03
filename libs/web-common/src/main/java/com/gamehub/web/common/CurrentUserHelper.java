package com.gamehub.web.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

    /**
     * 当前用户信息提取工具类
     * 
     * 统一从 JWT token 中提取用户信息，包括：
     * - 用户ID、用户名、昵称、邮箱
     * - Realm 角色、Client 角色
     * 
     * 使用方式：
     * <pre>
     * {@code
     * @GetMapping("/example")
     * public ResponseEntity<?> example(@AuthenticationPrincipal Jwt jwt) {
     *     CurrentUserInfo user = CurrentUserHelper.from(jwt);
     *     String displayName = user.getDisplayName();
     *     // ...
     * }
     * }
     * </pre>
     */
@Slf4j
public final class CurrentUserHelper {
    
    /** lastName 的默认占位值（用于 Keycloak 必填字段，实际使用时会被过滤掉） */
    private static final String LASTNAME_PLACEHOLDER = "-";
    
    private CurrentUserHelper() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * 从 JWT token 中提取当前用户信息
     * 
     * @param jwt JWT token（从 @AuthenticationPrincipal 注入）
     * @return 当前用户信息，如果 jwt 为 null 则返回 null
     */
    public static CurrentUserInfo from(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        
        // 1. 用户ID（subject）
        String userId = jwt.getSubject();
        
        // 2. 用户名（优先 preferred_username，没有则用 userId）
        String username = Optional.ofNullable(jwt.getClaimAsString("preferred_username"))
                .filter(s -> !s.isBlank())
                .orElse(userId);
        
        // 3. 昵称（优先 name，其次 preferred_username，都没有则为 null）
        // 注意：Keycloak 的 name 字段通常是 "firstName lastName" 格式
        // 如果 lastName 是占位值 "-"，需要过滤掉
        String nickname = Optional.ofNullable(jwt.getClaimAsString("name"))
                .map(CurrentUserHelper::cleanName)
                .filter(s -> !s.isBlank())
                .or(() -> Optional.ofNullable(jwt.getClaimAsString("preferred_username"))
                        .filter(s -> !s.isBlank()))
                .orElse(null);
        
        // 4. 邮箱
        String email = jwt.getClaimAsString("email");
        
        // 5. Realm 角色
        Collection<String> realmRoles = extractRealmRoles(jwt);
        
        // 6. Client 角色
        Map<String, Collection<String>> clientRoles = extractClientRoles(jwt);
        
        return new CurrentUserInfo(
                userId,
                username,
                nickname,
                email,
                realmRoles,
                clientRoles
        );
    }
    
    /**
     * 提取 Realm 角色
     */
    @SuppressWarnings("unchecked")
    private static Collection<String> extractRealmRoles(Jwt jwt) {
        try {
            Object realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof Map<?, ?> realm) {
                Object roles = realm.get("roles");
                if (roles instanceof Collection<?> r) {
                    return (Collection<String>) r;
                }
            }
        } catch (Exception e) {
            log.debug("提取 Realm 角色失败", e);
        }
        return Collections.emptyList();
    }
    
    /**
     * 提取 Client 角色
     * 返回格式：{ "client-id-1": ["role1", "role2"], "client-id-2": ["role3"] }
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Collection<String>> extractClientRoles(Jwt jwt) {
        try {
            Object resourceAccess = jwt.getClaim("resource_access");
            if (resourceAccess instanceof Map<?, ?> res) {
                Map<String, Collection<String>> result = new HashMap<>();
                for (Map.Entry<?, ?> entry : res.entrySet()) {
                    String clientId = String.valueOf(entry.getKey());
                    Object clientData = entry.getValue();
                    if (clientData instanceof Map<?, ?> client) {
                        Object roles = client.get("roles");
                        if (roles instanceof Collection<?> r) {
                            result.put(clientId, (Collection<String>) r);
                        }
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("提取 Client 角色失败", e);
        }
        return Collections.emptyMap();
    }
    
    /**
     * 快速获取用户ID
     */
    public static String getUserId(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : null;
    }
    
    /**
     * 快速获取显示名称（用于UI展示）
     */
    public static String getDisplayName(Jwt jwt) {
        CurrentUserInfo user = from(jwt);
        return user != null ? user.getDisplayName() : null;
    }
    
    /**
     * 快速获取昵称（优先从 JWT，如果没有则返回 null）
     */
    public static String getNickname(Jwt jwt) {
        CurrentUserInfo user = from(jwt);
        return user != null ? user.nickname() : null;
    }
    
    /**
     * 清理 name 字段，去掉 lastName 占位值
     * Keycloak 的 name 字段格式通常是 "firstName lastName"
     * 如果 lastName 是占位值 "-"，则只返回 firstName
     * 
     * 处理逻辑：
     * 1. 先 trim 去掉首尾空格
     * 2. 如果以 " -" 结尾（空格+横线），去掉这两个字符
     * 3. 如果以 "-" 结尾，去掉这个字符
     * 4. 再次 trim，确保没有尾随空格
     * 
     * 示例：
     * - "张三 -" → "张三"
     * - "张三-" → "张三"
     * - "张-三 -" → "张-三"（保留名字中间的横线）
     * - "张-三-" → "张-三"（保留名字中间的横线）
     * 
     * @param name 原始 name 字段值
     * @return 清理后的 name（去掉占位值部分）
     */
    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        
        // 先 trim 去掉首尾空格
        String result = name.trim();
        
        // 如果以 " -" 结尾（空格+横线），去掉这两个字符
        if (result.endsWith(" " + LASTNAME_PLACEHOLDER)) {
            result = result.substring(0, result.length() - 2);
        }
        // 如果以 "-" 结尾，去掉这个字符（只处理最后一个字符）
        else if (result.endsWith(LASTNAME_PLACEHOLDER)) {
            result = result.substring(0, result.length() - 1);
        }
        
        // 再次 trim，确保没有尾随空格
        return result.trim();
    }
}

