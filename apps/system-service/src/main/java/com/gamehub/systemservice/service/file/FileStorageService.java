package com.gamehub.systemservice.service.file;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口
 */
public interface FileStorageService {

    /**
     * 上传头像到临时目录
     * @param file 图片文件
     * @param userId 用户ID（用于生成文件名）
     * @return 文件访问 URL
     */
    String uploadAvatarToTemp(MultipartFile file, String userId) throws Exception;

    /**
     * 上传头像（旧方法，保留兼容）
     * @deprecated 使用 uploadAvatarToTemp 替代
     */
    @Deprecated
    String uploadAvatar(MultipartFile file) throws Exception;

    /**
     * 规范化头像：若已在 avatars 但文件名不是 userId，则重命名为 userId.xxx
     * @param avatarUrl 现有头像URL（可能是 /avatars/uuid.jpg）
     * @param userId 用户ID
     * @return 最终规范化后的 URL（/avatars/{userId}.ext）
     */
    String normalizeAvatarInAvatars(String avatarUrl, String userId) throws Exception;

    /**
     * 将临时头像移动到正式目录
     * @param tempUrl 临时文件URL
     * @param userId 用户ID
     * @return 正式文件访问 URL
     */
    String moveAvatarFromTemp(String tempUrl, String userId) throws Exception;

    /**
     * 上传游戏回放
     * @param file 回放文件
     * @param gameType 游戏类型（如：gomoku）
     * @param roomId 房间ID
     * @return 文件访问 URL
     */
    String uploadGameReplay(MultipartFile file, String gameType, String roomId) throws Exception;

    /**
     * 上传活动素材
     * @param file 素材文件
     * @param type 素材类型（如：banners、notices、games）
     * @param id 素材ID
     * @return 文件访问 URL
     */
    String uploadMaterial(MultipartFile file, String type, String id) throws Exception;
}

