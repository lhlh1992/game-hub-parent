package com.gamehub.systemservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 用户信息 DTO
 * 聚合 sys_user + sys_user_profile (+ 预留 user_score) 的读模型，对外提供完整的用户档案数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {

    // ========== 用户基础信息（sys_user）==========
    private String userId;          // Keycloak 用户ID（sub）
    private UUID systemUserId;      // 系统用户ID
    private String username;        // 用户名
    private String nickname;        // 昵称
    private String avatarUrl;       // 头像
    private String email;           // 邮箱
    private String phone;           // 手机
    private String userType;        // 用户类型（NORMAL/ADMIN）
    private Integer status;         // 状态：0-禁用，1-启用

    // ========== 用户扩展信息（sys_user_profile）==========
    private String bio;             // 个人简介
    private String locale;          // 语言偏好
    private String timezone;        // 时区
    private Map<String, Object> settings; // 设置 JSONB

    // ========== 游戏统计（user_score，暂可为空）==========
    private UUID levelId;
    private String levelName;
    private Integer levelNumber;
    private Long totalScore;
    private Long currentScore;
    private Long frozenScore;
    private Long experiencePoints;
    private Integer winCount;
    private Integer loseCount;
    private Integer drawCount;
    private Integer totalMatches;
    private BigDecimal winRate;
    private Long highestScore;

    /** 获取显示名称（优先昵称，其次用户名，最后截断 userId）。 */
    public String getDisplayName() {
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        if (username != null && !username.isBlank()) {
            return username;
        }
        return userId != null ? userId.substring(0, Math.min(8, userId.length())) + "..." : "未知用户";
    }

    /** 是否启用。 */
    public boolean isEnabled() {
        return status != null && status == 1;
    }

    /** 是否管理员。 */
    public boolean isAdmin() {
        return "ADMIN".equals(userType);
    }
}


