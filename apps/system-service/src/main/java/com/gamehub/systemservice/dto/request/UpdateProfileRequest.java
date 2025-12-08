package com.gamehub.systemservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 更新用户资料请求 DTO
 */
@Data
public class UpdateProfileRequest {

    /**
     * 昵称
     */
    @Size(max = 50, message = "昵称长度不能超过50个字符")
    private String nickname;

    /**
     * 邮箱
     */
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过100个字符")
    private String email;

    /**
     * 手机号
     */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 头像URL（临时URL，需要移动到正式目录）
     */
    @Size(max = 500, message = "头像URL长度不能超过500个字符")
    private String avatarUrl;

    /**
     * 个人简介
     */
    @Size(max = 500, message = "个人简介长度不能超过500个字符")
    private String bio;

    /**
     * 语言偏好（如：zh-CN、en-US）
     */
    @Pattern(regexp = "^[a-z]{2}-[A-Z]{2}$", message = "语言偏好格式不正确（如：zh-CN）")
    private String locale;

    /**
     * 时区（如：Asia/Shanghai、UTC）
     */
    @Size(max = 50, message = "时区长度不能超过50个字符")
    private String timezone;

    /**
     * 用户设置（JSON字符串，会被解析为Map）
     */
    private String settings;
}

