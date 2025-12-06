package com.gamehub.gameservice.games.gomoku.interfaces.http;

import com.gamehub.gameservice.games.gomoku.domain.repository.RoomRepository;
import com.gamehub.gameservice.games.gomoku.interfaces.http.dto.RoomListResponse;
import com.gamehub.gameservice.games.gomoku.interfaces.http.dto.RoomSummary;
import com.gamehub.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.gamehub.gameservice.games.gomoku.infrastructure.redis.RedisKeys.roomIndexKey;

/**
 * 五子棋大厅 - 在线房间列表查询（仅用于大厅展示）
 *
 * 暂不做鉴权，前端可直接调用，用于 RoomListPanel。
 */
@RestController
@RequestMapping("/api/gomoku/rooms")
@RequiredArgsConstructor
public class RoomListController {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RoomRepository roomRepository;

    /**
     * 分页查询房间列表
     * @param cursor 游标（时间戳），用于分页
     * @param limit 每页数量，默认4
     * @return 房间列表及下一页游标
     */
    @GetMapping
    public ApiResponse<RoomListResponse> list(
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "4") int limit) {

        double max = (cursor == null) ? Double.POSITIVE_INFINITY : cursor - 1;
        double min = Double.NEGATIVE_INFINITY;

        Set<Object> ids = redisTemplate.opsForZSet()
                .reverseRangeByScore(roomIndexKey(), min, max, 0, limit);

        List<RoomSummary> items = new ArrayList<>();
        Long nextCursor = null;

        if (ids != null) {
            long minCreated = Long.MAX_VALUE;
            for (Object idObj : ids) {
                String roomId = String.valueOf(idObj);
                var metaOpt = roomRepository.getRoomMeta(roomId);
                if (metaOpt.isEmpty()) {
                    // 房间已删除：跳过，不显示在列表中
                    continue;
                }
                var meta = metaOpt.get();
                
                // 过滤PVE房间
                if ("PVE".equalsIgnoreCase(meta.getMode())) {
                    continue;
                }
                
                // 检查玩家数量：如果已有2个玩家，不显示在列表中
                var seatsOpt = roomRepository.getSeats(roomId);
                if (seatsOpt.isPresent()) {
                    var seats = seatsOpt.get();
                    boolean hasTwoPlayers = seats.getSeatXSessionId() != null 
                            && !seats.getSeatXSessionId().isBlank()
                            && seats.getSeatOSessionId() != null 
                            && !seats.getSeatOSessionId().isBlank();
                    if (hasTwoPlayers) {
                        continue;
                    }
                }
                
                // 只返回未删除、非PVE、未满员的房间
                items.add(RoomSummary.from(meta));
                if (meta.getCreatedAt() < minCreated) {
                    minCreated = meta.getCreatedAt();
                }
            }
            // 如果过滤后还有足够的房间，才设置 nextCursor
            if (!items.isEmpty() && items.size() >= limit && minCreated != Long.MAX_VALUE) {
                nextCursor = minCreated;
            }
        }

        return ApiResponse.success(new RoomListResponse(items, nextCursor, nextCursor != null));
    }
}

