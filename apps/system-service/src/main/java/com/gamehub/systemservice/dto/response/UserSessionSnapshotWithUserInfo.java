package com.gamehub.systemservice.dto.response;

import com.gamehub.session.model.UserSessionSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户会话快照（带用户信息）
 * 在 UserSessionSnapshot 基础上增加用户昵称等信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSessionSnapshotWithUserInfo {
    
    /** 用户ID（Keycloak userId） */
    private String userId;
    
    /** 用户昵称 */
    private String nickname;
    
    /** 用户名 */
    private String username;
    
    /** 原始会话快照 */
    private UserSessionSnapshot sessionSnapshot;
}


