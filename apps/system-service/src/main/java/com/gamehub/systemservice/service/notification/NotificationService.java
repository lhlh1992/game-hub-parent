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
}

