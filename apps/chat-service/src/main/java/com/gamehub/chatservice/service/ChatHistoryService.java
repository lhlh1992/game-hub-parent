package com.gamehub.chatservice.service;

import com.gamehub.chatservice.service.dto.ChatMessagePayload;

import java.util.List;

/**
 * 聊天历史服务接口
 * 负责管理房间聊天和私聊消息的历史记录（存储在 Redis）
 */
public interface ChatHistoryService {

    /**
     * 追加一条房间消息到历史，并按 maxSize 进行截断（保留最近 N 条）。
     *
     * @param payload 消息载体（必需 roomId）
     * @param maxSize 保留的最大条数（<=0 使用默认 50）
     */
    void appendRoomMessage(ChatMessagePayload payload, int maxSize);

    /**
     * 获取房间最近 N 条消息（按时间顺序）。
     *
     * @param roomId 房间 ID
     * @param limit  最大条数（<=0 使用默认 50）
     * @return 消息列表（时间升序）
     */
    List<ChatMessagePayload> listRoomMessages(String roomId, int limit);

    /**
     * 删除指定房间的聊天历史（房间销毁时调用）。
     *
     * @param roomId 房间 ID
     */
    void deleteRoomMessages(String roomId);

    /**
     * 追加一条私聊消息到历史，并按 maxSize 进行截断（保留最近 N 条）。
     * 私聊消息会双向存储：发送方和接收方都能看到完整的历史记录。
     *
     * @param payload 消息载体（必需 senderId 和 targetUserId，type 为 PRIVATE）
     * @param maxSize 保留的最大条数（<=0 使用默认 100）
     */
    void appendPrivateMessage(ChatMessagePayload payload, int maxSize);

    /**
     * 获取两个用户之间的私聊历史记录（按时间顺序）。
     * 会话ID由两个用户ID按字典序排序后拼接而成，确保双方使用相同的key。
     *
     * @param userId1 用户1的Keycloak用户ID（String格式）
     * @param userId2 用户2的Keycloak用户ID（String格式）
     * @param limit   最大条数（<=0 使用默认 100）
     * @return 消息列表（时间升序）
     */
    List<ChatMessagePayload> listPrivateMessages(String userId1, String userId2, int limit);
}


