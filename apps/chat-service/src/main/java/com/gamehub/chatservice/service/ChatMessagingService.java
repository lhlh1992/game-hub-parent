package com.gamehub.chatservice.service;

/**
 * 聊天消息服务接口
 * 负责处理各种类型的聊天消息发送（大厅、房间、私聊）
 */
public interface ChatMessagingService {

    /**
     * 发送大厅消息
     *
     * @param userId  发送者用户ID（Keycloak用户ID，String格式）
     * @param content 消息内容
     */
    void sendLobbyMessage(String userId, String content);

    /**
     * 发送房间消息
     *
     * @param userId  发送者用户ID（Keycloak用户ID，String格式）
     * @param roomId  房间ID
     * @param content 消息内容
     */
    void sendRoomMessage(String userId, String roomId, String content);

    /**
     * 发送私聊消息
     * 使用点对点消息推送（/user/queue/chat.private），确保只有目标用户能收到
     *
     * @param senderId   发送者用户ID（Keycloak用户ID，String格式）
     * @param targetUserId 接收者用户ID（Keycloak用户ID，String格式）
     * @param content    消息内容
     * @param clientOpId 客户端操作ID（用于幂等，可选）
     * @return 是否发送成功（如果目标用户不在线或发送失败返回false）
     */
    boolean sendPrivateMessage(String senderId, String targetUserId, String content, String clientOpId);
}

