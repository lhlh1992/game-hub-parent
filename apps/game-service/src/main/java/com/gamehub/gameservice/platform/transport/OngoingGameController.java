package com.gamehub.gameservice.platform.transport;

import com.gamehub.gameservice.platform.ongoing.OngoingGameTracker;
import com.gamehub.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 负责“继续对局”相关的查询与清理。
 * 前端可以用它来决定是否展示“继续游戏”入口，以及在玩家主动退出时清理状态。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OngoingGameController {

    private final OngoingGameTracker tracker;

    /**
     * 查询当前登录用户是否存在进行中的游戏。
     * 若存在，返回游戏类型、房间号以及展示用标题；否则返回 hasOngoing=false。
     */
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

    /**
     * 主动结束进行中的游戏，用于“退出房间”或“结束对局”场景。
     * 校验 roomId（可选），避免误清其他房间的状态。
     */
    @PostMapping("/ongoing-game/end")
    public ResponseEntity<ApiResponse<Map<String, Object>>> end(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) EndRequest request
    ) {
        String userId = jwt.getSubject();
        tracker.find(userId).ifPresent(info -> {
            if (request == null || request.roomId() == null || info.getRoomId().equals(request.roomId())) {
                tracker.clear(userId);
            }
        });
        return ResponseEntity.ok(ApiResponse.success(Map.of("hasOngoing", false)));
    }

    private record EndRequest(String roomId) {}
}

