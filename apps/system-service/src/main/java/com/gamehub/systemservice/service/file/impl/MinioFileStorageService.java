package com.gamehub.systemservice.service.file.impl;

import com.gamehub.systemservice.service.file.FileStorageService;
import io.minio.*;
import io.minio.errors.MinioException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

/**
 * MinIO 文件存储服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioFileStorageService implements FileStorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket.avatars}")
    private String avatarsBucket;

    @Value("${minio.bucket.game-replays}")
    private String gameReplaysBucket;

    @Value("${minio.bucket.materials}")
    private String materialsBucket;

    @Value("${minio.bucket.temp}")
    private String tempBucket;

    @Value("${minio.public-url-prefix}")
    private String publicUrlPrefix;

    @Override
    public String uploadAvatarToTemp(MultipartFile file, String userId) throws Exception {
        // 验证文件
        validateImageFile(file);

        // 生成文件名（userId + 扩展名）
        String extension = getFileExtension(file.getOriginalFilename());
        String objectName = userId + extension;

        // 上传到临时目录
        uploadFile(tempBucket, objectName, file.getInputStream(), file.getContentType(), file.getSize());

        // 返回临时文件访问 URL
        return publicUrlPrefix + "/temp/" + objectName;
    }

    @Override
    @Deprecated
    public String uploadAvatar(MultipartFile file) throws Exception {
        // 旧方法，使用UUID作为文件名，上传到正式目录（兼容旧接口）
        validateImageFile(file);
        String extension = getFileExtension(file.getOriginalFilename());
        String objectName = UUID.randomUUID() + extension;
        uploadFile(avatarsBucket, objectName, file.getInputStream(), file.getContentType(), file.getSize());
        return publicUrlPrefix + "/avatars/" + objectName;
    }

    @Override
    public String normalizeAvatarInAvatars(String avatarUrl, String userId) throws Exception {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return avatarUrl;
        }
        // 仅处理 avatars 路径
        if (!avatarUrl.contains("/avatars/")) {
            return avatarUrl;
        }
        String objectName = extractObjectNameFromUrl(avatarUrl, "avatars");
        String extension = getFileExtension(objectName);
        String finalObjectName = userId + extension;

        // 如果已经是 userId 命名则直接返回
        if (objectName.equals(finalObjectName)) {
            return avatarUrl;
        }

        try {
            // 复制并覆盖为 userId.xxx
            minioClient.copyObject(
                    CopyObjectArgs.builder()
                            .bucket(avatarsBucket)
                            .object(finalObjectName)
                            .source(
                                    CopySource.builder()
                                            .bucket(avatarsBucket)
                                            .object(objectName)
                                            .build()
                            )
                            .build()
            );
            log.info("头像规范化: {} -> {}", objectName, finalObjectName);

            // 删除旧文件（不影响主流程）
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(avatarsBucket)
                                .object(objectName)
                                .build()
                );
            } catch (Exception e) {
                log.warn("删除旧头像失败（忽略）: {}", objectName, e);
            }

            return publicUrlPrefix + "/avatars/" + finalObjectName;
        } catch (MinioException e) {
            log.error("头像规范化失败: avatarUrl={}", avatarUrl, e);
            throw new Exception("头像规范化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String moveAvatarFromTemp(String tempUrl, String userId) throws Exception {
        // 从URL中提取文件名
        // URL格式：http://files.localhost/files/temp/{userId}.jpg
        String tempObjectName = extractObjectNameFromUrl(tempUrl, "temp");
        String extension = getFileExtension(tempObjectName);
        String finalObjectName = userId + extension;

        try {
            // 检查临时文件是否存在
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(tempBucket)
                            .object(tempObjectName)
                            .build()
            );

            // 复制文件从 temp 到 avatars（覆盖同名文件）
            minioClient.copyObject(
                    CopyObjectArgs.builder()
                            .bucket(avatarsBucket)
                            .object(finalObjectName)
                            .source(
                                    CopySource.builder()
                                            .bucket(tempBucket)
                                            .object(tempObjectName)
                                            .build()
                            )
                            .build()
            );

            log.info("头像从临时目录移动到正式目录: temp={}, final={}", tempObjectName, finalObjectName);

            // 删除临时文件
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(tempBucket)
                                .object(tempObjectName)
                                .build()
                );
                log.info("临时文件已删除: {}", tempObjectName);
            } catch (Exception e) {
                log.warn("删除临时文件失败（不影响主流程）: {}", tempObjectName, e);
            }

            // 返回正式文件访问 URL
            return publicUrlPrefix + "/avatars/" + finalObjectName;
        } catch (MinioException e) {
            log.error("移动头像文件失败: tempUrl={}", tempUrl, e);
            throw new Exception("移动头像文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从URL中提取对象名称
     */
    private String extractObjectNameFromUrl(String url, String bucketPrefix) {
        // URL格式：http://files.localhost/files/{bucketPrefix}/{objectName}
        String prefix = publicUrlPrefix + "/" + bucketPrefix + "/";
        if (url.startsWith(prefix)) {
            return url.substring(prefix.length());
        }
        // 如果URL格式不对，尝试直接使用文件名部分
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            return url.substring(lastSlash + 1);
        }
        throw new IllegalArgumentException("无法从URL中提取对象名称: " + url);
    }

    @Override
    public String uploadGameReplay(MultipartFile file, String gameType, String roomId) throws Exception {
        // 生成文件路径
        String objectName = gameType + "/" + roomId + "/replay.json";

        // 上传到 MinIO
        uploadFile(gameReplaysBucket, objectName, file.getInputStream(), file.getContentType(), file.getSize());

        // 返回公共访问 URL
        return publicUrlPrefix + "/game-replays/" + objectName;
    }

    @Override
    public String uploadMaterial(MultipartFile file, String type, String id) throws Exception {
        // 获取原始文件名
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // 生成文件路径
        String objectName = type + "/" + id + "/" + originalFilename;

        // 上传到 MinIO
        uploadFile(materialsBucket, objectName, file.getInputStream(), file.getContentType(), file.getSize());

        // 返回公共访问 URL
        return publicUrlPrefix + "/materials/" + objectName;
    }

    /**
     * 上传文件到 MinIO
     */
    private void uploadFile(String bucket, String objectName, InputStream inputStream, 
                           String contentType, long size) throws Exception {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(inputStream, size, -1)
                            .contentType(contentType)
                            .build()
            );
            log.info("文件上传成功: bucket={}, object={}", bucket, objectName);
        } catch (MinioException e) {
            log.error("MinIO 上传失败: bucket={}, object={}", bucket, objectName, e);
            throw new Exception("文件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验证图片文件
     */
    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        // 验证文件类型（只允许常见图片格式）
        String contentType = file.getContentType();
        String[] allowedTypes = {
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/gif",
            "image/webp"
        };
        
        boolean isValidType = false;
        if (contentType != null) {
            for (String allowedType : allowedTypes) {
                if (contentType.equalsIgnoreCase(allowedType)) {
                    isValidType = true;
                    break;
                }
            }
        }
        
        // 也检查文件扩展名（双重验证，更安全）
        String filename = file.getOriginalFilename();
        if (filename != null) {
            String extension = getFileExtension(filename).toLowerCase();
            String[] allowedExtensions = {".jpg", ".jpeg", ".png", ".gif", ".webp"};
            for (String allowedExt : allowedExtensions) {
                if (extension.equals(allowedExt)) {
                    isValidType = true;
                    break;
                }
            }
        }
        
        if (!isValidType) {
            throw new IllegalArgumentException("只支持图片格式：JPG、JPEG、PNG、GIF、WEBP");
        }

        // 限制文件大小（2MB）
        long maxSize = 2 * 1024 * 1024; // 2MB
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("文件大小不能超过 2MB");
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return ".jpg"; // 默认扩展名
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot);
        }
        return ".jpg"; // 默认扩展名
    }
}

