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
     * @param accepted true表示同意，false表示拒绝
     */
    void notifyFriendResult(UUID targetUserId,
                            String targetKeycloakUserId,
                            String handlerKeycloakUserId,
                            String title,
                            String content,
                            UUID friendRequestId,
                            boolean accepted);

    /**
     * 清除通知的操作按钮（处理完好友申请后调用，避免重新登录后仍显示操作按钮）。
     *
     * @param userId 用户ID（接收方）
     * @param refType 关联类型（如 "FRIEND_REQUEST"）
     * @param refId 关联ID（如 friendRequestId）
     */
    void clearNotificationActions(UUID userId, String refType, UUID refId);
}


