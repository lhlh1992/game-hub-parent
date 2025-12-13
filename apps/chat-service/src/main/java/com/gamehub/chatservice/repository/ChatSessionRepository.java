package com.gamehub.chatservice.repository;

import com.gamehub.chatservice.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 聊天会话 Repository
 */
@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    /**
     * 根据私聊会话唯一键查询会话（用于私聊去重）
     *
     * @param supportKey 私聊会话唯一键
     * @return 会话
     */
    Optional<ChatSession> findBySupportKeyAndSessionType(String supportKey, ChatSession.SessionType sessionType);

    /**
     * 根据房间ID查询房间会话
     *
     * @param roomId 房间ID
     * @return 会话
     */
    Optional<ChatSession> findByRoomIdAndSessionType(UUID roomId, ChatSession.SessionType sessionType);

    /**
     * 查询用户参与的所有会话（通过成员表关联）
     * 按最后消息时间倒序排列
     *
     * @param userId 用户ID
     * @return 会话列表
     */
    @Query("SELECT DISTINCT s FROM ChatSession s " +
           "INNER JOIN ChatSessionMember m ON s.id = m.sessionId " +
           "WHERE m.userId = :userId AND m.leftAt IS NULL " +
           "ORDER BY s.lastMessageTime DESC NULLS LAST, s.createdAt DESC")
    List<ChatSession> findSessionsByUserId(@Param("userId") UUID userId);

    /**
     * 查询用户有消息的私聊会话（通过消息表关联，即使成员记录缺失也能找到）
     * 按最后消息时间倒序排列
     * 
     * 用于补充查询：找出用户发送过或接收过消息的私聊会话
     * 查询条件：会话中有消息，且（用户是发送者 OR 用户是成员）
     *
     * @param userId 用户ID
     * @return 会话列表
     */
    @Query("SELECT DISTINCT s FROM ChatSession s " +
           "INNER JOIN ChatMessage msg ON s.id = msg.sessionId " +
           "WHERE s.sessionType = :sessionType " +
           "AND msg.isRecalled = false " +
           "AND (msg.senderId = :userId OR EXISTS (SELECT 1 FROM ChatSessionMember csm WHERE csm.sessionId = s.id AND csm.userId = :userId)) " +
           "ORDER BY s.lastMessageTime DESC NULLS LAST, s.createdAt DESC")
    List<ChatSession> findPrivateSessionsWithMessagesByUserId(@Param("userId") UUID userId, 
                                                              @Param("sessionType") ChatSession.SessionType sessionType);
}

