package com.gamehub.systemservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 好友信息 DTO
 * 包含好友关系信息和好友的详细信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendInfo {
    
    /**
     * 好友关系ID
     */
    private String friendRelationId;
    
    /**
     * 好友的用户ID（Keycloak用户ID）
     */
    private String friendId;
    
    /**
     * 好友备注昵称
     */
    private String friendNickname;
    
    /**
     * 好友分组
     */
    private String friendGroup;
    
    /**
     * 是否特别关心
     */
    private Boolean isFavorite;
    
    /**
     * 最后互动时间
     */
    private OffsetDateTime lastInteractionTime;
    
    /**
     * 好友的详细信息
     */
    private UserInfo friendInfo;
    
    /**
     * 在线状态（需要从chat-service获取，暂时为null）
     */
    private Boolean online;
}
