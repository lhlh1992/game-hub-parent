package com.gamehub.chatservice.controller.http;

import com.gamehub.chatservice.service.ChatHistoryService;
import com.gamehub.chatservice.service.dto.ChatMessagePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天历史 HTTP 接口
 * 提供房间聊天和私聊历史记录的查询功能
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    /**
     * 获取房间最近的聊天记录（默认 50 条，时间升序）。
     *
     * @param roomId 房间 ID
     * @param limit  最大条数（<=0 使用默认 50）
     */
    @GetMapping("/rooms/{roomId}/history")
    public ResponseEntity<List<ChatMessagePayload>> listRoomHistory(
            @PathVariable("roomId") String roomId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        if (!StringUtils.hasText(roomId)) {
            return ResponseEntity.badRequest().build();
        }
        List<ChatMessagePayload> list = chatHistoryService.listRoomMessages(roomId, limit);
        return ResponseEntity.ok(list);
    }

    /**
     * 获取私聊历史记录
     * 查询当前用户与目标用户之间的私聊消息历史
     *
     * @param targetUserId 目标用户ID（Keycloak用户ID，String格式）
     * @param limit        最大条数（默认100）
     * @param jwt          JWT Token，用于获取当前用户ID
     * @return 消息列表（时间升序）
     */
    @GetMapping("/private/{targetUserId}/history")
    public ResponseEntity<List<ChatMessagePayload>> listPrivateHistory(
            @PathVariable("targetUserId") String targetUserId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || !StringUtils.hasText(jwt.getSubject())) {
            return ResponseEntity.status(401).build();
        }
        
        String currentUserId = jwt.getSubject();
        if (!StringUtils.hasText(targetUserId)) {
            return ResponseEntity.badRequest().build();
        }
        
        try {
            List<ChatMessagePayload> list = chatHistoryService.listPrivateMessages(currentUserId, targetUserId, limit);
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            log.error("获取私聊历史失败: currentUserId={}, targetUserId={}", currentUserId, targetUserId, e);
            return ResponseEntity.status(500).build();
        }
    }
}


