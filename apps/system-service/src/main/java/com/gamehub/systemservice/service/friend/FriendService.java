package com.gamehub.systemservice.service.friend;

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
}

