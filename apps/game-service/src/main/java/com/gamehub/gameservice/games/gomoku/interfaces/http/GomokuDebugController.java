package com.gamehub.gameservice.games.gomoku.interfaces.http;

import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.enums.Rule;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import com.gamehub.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 临时调试用接口：批量创建测试房间。
 *
 * 说明：
 * - 仅用于本地/测试环境造数据，方便大厅房间列表联调；
 * - 无鉴权，ownerUserId 固定为 "abc"；
 * - 不影响正式创建逻辑（/api/gomoku/new 走原来的带鉴权接口）。
 */
@RestController
@RequestMapping("/api/gomoku/debug")
@RequiredArgsConstructor
public class GomokuDebugController {

    private final GomokuService gomokuService;

    /**
     * 批量创建测试房间。
     *
     * 用法（示例）：
     *   GET /api/gomoku/debug/create-rooms?count=10&rule=STANDARD
     */
    @GetMapping("/create-rooms")
    public ApiResponse<List<String>> createRooms(
            @RequestParam(name = "count", defaultValue = "5") int count,
            @RequestParam(name = "rule", defaultValue = "STANDARD") String rule
    ) {
        int safeCount = Math.min(Math.max(count, 1), 50); // 防止一次性创建过多
        Rule r = "RENJU".equalsIgnoreCase(rule) ? Rule.RENJU : Rule.STANDARD;

        List<String> roomIds = new ArrayList<>(safeCount);
        for (int i = 0; i < safeCount; i++) {
            String roomId = gomokuService.newRoom(Mode.PVP, null, r, "abc");
            roomIds.add(roomId);
        }
        return ApiResponse.success(roomIds);
    }
}


