package com.gamehub.systemservice.service.friend;

import com.gamehub.systemservice.dto.response.FriendInfo;

import java.util.List;
import java.util.UUID;

/**
 * 好友服务接口
 */
public interface FriendService {

    /**
     * 申请加好友
     * 
     * @param requesterKeycloakUserId 申请人Keycloak用户ID（String格式）
     * @param targetKeycloakUserId 目标用户Keycloak用户ID（String格式）
     * @param requestMessage 申请留言（可选）
     * @return 是否自动成为好友（true表示双向申请自动通过，false表示正常申请）
     * @throws com.gamehub.systemservice.exception.BusinessException 业务异常
     */
    boolean applyFriend(String requesterKeycloakUserId, String targetKeycloakUserId, String requestMessage);

    /**
     * 同意好友申请（接收方操作）
     *
     * @param receiverKeycloakUserId 接收方 Keycloak 用户ID（当前登录用户）
     * @param requestId              好友申请ID
     */
    void acceptFriendRequest(String receiverKeycloakUserId, UUID requestId);

    /**
     * 拒绝好友申请（接收方操作）
     *
     * @param receiverKeycloakUserId 接收方 Keycloak 用户ID（当前登录用户）
     * @param requestId              好友申请ID
     */
    void rejectFriendRequest(String receiverKeycloakUserId, UUID requestId);

    /**
     * 获取当前用户的好友列表
     *
     * @param currentUserKeycloakUserId 当前用户的Keycloak用户ID（String格式）
     * @return 好友列表
     */
    List<FriendInfo> getFriendsList(String currentUserKeycloakUserId);
}



