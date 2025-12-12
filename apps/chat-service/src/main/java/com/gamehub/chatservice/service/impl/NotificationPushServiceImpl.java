package com.gamehub.chatservice.service.impl;

import com.gamehub.chatservice.service.NotificationPushService;
import com.gamehub.chatservice.service.dto.NotificationMessagePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 * 基于 Spring STOMP 的通知推送实现。
 * 推送到用户队列：/user/queue/notify
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationPushServiceImpl implements NotificationPushService {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void sendToUser(String userId, NotificationMessagePayload payload) {
        Assert.hasText(userId, "userId required");
        if (payload == null) {
            log.warn("skip push notify, payload is null. userId={}", userId);
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(userId, "/queue/notify", payload);
        } catch (Exception e) {
            // 推送失败仅记录日志，不抛出，避免影响调用方事务
            log.warn("push notify to user failed, userId={}, payload={}", userId, payload, e);
        }
    }
}

