package com.gamehub.systemservice.controller.file;

import com.gamehub.systemservice.common.Result;
import com.gamehub.systemservice.service.file.FileStorageService;
import com.gamehub.web.common.CurrentUserHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /**
     * 上传头像到临时目录（用于完善用户信息）
     * 需要认证，使用当前登录用户的ID作为文件名
     */
    @PostMapping("/upload/avatar/temp")
    public Result<String> uploadAvatarToTemp(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            String userId = CurrentUserHelper.getUserId(jwt);
            String url = fileStorageService.uploadAvatarToTemp(file, userId);
            return Result.success("头像上传成功", url);
        } catch (IllegalArgumentException e) {
            log.warn("头像上传参数错误: {}", e.getMessage());
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("头像上传失败", e);
            return Result.error(500, "头像上传失败: " + e.getMessage());
        }
    }

    /**
     * 上传头像（旧接口，保留兼容）
     * 暂时放开权限，方便测试
     */
    @PostMapping("/upload/avatar")
    public Result<String> uploadAvatar(@RequestParam("file") MultipartFile file) {
        try {
            String url = fileStorageService.uploadAvatar(file);
            return Result.success("头像上传成功", url);
        } catch (IllegalArgumentException e) {
            log.warn("头像上传参数错误: {}", e.getMessage());
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("头像上传失败", e);
            return Result.error(500, "头像上传失败: " + e.getMessage());
        }
    }

    /**
     * 上传游戏回放
     * 暂时放开权限，方便测试
     */
    @PostMapping("/upload/replay")
    public Result<String> uploadReplay(
            @RequestParam("file") MultipartFile file,
            @RequestParam("gameType") String gameType,
            @RequestParam("roomId") String roomId) {
        try {
            String url = fileStorageService.uploadGameReplay(file, gameType, roomId);
            return Result.success("回放上传成功", url);
        } catch (Exception e) {
            log.error("回放上传失败", e);
            return Result.error(500, "回放上传失败: " + e.getMessage());
        }
    }

    /**
     * 上传活动素材
     * 暂时放开权限，方便测试
     */
    @PostMapping("/upload/material")
    public Result<String> uploadMaterial(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam("id") String id) {
        try {
            String url = fileStorageService.uploadMaterial(file, type, id);
            return Result.success("素材上传成功", url);
        } catch (Exception e) {
            log.error("素材上传失败", e);
            return Result.error(500, "素材上传失败: " + e.getMessage());
        }
    }
}

