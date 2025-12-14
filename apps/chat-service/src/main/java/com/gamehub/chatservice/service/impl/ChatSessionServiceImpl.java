package com.gamehub.chatservice.service.impl;

import com.gamehub.chatservice.entity.ChatMessage;
import com.gamehub.chatservice.entity.ChatSession;
import com.gamehub.chatservice.entity.ChatSessionMember;
import com.gamehub.chatservice.infrastructure.client.SystemUserClient;
import com.gamehub.chatservice.repository.ChatMessageRepository;
import com.gamehub.chatservice.repository.ChatSessionMemberRepository;
import com.gamehub.chatservice.repository.ChatSessionRepository;
import com.gamehub.chatservice.service.ChatSessionService;
import com.gamehub.chatservice.service.UserProfileCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final UserProfileCacheService userProfileCacheService;
    private final SystemUserClient systemUserClient;

    @Override
    public UUID getOrCreatePrivateSessionId(UUID userId1, UUID userId2) {
        ChatSession session = getOrCreatePrivateSession(userId1, userId2);
        return session.getId();
    }

    @Override
    public UUID getPrivateSessionId(UUID userId1, UUID userId2) {
        // 只查询，不创建
        String supportKey = buildPrivateSessionKey(userId1, userId2);
        return sessionRepository.findBySupportKeyAndSessionType(supportKey, ChatSession.SessionType.PRIVATE)
                .map(ChatSession::getId)
                .orElse(null);
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
        OffsetDateTime readTime = null;
        
        if (readMessageId != null) {
            // 如果提供了 messageId，查询该消息的 created_at
            Optional<ChatMessage> readMessage = messageRepository.findById(readMessageId);
            if (readMessage.isPresent()) {
                readTime = readMessage.get().getCreatedAt();
            }
        } else {
            // 如果没有提供 messageId，查询最后一条消息
            Optional<ChatMessage> lastMessage = messageRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId);
            if (lastMessage.isPresent()) {
                readMessageId = lastMessage.get().getId();
                readTime = lastMessage.get().getCreatedAt();
            }
        }
        
        // 3. 更新已读状态
        // 优先使用消息的 created_at 作为 last_read_time，确保时间戳准确
        // 如果没有消息，使用当前时间（会话还没有消息的情况）
        member.setLastReadMessageId(readMessageId);
        member.setLastReadTime(readTime != null ? readTime : OffsetDateTime.now());
        memberRepository.save(member);
        log.debug("标记消息已读: sessionId={}, userId={}, messageId={}, readTime={}", 
                sessionId, userId, readMessageId, member.getLastReadTime());
    }

    @Override
    public long countUnreadMessages(UUID sessionId, UUID userId) {
        // 1. 获取成员的最后已读消息ID和已读时间
        ChatSessionMember member = memberRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElse(null);

        // 2. 如果成员不存在，返回所有未撤回的消息数（排除自己发的消息）
        if (member == null) {
            return messageRepository.countAllUnreadMessages(sessionId, userId);
        }

        // 3. 如果 last_read_time 为 null，说明用户从未打开过会话，返回所有未撤回的消息数（排除自己发的消息）
        if (member.getLastReadTime() == null) {
            return messageRepository.countAllUnreadMessages(sessionId, userId);
        }

        // 4. 计算未读消息数（基于时间戳比较，避免子查询）
        // 直接使用 last_read_time 进行计算，因为标记已读时已经确保 last_read_time 是准确的
        // 这样可以避免因为查询 last_read_message_id 对应的消息时可能的时间差或精度问题
        // 同时排除用户自己发的消息（自己发的消息不算未读）
        OffsetDateTime lastReadTime = member.getLastReadTime();
        
        // 此时 lastReadTime 一定不为 null（因为前面已经判断过），使用 countUnreadMessagesAfter
        // 查询 created_at > lastReadTime 且 sender_id != userId 的消息数
        return messageRepository.countUnreadMessagesAfter(sessionId, lastReadTime, userId);
    }

    @Override
    public List<SessionInfo> listUserSessions(UUID userId) {
        try {
            // 1. 查询用户参与的所有会话（通过成员表）
            List<ChatSession> sessionsByMember = sessionRepository.findSessionsByUserId(userId);
            log.debug("通过成员表查询到 {} 个会话", sessionsByMember.size());
            
            // 2. 查询用户有消息的私聊会话（通过消息表，补充成员表可能缺失的情况）
            List<ChatSession> sessionsByMessage = sessionRepository.findPrivateSessionsWithMessagesByUserId(userId, ChatSession.SessionType.PRIVATE);
            log.debug("通过消息表查询到 {} 个会话", sessionsByMessage.size());
            
            // 3. 合并两个列表，去重（使用 sessionId 作为唯一标识）
            java.util.Map<UUID, ChatSession> sessionMap = new java.util.HashMap<>();
            if (sessionsByMember != null) {
                sessionsByMember.forEach(s -> sessionMap.put(s.getId(), s));
            }
            if (sessionsByMessage != null) {
                sessionsByMessage.forEach(s -> sessionMap.putIfAbsent(s.getId(), s));
            }
            
            List<ChatSession> allSessions = new ArrayList<>(sessionMap.values());
            log.debug("合并后共有 {} 个会话", allSessions.size());

        // 4. 为每个会话查询成员信息和未读数
        return allSessions.stream()
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
                        // 方法1：从成员表查询
                        List<ChatSessionMember> allMembers = memberRepository.findBySessionIdAndLeftAtIsNull(session.getId());
                        otherUserId = allMembers.stream()
                                .filter(m -> !m.getUserId().equals(userId))
                                .map(ChatSessionMember::getUserId)
                                .findFirst()
                                .orElse(null);
                        
                        // 方法2：如果成员表查不到，从 support_key 解析（格式：min(user1,user2)|max(user1,user2)）
                        if (otherUserId == null && session.getSupportKey() != null) {
                            String[] parts = session.getSupportKey().split("\\|");
                            if (parts.length == 2) {
                                try {
                                    UUID user1 = UUID.fromString(parts[0]);
                                    UUID user2 = UUID.fromString(parts[1]);
                                    otherUserId = user1.equals(userId) ? user2 : user1;
                                } catch (Exception e) {
                                    log.warn("解析 support_key 失败: supportKey={}", session.getSupportKey(), e);
                                }
                            }
                        }
                        
                        // 方法3：如果还是查不到，从消息中推断（查询所有消息的发送者，找出不是当前用户的）
                        if (otherUserId == null) {
                            // 先尝试从最后一条消息
                            if (lastMessage != null && !lastMessage.getSenderId().equals(userId)) {
                                otherUserId = lastMessage.getSenderId();
                            } else {
                                // 如果最后一条消息是当前用户发的，查询第一条消息
                                Optional<ChatMessage> firstMessage = messageRepository.findFirstBySessionIdOrderByCreatedAtAsc(session.getId());
                                if (firstMessage.isPresent() && !firstMessage.get().getSenderId().equals(userId)) {
                                    otherUserId = firstMessage.get().getSenderId();
                                }
                            }
                        }
                    }

                    return new SessionInfo(session, member, unreadCount, lastMessage, otherUserId);
                })
                .filter(info -> {
                    // 过滤掉无法确定对方用户的私聊会话（避免前端显示错误）
                    if (info.session().getSessionType() == ChatSession.SessionType.PRIVATE) {
                        if (info.otherUserId() == null) {
                            log.warn("私聊会话无法确定对方用户，已过滤: sessionId={}, userId={}", 
                                    info.session().getId(), userId);
                        }
                        return info.otherUserId() != null;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("查询用户会话列表失败: userId={}", userId, e);
            // 返回空列表而不是抛出异常，避免500错误
            return new ArrayList<>();
        }
    }

    @Override
    public List<SessionInfoWithUser> listUserSessionsWithUserInfo(UUID userId) {
        try {
            // 1. 先获取基础会话列表
            List<SessionInfo> baseSessions = listUserSessions(userId);

            // 2. 收集所有私聊会话的对方用户ID
            List<String> otherUserIds = baseSessions.stream()
                    .filter(info -> info.session().getSessionType() == ChatSession.SessionType.PRIVATE
                            && info.otherUserId() != null)
                    .map(info -> info.otherUserId().toString())
                    .distinct()
                    .collect(Collectors.toList());

            // 3. 批量获取用户信息（使用缓存和并行处理）
            Map<String, UserProfileCacheService.UserProfileView> userInfoMap = userProfileCacheService.batchGet(
                    otherUserIds,
                    userIdStr -> {
                        try {
                            SystemUserClient.UserInfo userInfo = systemUserClient.getUserInfo(userIdStr);
                            if (userInfo != null) {
                                return new UserProfileCacheService.UserProfileView(
                                        userInfo.userId(),
                                        userInfo.username(),
                                        userInfo.nickname(),
                                        userInfo.avatarUrl()
                                );
                            }
                        } catch (Exception e) {
                            log.warn("获取用户信息失败: userId={}", userIdStr, e);
                        }
                        return null;
                    }
            );

            // 4. 构建包含用户信息的会话列表
            return baseSessions.stream()
                    .map(info -> {
                        String otherUserNickname = null;
                        String otherUserAvatarUrl = null;

                        if (info.otherUserId() != null) {
                            UserProfileCacheService.UserProfileView userProfile = userInfoMap.get(info.otherUserId().toString());
                            if (userProfile != null) {
                                // 优先使用 nickname，如果没有则使用 username
                                otherUserNickname = StringUtils.hasText(userProfile.nickname())
                                        ? userProfile.nickname()
                                        : userProfile.username();
                                otherUserAvatarUrl = userProfile.avatarUrl();
                            }
                        }

                        return new ChatSessionService.SessionInfoWithUser(
                                info.session(),
                                info.member(),
                                info.unreadCount(),
                                info.lastMessage(),
                                info.otherUserId(),
                                otherUserNickname,
                                otherUserAvatarUrl
                        );
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("查询用户会话列表（含用户信息）失败: userId={}", userId, e);
            // 返回空列表而不是抛出异常，避免500错误
            return new ArrayList<>();
        }
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

