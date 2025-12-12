package com.gamehub.systemservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 申请加好友请求 DTO
 */
@Data
public class ApplyFriendRequest {

    /**
     * 目标用户ID（Keycloak 用户ID，String格式）
     */
    @NotBlank(message = "目标用户ID不能为空")
    private String targetUserId;

    /**
     * 申请留言（可选，最大200字符）
     */
    @Size(max = 200, message = "申请留言不能超过200字符")
    private String requestMessage;
}

