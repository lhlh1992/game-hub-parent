package com.gamehub.systemservice.service.file;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口
 */
public interface FileStorageService {

    /**
     * 上传头像
     * @param file 图片文件
     * @return 文件访问 URL
     */
    String uploadAvatar(MultipartFile file) throws Exception;

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

