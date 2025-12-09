package com.gamehub.chatservice.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SystemUserClientFallback implements SystemUserClient {
    @Override
    public UserInfo getUserInfo(String userId) {
        log.warn("system-service unavailable, return minimal user info, userId={}", userId);
        return new UserInfo(userId, userId, userId, null, null);
    }

    @Override
    public boolean isMuted(String userId) {
        log.warn("system-service unavailable, default muted=false, userId={}", userId);
        return false;
    }
}

