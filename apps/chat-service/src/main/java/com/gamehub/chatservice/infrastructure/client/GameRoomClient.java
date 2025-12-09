package com.gamehub.chatservice.infrastructure.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * 可选：查询房间成员，用于房间聊天权限校验。
 */
@FeignClient(name = "game-service", fallback = GameRoomClientFallback.class)
public interface GameRoomClient {

    @GetMapping("/api/game/rooms/{roomId}/members")
    @CircuitBreaker(name = "gameRoomClient")
    List<String> getRoomMembers(@PathVariable String roomId);
}

