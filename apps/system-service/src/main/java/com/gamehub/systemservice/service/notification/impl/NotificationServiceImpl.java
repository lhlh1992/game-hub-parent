package com.gamehub.systemservice.service.notification.impl;

import com.gamehub.systemservice.entity.notification.Notification;
import com.gamehub.systemservice.repository.notification.NotificationRepository;
import com.gamehub.systemservice.service.notification.NotificationService;
import com.gamehub.systemservice.service.notification.dto.NotificationView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.gamehub.systemservice.infrastructure.client.chat.ChatNotifyClient;
import com.gamehub.systemservice.infrastructure.client.chat.dto.NotifyPushRequest;

import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
        // 1) 落库，确保离线/未读可见；统一走 sys_notification
        Notification notification = Notification.builder()
                .userId(receiverUserId)
                .type("FRIEND_REQUEST")
                .title("好友申请")
                .content(requesterName + " 请求加你为好友")
                .fromUserId(requesterKeycloakUserId)
                .refType("FRIEND_REQUEST")
                .refId(friendRequestId)
                .payload(buildPayload(friendRequestId, requestMessage, requesterName, null))
                .actions(List.of("ACCEPT", "REJECT"))
                .status("UNREAD")
                .sourceService("system-service")
                .build();
        notificationRepository.save(notification);

        // 2) 推送 WS（失败不影响事务；携带 notificationId 便于前端去重/标记）
        try {
            pushToChatService(receiverKeycloakUserId, requesterKeycloakUserId, requesterName, friendRequestId, requestMessage, notification.getId());
        } catch (Exception e) {
            log.warn("推送好友申请通知失败（已落库，用户可离线查看）：receiver={}, refId={}, err={}",
                    receiverKeycloakUserId, friendRequestId, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notifyFriendResult(UUID targetUserId,
                                   String targetKeycloakUserId,
                                   String handlerKeycloakUserId,
                                   String title,
                                   String content,
                                   UUID friendRequestId) {
        Notification notification = Notification.builder()
                .userId(targetUserId)
                .type("FRIEND_RESULT")
                .title(title)
                .content(content)
                .fromUserId(handlerKeycloakUserId)
                .refType("FRIEND_REQUEST")
                .refId(friendRequestId)
                .status("UNREAD")
                .sourceService("system-service")
                .build();
        notificationRepository.save(notification);

        try {
            NotifyPushRequest body = new NotifyPushRequest();
            body.setUserId(targetKeycloakUserId);
            body.setType("FRIEND_RESULT");
            body.setTitle(title);
            body.setContent(content);
            body.setFromUserId(handlerKeycloakUserId);
            body.setPayload(Map.of(
                    "friendRequestId", friendRequestId != null ? friendRequestId.toString() : null
            ));
            body.setActions(new String[0]);
            chatNotifyClient.push(body);
        } catch (Exception e) {
            log.warn("推送好友结果通知失败（已落库）：receiver={}, refId={}, err={}",
                    targetKeycloakUserId, friendRequestId, e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationView> listNotifications(UUID userId, String status, int limit) {
        int pageSize = Math.max(1, Math.min(limit, 100));
        return notificationRepository
                .findByUserIdAndStatus(userId, status, PageRequest.of(0, pageSize))
                .stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(UUID userId) {
        return notificationRepository.countUnread(userId);
    }

    @Override
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (!Objects.equals(n.getUserId(), userId)) {
                return; // 越权忽略
            }
            if (!"READ".equalsIgnoreCase(n.getStatus())) {
                n.setStatus("READ");
                n.setReadAt(OffsetDateTime.now());
                notificationRepository.save(n);
            }
        });
    }

    @Override
    @Transactional
    public void markAllRead(UUID userId) {
        // 批量改状态，前端打开铃铛时调用
        List<Notification> list = notificationRepository.findByUserIdAndStatus(userId, "UNREAD", PageRequest.of(0, 500));
        if (list.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        list.forEach(n -> {
            n.setStatus("READ");
            n.setReadAt(now);
        });
        notificationRepository.saveAll(list);
    }

    private void pushToChatService(String receiverKeycloakUserId,
                                   String requesterKeycloakUserId,
                                   String requesterName,
                                   UUID friendRequestId,
                                   String requestMessage,
                                   UUID notificationId) {
        NotifyPushRequest body = new NotifyPushRequest();
        body.setUserId(receiverKeycloakUserId);
        body.setType("FRIEND_REQUEST");
        body.setTitle("好友申请");
        body.setContent(requesterName + " 请求加你为好友");
        body.setFromUserId(requesterKeycloakUserId);
        body.setPayload(buildPayload(friendRequestId, requestMessage, requesterName, notificationId));
        body.setActions(new String[]{"ACCEPT", "REJECT"});

        chatNotifyClient.push(body);
    }

    private Map<String, Object> buildPayload(UUID friendRequestId, String requestMessage, String requesterName, UUID notificationId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("friendRequestId", friendRequestId != null ? friendRequestId.toString() : null);
        payload.put("requestMessage", requestMessage);
        payload.put("requesterName", requesterName);
        if (notificationId != null) {
            payload.put("notificationId", notificationId.toString());
        }
        return payload;
    }

    private NotificationView toView(Notification n) {
        return NotificationView.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .content(n.getContent())
                .status(n.getStatus())
                .fromUserId(n.getFromUserId())
                .refType(n.getRefType())
                .refId(n.getRefId())
                .payload(n.getPayload())
                .actions(n.getActions())
                .sourceService(n.getSourceService())
                .createdAt(n.getCreatedAt())
                .readAt(n.getReadAt())
                .build();
    }
}


