package com.gamehub.chatservice.service.impl;

import com.gamehub.chatservice.entity.ChatMessage;
import com.gamehub.chatservice.entity.ChatSession;
import com.gamehub.chatservice.entity.ChatSessionMember;
import com.gamehub.chatservice.repository.ChatMessageRepository;
import com.gamehub.chatservice.repository.ChatSessionMemberRepository;
import com.gamehub.chatservice.repository.ChatSessionRepository;
import com.gamehub.chatservice.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天会话服务实现类
 * 负责管理会话、消息持久化和未读消息计数
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatSessionMemberRepository memberRepository;
    private final ChatMessageRepository messageRepository;

    @Override
    public UUID getOrCreatePrivateSessionId(UUID userId1, UUID userId2) {
        ChatSession session = getOrCreatePrivateSession(userId1, userId2);
        return session.getId();
    }

    @Override
    @Transactional
    public ChatSession getOrCreatePrivateSession(UUID userId1, UUID userId2) {
        // 1. 生成私聊会话唯一键（按用户ID字典序排序，确保双方使用相同的key）
        String supportKey = buildPrivateSessionKey(userId1, userId2);

        // 2. 查询是否已存在会话
        return sessionRepository.findBySupportKeyAndSessionType(supportKey, ChatSession.SessionType.PRIVATE)
                .orElseGet(() -> {
                    // 3. 创建新会话
                    ChatSession session = ChatSession.builder()
                            .sessionType(ChatSession.SessionType.PRIVATE)
                            .supportKey(supportKey)
                            .memberCount(2) // 私聊固定2人
                            .build();
                    session = sessionRepository.save(session);

                    // 4. 创建两个成员记录
                    ChatSessionMember member1 = ChatSessionMember.builder()
                            .sessionId(session.getId())
                            .userId(userId1)
                            .memberRole(ChatSessionMember.MemberRole.MEMBER)
                            .build();
                    ChatSessionMember member2 = ChatSessionMember.builder()
                            .sessionId(session.getId())
                            .userId(userId2)
                            .memberRole(ChatSessionMember.MemberRole.MEMBER)
                            .build();

                    memberRepository.save(member1);
                    memberRepository.save(member2);

                    log.debug("创建私聊会话: sessionId={}, userId1={}, userId2={}", 
                            session.getId(), userId1, userId2);
                    return session;
                });
    }

    @Override
    @Transactional
    public ChatMessage savePrivateMessage(UUID sessionId, UUID senderId, String content, String clientOpId) {
        // 1. 幂等检查：如果提供了 clientOpId，检查是否已存在
        if (clientOpId != null && !clientOpId.isBlank()) {
            messageRepository.findByClientOpId(clientOpId).ifPresent(existing -> {
                log.debug("消息已存在（幂等）: clientOpId={}, messageId={}", clientOpId, existing.getId());
                throw new IllegalStateException("消息已存在");
            });
        }

        // 2. 创建消息
        ChatMessage message = ChatMessage.builder()
                .sessionId(sessionId)
                .senderId(senderId)
                .clientOpId(clientOpId)
                .messageType(ChatMessage.MessageType.TEXT)
                .content(content)
                .build();
        message = messageRepository.save(message);

        // 3. 更新会话的最后消息信息
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        session.setLastMessageId(message.getId());
        session.setLastMessageTime(message.getCreatedAt());
        sessionRepository.save(session);

        log.debug("保存私聊消息: sessionId={}, messageId={}, senderId={}", 
                sessionId, message.getId(), senderId);

        return message;
    }

    @Override
    public List<ChatMessage> listMessages(UUID sessionId, int limit) {
        Pageable pageable = PageRequest.of(0, limit > 0 ? limit : 100);
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
        // 反转列表，按时间正序返回（最早的在前面）
        List<ChatMessage> result = new ArrayList<>(messages);
        result.sort(Comparator.comparing(ChatMessage::getCreatedAt));
        return result;
    }

    @Override
    @Transactional
    public void markAsRead(UUID sessionId, UUID userId, UUID messageId) {
        // 1. 获取成员记录
        ChatSessionMember member = memberRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不是该会话的成员"));

        // 2. 如果提供了 messageId，使用它；否则使用会话的最后一条消息ID
        UUID readMessageId = messageId;
        if (readMessageId == null) {
            readMessageId = messageRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId)
                    .map(ChatMessage::getId)
                    .orElse(null);
        }

        // 3. 更新已读状态
        // 即使没有消息（readMessageId 为 null），也要更新 last_read_time
        // 这样在计算未读数时，如果 last_read_time 不为 null，即使 last_read_message_id 为 null，也能正确判断
        member.setLastReadMessageId(readMessageId);
        member.setLastReadTime(OffsetDateTime.now());
        memberRepository.save(member);
        log.debug("标记消息已读: sessionId={}, userId={}, messageId={}", 
                sessionId, userId, readMessageId);
    }

    @Override
    public long countUnreadMessages(UUID sessionId, UUID userId) {
        // 1. 获取成员的最后已读消息ID和已读时间
        ChatSessionMember member = memberRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElse(null);

        // 2. 如果成员不存在，返回所有未撤回的消息数
        if (member == null) {
            return messageRepository.countUnreadMessages(sessionId, null);
        }

        // 3. 如果 last_read_time 不为 null，说明用户已经打开过会话
        // 即使 last_read_message_id 为 null（会话没有消息），也应该认为已读
        if (member.getLastReadTime() != null && member.getLastReadMessageId() == null) {
            // 用户已经打开过会话，但会话还没有消息，未读数为0
            return 0;
        }

        // 4. 如果 last_read_time 为 null，说明用户从未打开过会话，返回所有未撤回的消息数
        if (member.getLastReadTime() == null) {
            return messageRepository.countUnreadMessages(sessionId, null);
        }

        // 5. 计算未读消息数（基于 last_read_message_id）
        UUID lastReadMessageId = member.getLastReadMessageId();
        return messageRepository.countUnreadMessages(sessionId, lastReadMessageId);
    }

    @Override
    public List<SessionInfo> listUserSessions(UUID userId) {
        // 1. 查询用户参与的所有会话
        List<ChatSession> sessions = sessionRepository.findSessionsByUserId(userId);

        // 2. 为每个会话查询成员信息和未读数
        return sessions.stream()
                .map(session -> {
                    // 查询成员记录
                    ChatSessionMember member = memberRepository.findBySessionIdAndUserId(session.getId(), userId)
                            .orElse(null);

                    // 计算未读数
                    long unreadCount = countUnreadMessages(session.getId(), userId);

                    // 查询最后一条消息
                    ChatMessage lastMessage = messageRepository.findFirstBySessionIdOrderByCreatedAtDesc(session.getId())
                            .orElse(null);

                    // 对于私聊会话，查询对方用户ID（用于前端匹配）
                    UUID otherUserId = null;
                    if (session.getSessionType() == ChatSession.SessionType.PRIVATE) {
                        List<ChatSessionMember> allMembers = memberRepository.findBySessionIdAndLeftAtIsNull(session.getId());
                        otherUserId = allMembers.stream()
                                .filter(m -> !m.getUserId().equals(userId))
                                .map(ChatSessionMember::getUserId)
                                .findFirst()
                                .orElse(null);
                    }

                    return new SessionInfo(session, member, unreadCount, lastMessage, otherUserId);
                })
                .collect(Collectors.toList());
    }

    /**
     * 构建私聊会话唯一键
     * 按用户ID字典序排序后拼接，确保双方使用相同的key
     *
     * @param userId1 用户1的Keycloak用户ID
     * @param userId2 用户2的Keycloak用户ID
     * @return 会话唯一键
     */
    private String buildPrivateSessionKey(UUID userId1, UUID userId2) {
        // 按字典序排序，确保无论传入顺序如何，都生成相同的key
        if (userId1.compareTo(userId2) <= 0) {
            return userId1.toString() + "|" + userId2.toString();
        } else {
            return userId2.toString() + "|" + userId1.toString();
        }
    }
}
