package com.gamehub.systemservice.service.notification.impl;

import com.gamehub.systemservice.entity.notification.Notification;
import com.gamehub.systemservice.repository.notification.NotificationRepository;
import com.gamehub.systemservice.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.gamehub.systemservice.infrastructure.client.chat.ChatNotifyClient;
import com.gamehub.systemservice.infrastructure.client.chat.dto.NotifyPushRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通知业务实现：负责落库 + 调用 chat-service 推送。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final ChatNotifyClient chatNotifyClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notifyFriendRequest(UUID receiverUserId,
                                    String receiverKeycloakUserId,
                                    String requesterKeycloakUserId,
                                    String requesterName,
                                    UUID friendRequestId,
                                    String requestMessage) {
        // 1) 落库，确保离线/未读可见
        Notification notification = Notification.builder()
                .userId(receiverUserId)
                .type("FRIEND_REQUEST")
                .title("好友申请")
                .content(requesterName + " 请求加你为好友")
                .fromUserId(requesterKeycloakUserId)
                .refId(friendRequestId)
                .status("UNREAD")
                .build();
        notificationRepository.save(notification);

        // 2) 尝试 WS 推送（失败不影响事务）
        try {
            pushToChatService(receiverKeycloakUserId, requesterKeycloakUserId, requesterName, friendRequestId, requestMessage);
        } catch (Exception e) {
            log.warn("推送好友申请通知失败（已落库，用户可离线查看）：receiver={}, refId={}, err={}",
                    receiverKeycloakUserId, friendRequestId, e.getMessage());
        }
    }

    private void pushToChatService(String receiverKeycloakUserId,
                                   String requesterKeycloakUserId,
                                   String requesterName,
                                   UUID friendRequestId,
                                   String requestMessage) {
        NotifyPushRequest body = new NotifyPushRequest();
        body.setUserId(receiverKeycloakUserId);
        body.setType("FRIEND_REQUEST");
        body.setTitle("好友申请");
        body.setContent(requesterName + " 请求加你为好友");
        body.setFromUserId(requesterKeycloakUserId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("friendRequestId", friendRequestId != null ? friendRequestId.toString() : null);
        payload.put("requestMessage", requestMessage);
        payload.put("requesterName", requesterName);
        body.setPayload(payload);
        body.setActions(new String[]{"ACCEPT", "REJECT"});

        chatNotifyClient.push(body);
    }
}

