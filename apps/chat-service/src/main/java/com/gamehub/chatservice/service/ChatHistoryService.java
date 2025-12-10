package com.gamehub.chatservice.service;

import com.gamehub.chatservice.service.dto.ChatMessagePayload;

import java.util.List;

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
}


