package com.gamehub.chatservice.controller.http;

import com.gamehub.chatservice.service.ChatHistoryService;
import com.gamehub.chatservice.service.dto.ChatMessagePayload;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 房间聊天历史查询接口。
 */
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    /**
     * 获取房间最近的聊天记录（默认 50 条，时间升序）。
     *
     * @param roomId 房间 ID
     * @param limit  最大条数（<=0 使用默认 50）
     */
    @GetMapping("/{roomId}/history")
    public ResponseEntity<List<ChatMessagePayload>> listRoomHistory(
            @PathVariable("roomId") String roomId,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        if (!StringUtils.hasText(roomId)) {
            return ResponseEntity.badRequest().build();
        }
        List<ChatMessagePayload> list = chatHistoryService.listRoomMessages(roomId, limit);
        return ResponseEntity.ok(list);
    }
}


