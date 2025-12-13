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
     * 查询会话中所有未撤回的消息数（用于计算未读数，当用户从未打开过会话时）
     *
     * @param sessionId 会话ID
     * @return 未读消息数量
     */
    @Query(value = "SELECT COUNT(*) FROM chat_message m " +
           "WHERE m.session_id = :sessionId " +
           "AND m.is_recalled = false",
           nativeQuery = true)
    long countAllUnreadMessages(@Param("sessionId") UUID sessionId);

    /**
     * 查询会话中指定时间之后的所有消息（用于计算未读数）
     * 
     * 注意：lastReadTime 不能为 null，如果为 null 请使用 countAllUnreadMessages
     *
     * @param sessionId 会话ID
     * @param lastReadTime 最后已读时间（不能为 null）
     * @return 未读消息数量
     */
    @Query(value = "SELECT COUNT(*) FROM chat_message m " +
           "WHERE m.session_id = :sessionId " +
           "AND m.is_recalled = false " +
           "AND m.created_at > :lastReadTime",
           nativeQuery = true)
    long countUnreadMessagesAfter(@Param("sessionId") UUID sessionId, 
                                  @Param("lastReadTime") java.time.OffsetDateTime lastReadTime);

    /**
     * 查询会话中最后一条消息
     *
     * @param sessionId 会话ID
     * @return 最后一条消息
     */
    Optional<ChatMessage> findFirstBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    /**
     * 查询会话中第一条消息（按时间正序）
     *
     * @param sessionId 会话ID
     * @return 第一条消息
     */
    Optional<ChatMessage> findFirstBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}

