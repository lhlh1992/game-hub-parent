package com.gamehub.systemservice.service.friend.impl;

import com.gamehub.systemservice.dto.response.FriendInfo;
import com.gamehub.systemservice.dto.response.UserInfo;
import com.gamehub.systemservice.entity.friend.FriendRequest;
import com.gamehub.systemservice.entity.friend.UserFriend;
import com.gamehub.systemservice.entity.user.SysUser;
import com.gamehub.systemservice.exception.BusinessException;
import com.gamehub.systemservice.repository.friend.FriendRequestRepository;
import com.gamehub.systemservice.repository.friend.UserFriendRepository;
import com.gamehub.systemservice.repository.user.SysUserRepository;
import com.gamehub.systemservice.service.friend.FriendService;
import com.gamehub.systemservice.service.notification.NotificationService;
import com.gamehub.systemservice.service.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final UserService userService;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptFriendRequest(String receiverKeycloakUserId, UUID requestId) {
        handleFriendRequest(receiverKeycloakUserId, requestId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectFriendRequest(String receiverKeycloakUserId, UUID requestId) {
        handleFriendRequest(receiverKeycloakUserId, requestId, false);
    }

    /**
     * 处理好友申请（同意/拒绝）
     *
     * @param receiverKeycloakUserId 当前登录用户（接收方）Keycloak ID
     * @param requestId              好友申请 ID
     * @param accept                 true 同意，false 拒绝
     */
    private void handleFriendRequest(String receiverKeycloakUserId, UUID requestId, boolean accept) {
        UUID receiverUuid = parseKeycloakId(receiverKeycloakUserId);
        FriendRequest request = friendRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(404, "好友申请不存在"));

        // 仅接收方可处理，且必须是待处理状态
        if (!request.getReceiverId().equals(getSystemUserId(receiverUuid))) {
            throw new BusinessException(403, "无权处理该申请");
        }
        if (request.getStatus() != FriendRequest.RequestStatus.PENDING) {
            throw new BusinessException(409, "该申请已处理");
        }

        OffsetDateTime now = OffsetDateTime.now();
        request.setStatus(accept ? FriendRequest.RequestStatus.ACCEPTED : FriendRequest.RequestStatus.REJECTED);
        request.setHandledAt(now);
        friendRequestRepository.save(request);

        // 同意则建立好友关系（双向）
        if (accept) {
            createFriendRelation(request.getRequesterId(), request.getReceiverId(), now);
        }

        // 清除接收方通知的操作按钮（避免重新登录后仍显示操作按钮）
        UUID receiverSystemUserId = getSystemUserId(receiverUuid);
        notificationService.clearNotificationActions(receiverSystemUserId, "FRIEND_REQUEST", requestId);

        // 通知申请人结果
        notifyRequesterResult(request, accept);
    }

    private UUID parseKeycloakId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "无效的用户ID格式");
        }
    }

    private UUID getSystemUserId(UUID keycloakId) {
        return sysUserRepository.findByKeycloakUserIdAndNotDeleted(keycloakId)
                .filter(user -> user.getDeletedAt() == null && user.isEnabled())
                .map(SysUser::getId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在或已被禁用"));
    }

    private void createFriendRelation(UUID requesterId, UUID targetId, OffsetDateTime now) {
        UserFriend relation1 = UserFriend.builder()
                .userId(requesterId)
                .friendId(targetId)
                .status(UserFriend.FriendStatus.ACTIVE)
                .isFavorite(false)
                .lastInteractionTime(now)
                .build();

        UserFriend relation2 = UserFriend.builder()
                .userId(targetId)
                .friendId(requesterId)
                .status(UserFriend.FriendStatus.ACTIVE)
                .isFavorite(false)
                .lastInteractionTime(now)
                .build();

        userFriendRepository.save(relation1);
        userFriendRepository.save(relation2);
    }

    /**
     * 通知申请人处理结果（FRIEND_RESULT）
     */
    private void notifyRequesterResult(FriendRequest request, boolean accept) {
        String resultTitle = accept ? "好友申请通过" : "好友申请被拒绝";
        String resultContent = accept ? "对方已同意你的好友申请" : "对方拒绝了你的好友申请";

        // 申请人 keycloakId
        SysUser requester = sysUserRepository.findById(request.getRequesterId())
                .orElse(null);
        SysUser receiver = sysUserRepository.findById(request.getReceiverId())
                .orElse(null);
        if (requester == null || receiver == null) {
            return;
        }

        notificationService.notifyFriendResult(
                requester.getId(),
                requester.getKeycloakUserId().toString(),
                receiver.getKeycloakUserId().toString(),
                resultTitle,
                resultContent,
                request.getId(),
                accept
        );
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

    @Override
    public List<FriendInfo> getFriendsList(String currentUserKeycloakUserId) {
        // 1. 将 Keycloak 用户ID 转换为 UUID
        UUID currentUserKeycloakUuid;
        try {
            currentUserKeycloakUuid = UUID.fromString(currentUserKeycloakUserId);
        } catch (IllegalArgumentException e) {
            log.warn("无效的 Keycloak 用户ID格式: {}", currentUserKeycloakUserId);
            return List.of();
        }

        // 2. 通过 Keycloak 用户ID 查询系统用户ID
        UUID currentSystemUserId = getSystemUserId(currentUserKeycloakUuid);
        log.debug("查询好友列表: Keycloak用户ID={}, 系统用户ID={}", currentUserKeycloakUserId, currentSystemUserId);

        // 3. 查询当前用户的所有好友关系（状态为ACTIVE），使用系统用户ID
        List<UserFriend> friendRelations = userFriendRepository.findActiveFriendsByUserId(currentSystemUserId);
        log.debug("查询到的好友关系数量: {}", friendRelations.size());
        
        if (friendRelations.isEmpty()) {
            log.debug("当前用户没有好友关系");
            return List.of();
        }

        // 3. 提取所有好友的系统用户ID（UserFriend.friendId 是系统用户ID，UUID类型）
        List<UUID> friendSystemUserIds = friendRelations.stream()
                .map(UserFriend::getFriendId)
                .distinct()
                .collect(Collectors.toList());

        // 4. 批量查询好友的系统用户信息，获取 Keycloak 用户ID
        List<SysUser> friendSysUsers = sysUserRepository.findAllById(friendSystemUserIds);
        Map<UUID, SysUser> friendSysUserMap = friendSysUsers.stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user, (a, b) -> a));

        // 5. 提取所有好友的 Keycloak 用户ID（String格式）
        List<String> friendKeycloakUserIds = friendSysUsers.stream()
                .map(user -> user.getKeycloakUserId() != null ? user.getKeycloakUserId().toString() : null)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        if (friendKeycloakUserIds.isEmpty()) {
            log.warn("好友列表中没有任何有效的 Keycloak 用户ID");
            return List.of();
        }

        // 6. 批量查询好友的完整用户信息（UserInfo）
        List<UserInfo> friendUserInfos = userService.findUserInfosByKeycloakUserIds(friendKeycloakUserIds);
        log.debug("查询到的用户信息数量: {}, 期望数量: {}", friendUserInfos.size(), friendKeycloakUserIds.size());
        
        // 7. 构建 Keycloak 用户ID 到用户信息的映射
        Map<String, UserInfo> friendInfoMap = friendUserInfos.stream()
                .collect(Collectors.toMap(UserInfo::getUserId, info -> info, (a, b) -> a));

        // 8. 组装 FriendInfo 列表
        List<FriendInfo> result = friendRelations.stream()
                .map(uf -> {
                    // 从系统用户ID获取 Keycloak 用户ID
                    SysUser friendSysUser = friendSysUserMap.get(uf.getFriendId());
                    String friendKeycloakUserId = friendSysUser != null && friendSysUser.getKeycloakUserId() != null
                            ? friendSysUser.getKeycloakUserId().toString()
                            : null;
                    
                    // 获取完整的用户信息
                    UserInfo friendUserInfo = friendKeycloakUserId != null 
                            ? friendInfoMap.get(friendKeycloakUserId)
                            : null;
                    
                    return FriendInfo.builder()
                            .friendRelationId(uf.getId().toString())
                            .friendId(friendKeycloakUserId) // 使用 Keycloak 用户ID
                            .friendNickname(uf.getFriendNickname())
                            .friendGroup(uf.getFriendGroup())
                            .isFavorite(uf.getIsFavorite())
                            .lastInteractionTime(uf.getLastInteractionTime())
                            .friendInfo(friendUserInfo)
                            .online(null) // 在线状态需要从chat-service获取，暂时为null
                            .build();
                })
                .filter(fi -> fi.getFriendId() != null && fi.getFriendInfo() != null) // 过滤掉无效的数据
                .collect(Collectors.toList());
        
        log.debug("最终返回的好友列表数量: {}", result.size());
        return result;
    }

    @Override
    public boolean isFriend(String userId1, String userId2) {
        // 1. 参数校验
        if (userId1 == null || userId2 == null || userId1.equals(userId2)) {
            return false;
        }

        // 2. 将 Keycloak 用户ID 转换为 UUID
        UUID user1Uuid;
        UUID user2Uuid;
        try {
            user1Uuid = UUID.fromString(userId1);
            user2Uuid = UUID.fromString(userId2);
        } catch (IllegalArgumentException e) {
            log.warn("无效的 Keycloak 用户ID格式: userId1={}, userId2={}", userId1, userId2);
            return false;
        }

        // 3. 查询两个用户的系统用户ID
        UUID user1SystemId;
        UUID user2SystemId;
        try {
            user1SystemId = getSystemUserId(user1Uuid);
            user2SystemId = getSystemUserId(user2Uuid);
        } catch (BusinessException e) {
            log.debug("查询系统用户ID失败: userId1={}, userId2={}, err={}", userId1, userId2, e.getMessage());
            return false;
        }

        // 4. 检查是否是好友关系（状态为ACTIVE）
        return userFriendRepository.existsActiveFriendRelation(user1SystemId, user2SystemId);
    }
}





