package com.gamehub.session.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户会话快照：聚合该用户的“登录会话 + WebSocket 会话”。
 *
 * 用于后台展示与“强制下线”时一次性清理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSessionSnapshot {

    /** 用户 ID */
    private String userId;

    /** 登录会话列表（HTTP / JWT） */
    @Builder.Default
    private List<LoginSessionInfo> loginSessions = new ArrayList<>();

    /** WebSocket 会话列表（长连接） */
    @Builder.Default
    private List<WebSocketSessionInfo> webSocketSessions = new ArrayList<>();

    /** 所有会话数量（登录 + WebSocket） */
    public int getTotalSessionCount() {
        return loginSessions.size() + webSocketSessions.size();
    }
}
