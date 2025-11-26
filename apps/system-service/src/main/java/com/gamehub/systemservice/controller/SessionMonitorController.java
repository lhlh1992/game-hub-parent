package com.gamehub.systemservice.controller;

import com.gamehub.session.SessionRegistry;
import com.gamehub.session.model.UserSessionSnapshot;
import com.gamehub.systemservice.dto.response.UserSessionSnapshotWithUserInfo;
import com.gamehub.systemservice.service.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 会话监控控制器 - 用于开发和调试
 * 
 * 提供实时查看所有在线用户会话的接口，包括：
 * - 登录会话（HTTP/JWT）
 * - WebSocket 连接会话
 * - 用户昵称等信息
 * 
 * 注意：此接口完全开放，仅用于开发环境排查问题
 * 
 * 业务边界：此功能属于系统管理功能，放在 system-service 更符合业务边界划分
 */
@Slf4j
@RestController
@RequestMapping("/internal/sessions")
@RequiredArgsConstructor
@org.springframework.web.bind.annotation.CrossOrigin(origins = "*")
public class SessionMonitorController {

    private final SessionRegistry sessionRegistry;
    private final UserService userService;

    /**
     * 获取所有在线用户的会话信息（包含用户昵称）
     * 
     * @return 所有用户的会话快照列表（带用户信息）
     */
    @GetMapping
    public ResponseEntity<List<UserSessionSnapshotWithUserInfo>> getAllSessions() {
        try {
            List<UserSessionSnapshot> sessions = sessionRegistry.getAllUserSessions();
            log.debug("查询所有用户会话，共 {} 个用户", sessions.size());
            
            // 批量查询用户信息并填充昵称
            List<UserSessionSnapshotWithUserInfo> result = sessions.stream()
                    .map(snapshot -> {
                        String userId = snapshot.getUserId();
                        
                        // 尝试根据 Keycloak userId 查询系统用户
                        String nickname = null;
                        String username = null;
                        try {
                            UUID keycloakUserId = UUID.fromString(userId);
                            var userOpt = userService.findByKeycloakUserId(keycloakUserId);
                            if (userOpt.isPresent()) {
                                var user = userOpt.get();
                                nickname = user.getNickname();
                                username = user.getUsername();
                            }
                        } catch (Exception e) {
                            log.debug("无法查询用户信息: userId={}, error={}", userId, e.getMessage());
                        }
                        
                        return UserSessionSnapshotWithUserInfo.builder()
                                .userId(userId)
                                .nickname(nickname)
                                .username(username)
                                .sessionSnapshot(snapshot)
                                .build();
                    })
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询用户会话失败", e);
            return ResponseEntity.status(500).build();
        }
    }
}

