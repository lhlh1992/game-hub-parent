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

    @Override
    public boolean isFriend(String userId1, String userId2) {
        log.warn("system-service unavailable, default isFriend=false, userId1={}, userId2={}", userId1, userId2);
        // 降级策略：如果 system-service 不可用，为了可用性允许发送（但记录警告）
        // 生产环境建议改为 return false，确保安全性
        return true;
    }
}

