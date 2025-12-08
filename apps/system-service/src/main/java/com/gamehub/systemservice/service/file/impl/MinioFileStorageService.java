package com.gamehub.systemservice.service.file.impl;

import com.gamehub.systemservice.service.file.FileStorageService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
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

    @Value("${minio.public-url-prefix}")
    private String publicUrlPrefix;

    @Override
    public String uploadAvatar(MultipartFile file) throws Exception {
        // 验证文件
        validateImageFile(file);

        // 生成文件名（UUID + 扩展名）
        String extension = getFileExtension(file.getOriginalFilename());
        String objectName = UUID.randomUUID() + extension;

        // 上传到 MinIO
        uploadFile(avatarsBucket, objectName, file.getInputStream(), file.getContentType(), file.getSize());

        // 返回公共访问 URL
        return publicUrlPrefix + "/avatars/" + objectName;
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

