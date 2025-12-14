package com.gamehub.systemservice.repository.friend;

import com.gamehub.systemservice.entity.friend.UserFriend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 好友关系 Repository
 */
@Repository
public interface UserFriendRepository extends JpaRepository<UserFriend, UUID> {

    /**
     * 查询两个用户之间的好友关系（任意方向）
     */
    @Query("SELECT uf FROM UserFriend uf WHERE " +
           "(uf.userId = :userId1 AND uf.friendId = :userId2) OR " +
           "(uf.userId = :userId2 AND uf.friendId = :userId1)")
    Optional<UserFriend> findFriendRelation(@Param("userId1") UUID userId1,
                                            @Param("userId2") UUID userId2);

    /**
     * 检查两个用户是否已经是好友（状态为ACTIVE）
     */
    @Query("SELECT COUNT(uf) > 0 FROM UserFriend uf WHERE " +
           "((uf.userId = :userId1 AND uf.friendId = :userId2) OR " +
           "(uf.userId = :userId2 AND uf.friendId = :userId1)) " +
           "AND uf.status = 'ACTIVE'")
    boolean existsActiveFriendRelation(@Param("userId1") UUID userId1,
                                        @Param("userId2") UUID userId2);

    /**
     * 查询指定用户的所有好友（状态为ACTIVE）
     */
    @Query("SELECT uf FROM UserFriend uf WHERE uf.userId = :userId " +
           "AND uf.status = 'ACTIVE' ORDER BY uf.lastInteractionTime DESC NULLS LAST, uf.createdAt DESC")
    List<UserFriend> findActiveFriendsByUserId(@Param("userId") UUID userId);

    /**
     * 查询指定用户的好友关系（单向，用于查询特定好友）
     */
    @Query("SELECT uf FROM UserFriend uf WHERE uf.userId = :userId " +
           "AND uf.friendId = :friendId")
    Optional<UserFriend> findByUserIdAndFriendId(@Param("userId") UUID userId,
                                                  @Param("friendId") UUID friendId);
}


