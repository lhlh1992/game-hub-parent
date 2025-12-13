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
}
