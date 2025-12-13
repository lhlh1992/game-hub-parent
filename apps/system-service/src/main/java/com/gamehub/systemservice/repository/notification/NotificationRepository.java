package com.gamehub.systemservice.repository.notification;

import com.gamehub.systemservice.entity.notification.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 用户通知 Repository。
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * 查询用户未读数量。
     */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.status = 'UNREAD'")
    long countUnread(@Param("userId") UUID userId);

    /**
     * 查询用户最近通知（按创建时间倒序）。
     */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.userId = :userId
              AND (:status IS NULL OR n.status = :status)
            ORDER BY 
              CASE WHEN n.status = 'UNREAD' THEN 0 ELSE 1 END,
              n.createdAt DESC
            """)
    List<Notification> findByUserIdAndStatus(@Param("userId") UUID userId,
                                             @Param("status") String status,
                                             org.springframework.data.domain.Pageable pageable);

    /**
     * 根据用户ID、关联类型和关联ID查找通知（用于处理好友申请后清除操作按钮）。
     */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.userId = :userId
              AND n.refType = :refType
              AND n.refId = :refId
            """)
    List<Notification> findByUserIdAndRefTypeAndRefId(@Param("userId") UUID userId,
                                                      @Param("refType") String refType,
                                                      @Param("refId") UUID refId);
}


