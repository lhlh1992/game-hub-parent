package com.gamehub.gameservice.platform.transport;

import com.gamehub.gameservice.platform.ongoing.OngoingGameTracker;
import com.gamehub.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供“继续对局”查询接口。
 * 返回值只有是否存在进行中的游戏以及必要的路由信息，
 * 方便前端在任意页面快速展示入口。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OngoingGameController {

    private final OngoingGameTracker tracker;

    @GetMapping("/ongoing-game")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ongoing(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return tracker.find(userId)
                .map(info -> Map.<String, Object>of(
                        "hasOngoing", true,
                        "gameType", info.getGameType(),
                        "roomId", info.getRoomId(),
                        "title", info.getTitle(),
                        "updatedAt", info.getUpdatedAt()
                ))
                .map(body -> ResponseEntity.ok(ApiResponse.success(body)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.success(Map.of("hasOngoing", false))));
    }
}

