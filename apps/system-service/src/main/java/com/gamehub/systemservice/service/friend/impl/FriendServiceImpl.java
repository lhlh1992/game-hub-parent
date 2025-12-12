package com.gamehub.systemservice.service.friend.impl;

import com.gamehub.systemservice.entity.friend.FriendRequest;
import com.gamehub.systemservice.entity.friend.UserFriend;
import com.gamehub.systemservice.entity.user.SysUser;
import com.gamehub.systemservice.exception.BusinessException;
import com.gamehub.systemservice.repository.friend.FriendRequestRepository;
import com.gamehub.systemservice.repository.friend.UserFriendRepository;
import com.gamehub.systemservice.repository.user.SysUserRepository;
import com.gamehub.systemservice.service.friend.FriendService;
import com.gamehub.systemservice.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 好友服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

    private final FriendRequestRepository friendRequestRepository;
    private final UserFriendRepository userFriendRepository;
    private final SysUserRepository sysUserRepository;
    private final NotificationService notificationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean applyFriend(String requesterKeycloakUserId, String targetKeycloakUserId, String requestMessage) {
        // 1. 参数验证：不能添加自己为好友
        if (requesterKeycloakUserId.equals(targetKeycloakUserId)) {
            throw new BusinessException(400, "不能添加自己为好友");
        }

        // 预处理留言：去除首尾空格，空字符串视为 null，并裁剪至 200 字符
        String normalizedMessage = normalizeRequestMessage(requestMessage);
        log.debug("留言规范化: 原始={}, 规范化后={}", requestMessage, normalizedMessage);

        // 2. 将 Keycloak 用户ID 转换为 UUID
        UUID requesterKeycloakUuid;
        UUID targetKeycloakUuid;
        try {
            requesterKeycloakUuid = UUID.fromString(requesterKeycloakUserId);
            targetKeycloakUuid = UUID.fromString(targetKeycloakUserId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "无效的用户ID格式");
        }

        // 3. 查询申请人系统用户ID
        SysUser requesterUser = sysUserRepository.findByKeycloakUserIdAndNotDeleted(requesterKeycloakUuid)
                .filter(user -> user.getDeletedAt() == null && user.isEnabled())
                .orElseThrow(() -> new BusinessException(404, "当前用户不存在或已被禁用"));

        // 4. 查询目标用户系统用户ID
        SysUser targetUser = sysUserRepository.findByKeycloakUserIdAndNotDeleted(targetKeycloakUuid)
                .filter(user -> user.getDeletedAt() == null && user.isEnabled())
                .orElseThrow(() -> new BusinessException(404, "目标用户不存在或已被禁用"));

        UUID requesterId = requesterUser.getId();
        UUID targetId = targetUser.getId();

        // 5. 检查是否已经是好友
        if (userFriendRepository.existsActiveFriendRelation(requesterId, targetId)) {
            throw new BusinessException(409, "你们已经是好友了");
        }

        // 6. 检查是否已有待处理的申请
        Optional<FriendRequest> existingRequest = friendRequestRepository.findPendingRequest(requesterId, targetId);
        if (existingRequest.isPresent()) {
            throw new BusinessException(409, "已发送申请，等待对方处理");
        }

        // 7. 检查是否存在反向的待处理申请（双向申请自动通过）
        Optional<FriendRequest> reverseRequest = friendRequestRepository.findReversePendingRequest(requesterId, targetId);
        
        if (reverseRequest.isPresent()) {
            // 双向申请自动通过
            return handleMutualRequest(requesterId, targetId, reverseRequest.get(), normalizedMessage);
        } else {
            // 正常申请流程：创建新的申请记录
            FriendRequest created = createFriendRequest(requesterId, targetId, normalizedMessage);
            // 推送通知给接收方（落库 + WS）
            notificationService.notifyFriendRequest(
                    targetUser.getId(),
                    targetUser.getKeycloakUserId().toString(),
                    requesterUser.getKeycloakUserId().toString(),
                    resolveDisplayName(requesterUser),
                    created.getId(),
                    normalizedMessage
            );
            return false;
        }
    }

    /**
     * 处理双向申请自动通过
     * 
     * @param requesterId 申请人ID
     * @param targetUserId 目标用户ID
     * @param reverseRequest 反向申请记录
     * @return true 表示自动成为好友
     */
    private boolean handleMutualRequest(UUID requesterId, UUID targetUserId, FriendRequest reverseRequest, String requestMessage) {
        log.info("检测到双向申请，自动通过: requesterId={}, targetUserId={}", requesterId, targetUserId);
        
        OffsetDateTime now = OffsetDateTime.now();
        
        // 1. 更新反向申请状态为ACCEPTED
        reverseRequest.setStatus(FriendRequest.RequestStatus.ACCEPTED);
        reverseRequest.setHandledAt(now);
        friendRequestRepository.save(reverseRequest);
        
        // 2. 创建正向申请记录并设置为ACCEPTED
        FriendRequest newRequest = FriendRequest.builder()
                .requesterId(requesterId)
                .receiverId(targetUserId)
                .requestMessage(requestMessage)
                .status(FriendRequest.RequestStatus.ACCEPTED)
                .handledAt(now)
                .build();
        friendRequestRepository.save(newRequest);
        
        // 3. 创建两条好友关系记录（双向）
        UserFriend relation1 = UserFriend.builder()
                .userId(requesterId)
                .friendId(targetUserId)
                .status(UserFriend.FriendStatus.ACTIVE)
                .isFavorite(false)
                .lastInteractionTime(now)
                .build();
        
        UserFriend relation2 = UserFriend.builder()
                .userId(targetUserId)
                .friendId(requesterId)
                .status(UserFriend.FriendStatus.ACTIVE)
                .isFavorite(false)
                .lastInteractionTime(now)
                .build();
        
        userFriendRepository.save(relation1);
        userFriendRepository.save(relation2);
        
        log.info("双向申请自动通过完成，已建立好友关系: userId1={}, userId2={}", requesterId, targetUserId);
        return true;
    }

    /**
     * 创建好友申请记录
     * 
     * @param requesterId 申请人ID
     * @param targetUserId 目标用户ID
     * @param requestMessage 申请留言（可选）
     */
    private FriendRequest createFriendRequest(UUID requesterId, UUID targetUserId, String requestMessage) {
        FriendRequest request = FriendRequest.builder()
                .requesterId(requesterId)
                .receiverId(targetUserId)
                .requestMessage(requestMessage)
                .status(FriendRequest.RequestStatus.PENDING)
                .build();
        
        FriendRequest saved = friendRequestRepository.save(request);
        log.info("创建好友申请: requesterId={}, receiverId={}, message={}, savedMessage={}", 
                requesterId, targetUserId, requestMessage, saved.getRequestMessage());
        return saved;
    }

    /**
     * 留言规范化：去除首尾空格，空串返回 null，超长裁剪到 200 字符
     */
    private String normalizeRequestMessage(String requestMessage) {
        if (requestMessage == null) {
            return null;
        }
        String trimmed = requestMessage.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // 保护性裁剪，避免超出数据库字段长度
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    /**
     * 申请人展示名（用于通知文案）。
     */
    private String resolveDisplayName(com.gamehub.systemservice.entity.user.SysUser user) {
        if (user == null) {
            return "玩家";
        }
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return user.getKeycloakUserId() != null ? user.getKeycloakUserId().toString() : "玩家";
    }
}


