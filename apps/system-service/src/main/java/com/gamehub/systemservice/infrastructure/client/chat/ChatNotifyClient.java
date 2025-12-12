package com.gamehub.systemservice.infrastructure.client.chat;

import com.gamehub.systemservice.infrastructure.client.chat.dto.NotifyPushRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 调用 chat-service 内部通知推送接口。
 */
@FeignClient(name = "chat-service", path = "/api/internal/notify", fallback = ChatNotifyClientFallback.class)
public interface ChatNotifyClient {

    @PostMapping
    @CircuitBreaker(name = "chatNotifyClient")
    void push(@RequestBody NotifyPushRequest request);
}

