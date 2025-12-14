package com.gamehub.chatservice.controller.ws.dto;

import lombok.Data;

/**
 * 私聊消息发送命令 DTO
 */
@Data
public class SendPrivateMessage {
    private String targetUserId; // 接收者用户ID（Keycloak用户ID，String格式）
    private String content;      // 消息内容
    private String clientOpId;   // 预留幂等键（客户端操作ID，用于去重）
}

