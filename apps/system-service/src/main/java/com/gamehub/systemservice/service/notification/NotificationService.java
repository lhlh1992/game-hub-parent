package com.gamehub.systemservice.service.notification;

import java.util.UUID;

/**
 * 通知业务接口。
 */
public interface NotificationService {

    /**
     * 创建一条好友申请通知并尝试推送。
     *
     * @param receiverUserId   接收方系统用户ID
     * @param receiverKeycloakUserId 接收方 Keycloak userId（用于 WS 推送）
     * @param requesterKeycloakUserId 申请人 Keycloak userId
     * @param requesterName    申请人显示名
     * @param friendRequestId  好友申请记录 ID
     */
    void notifyFriendRequest(UUID receiverUserId,
                             String receiverKeycloakUserId,
                             String requesterKeycloakUserId,
                             String requesterName,
                             UUID friendRequestId,
                             String requestMessage);

    /**
     * 查询通知列表（可按状态过滤，limit 默认 20）。
     */
    java.util.List<com.gamehub.systemservice.service.notification.dto.NotificationView> listNotifications(UUID userId,
                                                                                                         String status,
                                                                                                         int limit);

    /**
     * 未读数量。
     */
    long countUnread(UUID userId);

    /**
     * 标记单条已读（幂等）。
     */
    void markRead(UUID userId, UUID notificationId);

    /**
     * 全部标记已读。
     */
    void markAllRead(UUID userId);

    /**
     * 通知好友申请结果（给申请人）
     */
    void notifyFriendResult(UUID targetUserId,
                            String targetKeycloakUserId,
                            String handlerKeycloakUserId,
                            String title,
                            String content,
                            UUID friendRequestId);
}


