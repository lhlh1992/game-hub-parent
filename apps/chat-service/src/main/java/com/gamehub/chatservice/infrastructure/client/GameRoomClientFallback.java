package com.gamehub.chatservice.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class GameRoomClientFallback implements GameRoomClient {
    @Override
    public List<String> getRoomMembers(String roomId) {
        log.warn("game-service unavailable, return empty members, roomId={}", roomId);
        return Collections.emptyList();
    }
}

