package com.gamehub.chatservice.repository;

import com.gamehub.chatservice.entity.ChatSessionMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 聊天会话成员 Repository
 */
@Repository
public interface ChatSessionMemberRepository extends JpaRepository<ChatSessionMember, UUID> {

    /**
     * 根据会话ID和用户ID查询成员
     *
     * @param sessionId 会话ID
     * @param userId     用户ID
     * @return 成员
     */
    Optional<ChatSessionMember> findBySessionIdAndUserId(UUID sessionId, UUID userId);

    /**
     * 查询会话的所有成员（未离开的）
     *
     * @param sessionId 会话ID
     * @return 成员列表
     */
    List<ChatSessionMember> findBySessionIdAndLeftAtIsNull(UUID sessionId);

    /**
     * 查询用户参与的所有会话成员记录（未离开的）
     *
     * @param userId 用户ID
     * @return 成员列表
     */
    List<ChatSessionMember> findByUserIdAndLeftAtIsNull(UUID userId);

    /**
     * 统计会话的成员数量（未离开的）
     *
     * @param sessionId 会话ID
     * @return 成员数量
     */
    @Query("SELECT COUNT(m) FROM ChatSessionMember m " +
           "WHERE m.sessionId = :sessionId AND m.leftAt IS NULL")
    long countActiveMembersBySessionId(@Param("sessionId") UUID sessionId);
}

