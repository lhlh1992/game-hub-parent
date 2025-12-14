package com.gamehub.systemservice.entity.friend;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 好友申请表实体
 * 对应数据库表：friend_request
 */
@Entity
@Table(name = "friend_request")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 申请人用户ID
     */
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    /**
     * 接收人用户ID
     */
    @Column(name = "receiver_id", nullable = false)
    private UUID receiverId;

    /**
     * 申请留言
     */
    @Column(name = "request_message", length = 200)
    private String requestMessage;

    /**
     * 申请状态：PENDING（待处理）、ACCEPTED（已接受）、REJECTED（已拒绝）、EXPIRED（已过期）
     */
    @Column(name = "status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RequestStatus status = RequestStatus.PENDING;

    /**
     * 处理时间
     */
    @Column(name = "handled_at")
    private OffsetDateTime handledAt;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 申请状态枚举
     */
    public enum RequestStatus {
        PENDING,    // 待处理
        ACCEPTED,  // 已接受
        REJECTED,  // 已拒绝
        EXPIRED    // 已过期
    }
}


