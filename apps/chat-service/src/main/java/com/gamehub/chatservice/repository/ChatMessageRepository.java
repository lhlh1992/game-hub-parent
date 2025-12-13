package com.gamehub.chatservice.repository;

import com.gamehub.chatservice.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 聊天消息 Repository
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /**
     * 根据客户端操作ID查询消息（用于幂等检查）
     *
     * @param clientOpId 客户端操作ID
     * @return 消息
     */
    Optional<ChatMessage> findByClientOpId(String clientOpId);

    /**
     * 查询会话的消息列表（按时间倒序）
     *
     * @param sessionId 会话ID
     * @param pageable  分页参数
     * @return 消息列表
     */
    List<ChatMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

    /**
     * 查询会话中指定消息之后的所有消息（用于计算未读数）
     * 
     * 注意：如果 lastReadMessageId 为 null，表示用户还没有已读任何消息，返回所有未撤回的消息数
     * 如果 lastReadMessageId 不为 null，返回 id > lastReadMessageId 的未撤回消息数
     *
     * @param sessionId 会话ID
     * @param lastReadMessageId 最后已读消息ID（null 表示未读任何消息）
     * @return 未读消息数量
     */
    @Query("SELECT COUNT(m) FROM ChatMessage m " +
           "WHERE m.sessionId = :sessionId " +
           "AND m.isRecalled = false " +
           "AND (:lastReadMessageId IS NULL OR m.id > :lastReadMessageId)")
    long countUnreadMessages(@Param("sessionId") UUID sessionId, 
                             @Param("lastReadMessageId") UUID lastReadMessageId);

    /**
     * 查询会话中最后一条消息
     *
     * @param sessionId 会话ID
     * @return 最后一条消息
     */
    Optional<ChatMessage> findFirstBySessionIdOrderByCreatedAtDesc(UUID sessionId);
}
