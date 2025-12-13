package com.gamehub.chatservice.service;

import com.gamehub.chatservice.entity.ChatMessage;
import com.gamehub.chatservice.entity.ChatSession;
import com.gamehub.chatservice.entity.ChatSessionMember;

import java.util.List;
import java.util.UUID;

/**
 * 聊天会话服务接口
 * 负责管理会话、消息持久化和未读消息计数
 */
public interface ChatSessionService {

    /**
     * 获取或创建私聊会话
     * 如果会话已存在，直接返回；否则创建新会话
     *
     * @param userId1 用户1的Keycloak用户ID（UUID格式）
     * @param userId2 用户2的Keycloak用户ID（UUID格式）
     * @return 会话
     */
    ChatSession getOrCreatePrivateSession(UUID userId1, UUID userId2);

    /**
     * 通过两个用户ID获取私聊会话ID（如果不存在则创建）
     * 用于发送消息时创建会话
     *
     * @param userId1 用户1的Keycloak用户ID（UUID格式）
     * @param userId2 用户2的Keycloak用户ID（UUID格式）
     * @return 会话ID
     */
    UUID getOrCreatePrivateSessionId(UUID userId1, UUID userId2);

    /**
     * 通过两个用户ID查询私聊会话ID（仅查询，不创建）
     * 用于前端获取 sessionId 以便标记已读
     *
     * @param userId1 用户1的Keycloak用户ID（UUID格式）
     * @param userId2 用户2的Keycloak用户ID（UUID格式）
     * @return 会话ID，如果不存在则返回 null
     */
    UUID getPrivateSessionId(UUID userId1, UUID userId2);

    /**
     * 保存私聊消息到数据库
     * 同时更新会话的最后消息信息，并更新成员的未读状态
     *
     * @param sessionId 会话ID
     * @param senderId  发送者用户ID（Keycloak用户ID，UUID格式）
     * @param content   消息内容
     * @param clientOpId 客户端操作ID（用于幂等，可选）
     * @return 保存的消息
     */
    ChatMessage savePrivateMessage(UUID sessionId, UUID senderId, String content, String clientOpId);

    /**
     * 查询会话的消息列表（按时间倒序）
     *
     * @param sessionId 会话ID
     * @param limit     最大条数
     * @return 消息列表
     */
    List<ChatMessage> listMessages(UUID sessionId, int limit);

    /**
     * 标记会话消息为已读
     * 更新成员的 last_read_message_id 和 last_read_time
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（Keycloak用户ID，UUID格式）
     * @param messageId 已读到的消息ID（可选，如果不提供则标记为最后一条消息）
     */
    void markAsRead(UUID sessionId, UUID userId, UUID messageId);

    /**
     * 计算会话的未读消息数
     * 未读数 = 会话中最后一条消息ID > 该用户最后已读消息ID 的消息数量
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（Keycloak用户ID，UUID格式）
     * @return 未读消息数
     */
    long countUnreadMessages(UUID sessionId, UUID userId);

    /**
     * 查询用户的所有会话列表（包含未读数）
     *
     * @param userId 用户ID（Keycloak用户ID，UUID格式）
     * @return 会话列表（包含未读数信息）
     */
    List<SessionInfo> listUserSessions(UUID userId);

    /**
     * 查询用户的所有会话列表（包含未读数和用户信息）
     * 优化版本：批量获取用户信息，使用缓存和并行处理
     *
     * @param userId 用户ID（Keycloak用户ID，UUID格式）
     * @return 会话列表（包含未读数和用户信息）
     */
    List<SessionInfoWithUser> listUserSessionsWithUserInfo(UUID userId);

    /**
     * 会话信息DTO（包含未读数）
     * 
     * @param session 会话实体
     * @param member 当前用户的成员记录
     * @param unreadCount 未读消息数
     * @param lastMessage 最后一条消息
     * @param otherUserId 对方用户ID（仅私聊会话使用，用于前端匹配）
     */
    record SessionInfo(
            ChatSession session,
            ChatSessionMember member,
            long unreadCount,
            ChatMessage lastMessage,
            UUID otherUserId
    ) {}

    /**
     * 会话信息DTO（包含未读数和用户信息）
     * 
     * @param session 会话实体
     * @param member 当前用户的成员记录
     * @param unreadCount 未读消息数
     * @param lastMessage 最后一条消息
     * @param otherUserId 对方用户ID（仅私聊会话使用）
     * @param otherUserNickname 对方用户昵称（仅私聊会话使用）
     * @param otherUserAvatarUrl 对方用户头像URL（仅私聊会话使用）
     */
    record SessionInfoWithUser(
            ChatSession session,
            ChatSessionMember member,
            long unreadCount,
            ChatMessage lastMessage,
            UUID otherUserId,
            String otherUserNickname,
            String otherUserAvatarUrl
    ) {}
}

