package com.gamehub.chatservice.controller.ws.dto;

import lombok.Data;

/**
 * 通用消息发送命令 DTO（用于大厅和房间）
 */
@Data
public class SendMessage {
    private String roomId;      // 房间ID（大厅消息为 null）
    private String content;     // 消息内容
    private String clientOpId;  // 预留幂等键（客户端操作ID，用于去重）
}
