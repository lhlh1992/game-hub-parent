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
    @Query("SELECT n FROM Notification n WHERE n.userId = :userId ORDER BY n.createdAt DESC")
    List<Notification> findTopByUserId(@Param("userId") UUID userId, org.springframework.data.domain.Pageable pageable);
}

