package com.gamehub.gameservice.application.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 游戏服务视角下的“用户档案视图”。
 * 对应 system-service 暴露的 UserInfo，但这里只作为只读视图使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // 兼容 system-service 返回的额外字段（如 enabled）
public class UserProfileView {

    // ===== 基础信息（sys_user）=====
    /** Keycloak 用户ID（JWT sub） */
    private String userId;
    /** 系统用户ID */
    private UUID systemUserId;
    /** 用户名 */
    private String username;
    /** 昵称（展示名） */
    private String nickname;
    /** 头像地址 */
    private String avatarUrl;
    /** 邮箱 */
    private String email;
    /** 手机号 */
    private String phone;
    /** 用户类型：NORMAL / ADMIN */
    private String userType;
    /** 状态：0-禁用，1-启用 */
    private Integer status;

    // ===== 扩展档案（sys_user_profile）=====
    /** 个性签名 / 个人简介 */
    private String bio;
    /** 语言偏好，如 zh-CN / en-US */
    private String locale;
    /** 时区，如 Asia/Shanghai / UTC */
    private String timezone;

    // ===== 游戏统计（user_score，预留）=====
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

    /** 展示名：优先昵称，其次用户名，最后截断 userId。 */
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



