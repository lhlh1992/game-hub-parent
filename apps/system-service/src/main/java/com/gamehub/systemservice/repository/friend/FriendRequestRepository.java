package com.gamehub.systemservice.repository.friend;

import com.gamehub.systemservice.entity.friend.FriendRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 好友申请 Repository
 */
@Repository
public interface FriendRequestRepository extends JpaRepository<FriendRequest, UUID> {

    /**
     * 查询指定用户对目标用户的待处理申请
     */
    @Query("SELECT fr FROM FriendRequest fr WHERE fr.requesterId = :requesterId " +
           "AND fr.receiverId = :receiverId AND fr.status = 'PENDING'")
    Optional<FriendRequest> findPendingRequest(@Param("requesterId") UUID requesterId,
                                               @Param("receiverId") UUID receiverId);

    /**
     * 查询指定用户收到的所有待处理申请
     */
    @Query("SELECT fr FROM FriendRequest fr WHERE fr.receiverId = :receiverId " +
           "AND fr.status = 'PENDING' ORDER BY fr.createdAt DESC")
    List<FriendRequest> findPendingRequestsByReceiver(@Param("receiverId") UUID receiverId);

    /**
     * 查询指定用户发送的所有待处理申请
     */
    @Query("SELECT fr FROM FriendRequest fr WHERE fr.requesterId = :requesterId " +
           "AND fr.status = 'PENDING' ORDER BY fr.createdAt DESC")
    List<FriendRequest> findPendingRequestsByRequester(@Param("requesterId") UUID requesterId);

    /**
     * 检查是否存在反向的待处理申请（用于双向申请自动通过）
     */
    @Query("SELECT fr FROM FriendRequest fr WHERE fr.requesterId = :receiverId " +
           "AND fr.receiverId = :requesterId AND fr.status = 'PENDING'")
    Optional<FriendRequest> findReversePendingRequest(@Param("requesterId") UUID requesterId,
                                                      @Param("receiverId") UUID receiverId);
}

