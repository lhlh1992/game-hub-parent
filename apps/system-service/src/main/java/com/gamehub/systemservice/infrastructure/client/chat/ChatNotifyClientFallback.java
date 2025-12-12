package com.gamehub.systemservice.infrastructure.client.chat;

import com.gamehub.systemservice.infrastructure.client.chat.dto.NotifyPushRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * chat-service 通知推送熔断兜底：记录日志，不抛异常。
 */
@Component
@Slf4j
public class ChatNotifyClientFallback implements ChatNotifyClient {
    @Override
    public void push(NotifyPushRequest request) {
        log.warn("chat-service notify push fallback: skip push, request={}", request);
    }
}

