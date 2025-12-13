package com.gamehub.systemservice.service.notification.impl;

import com.gamehub.systemservice.entity.notification.Notification;
import com.gamehub.systemservice.entity.friend.FriendRequest;
import com.gamehub.systemservice.repository.notification.NotificationRepository;
import com.gamehub.systemservice.repository.friend.FriendRequestRepository;
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
    private final FriendRequestRepository friendRequestRepository;

    /**
     * 发送好友申请通知
     * 先落库，再推送到chat-service，推送失败不影响主流程
     */
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

    /**
     * 发送好友申请处理结果通知（同意/拒绝）
     * 通知申请人处理结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notifyFriendResult(UUID targetUserId,
                                   String targetKeycloakUserId,
                                   String handlerKeycloakUserId,
                                   String title,
                                   String content,
                                   UUID friendRequestId,
                                   boolean accepted) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("friendRequestId", friendRequestId != null ? friendRequestId.toString() : null);
        payload.put("accepted", accepted);
        payload.put("result", accepted ? "ACCEPTED" : "REJECTED");
        
        Notification notification = Notification.builder()
                .userId(targetUserId)
                .type("FRIEND_RESULT")
                .title(title)
                .content(content)
                .fromUserId(handlerKeycloakUserId)
                .refType("FRIEND_REQUEST")
                .refId(friendRequestId)
                .payload(payload)
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
            body.setPayload(payload);
            body.setActions(new String[0]);
            chatNotifyClient.push(body);
        } catch (Exception e) {
            log.warn("推送好友结果通知失败（已落库）：receiver={}, refId={}, err={}",
                    targetKeycloakUserId, friendRequestId, e.getMessage());
        }
    }

    /**
     * 查询通知列表
     * @param status 可为null，null时返回所有状态
     * @param limit 限制条数，最大100
     */
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

    /**
     * 统计未读通知数量
     */
    @Override
    @Transactional(readOnly = true)
    public long countUnread(UUID userId) {
        return notificationRepository.countUnread(userId);
    }

    /**
     * 标记单条通知为已读
     * 会校验userId，防止越权
     */
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

    /**
     * 批量标记所有未读通知为已读
     * 最多处理500条，避免一次性更新太多
     */
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

    /**
     * 清除通知的操作按钮
     * 当业务已处理完成时调用，比如好友申请已同意/拒绝后，就不应该再显示操作按钮了
     */
    @Override
    @Transactional
    public void clearNotificationActions(UUID userId, String refType, UUID refId) {
        // 查找接收方对应的通知（如 FRIEND_REQUEST 类型的通知）
        List<Notification> notifications = notificationRepository.findByUserIdAndRefTypeAndRefId(userId, refType, refId);
        if (notifications.isEmpty()) {
            log.debug("未找到需要清除操作按钮的通知: userId={}, refType={}, refId={}", userId, refType, refId);
            return;
        }
        // 清除所有匹配通知的操作按钮（已处理，不应再显示操作按钮）
        notifications.forEach(n -> {
            n.setActions(null); // 清除操作按钮
            if ("UNREAD".equalsIgnoreCase(n.getStatus())) {
                n.setStatus("READ"); // 同时标记为已读
                n.setReadAt(OffsetDateTime.now());
            }
        });
        notificationRepository.saveAll(notifications);
        log.debug("已清除通知操作按钮: userId={}, refType={}, refId={}, count={}", userId, refType, refId, notifications.size());
    }

    /**
     * 推送通知到chat-service，通过websocket实时推送
     */
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

    /**
     * 构建通知的payload数据
     */
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

    /**
     * 将Notification实体转换为NotificationView
     * 对于已处理的好友申请，会查询实际状态并补充到payload中
     */
    private NotificationView toView(Notification n) {
        Map<String, Object> payload = n.getPayload() != null ? new HashMap<>(n.getPayload()) : new HashMap<>();
        
        // 如果是好友申请类型且已处理（actions为空），查询处理状态并添加到payload
        if ("FRIEND_REQUEST".equals(n.getType()) 
            && (n.getActions() == null || n.getActions().isEmpty()) 
            && n.getRefId() != null) {
            try {
                friendRequestRepository.findById(n.getRefId()).ifPresent(request -> {
                    FriendRequest.RequestStatus requestStatus = request.getStatus();
                    if (requestStatus == FriendRequest.RequestStatus.ACCEPTED) {
                        payload.put("handledStatus", "ACCEPTED");
                        payload.put("handledStatusText", "已同意");
                    } else if (requestStatus == FriendRequest.RequestStatus.REJECTED) {
                        payload.put("handledStatus", "REJECTED");
                        payload.put("handledStatusText", "已拒绝");
                    }
                });
            } catch (Exception e) {
                log.debug("查询好友申请状态失败: notificationId={}, refId={}, err={}", 
                    n.getId(), n.getRefId(), e.getMessage());
            }
        }
        
        return NotificationView.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .content(n.getContent())
                .status(n.getStatus())
                .fromUserId(n.getFromUserId())
                .refType(n.getRefType())
                .refId(n.getRefId())
                .payload(payload)
                .actions(n.getActions())
                .sourceService(n.getSourceService())
                .createdAt(n.getCreatedAt())
                .readAt(n.getReadAt())
                .build();
    }
}


