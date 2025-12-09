package com.gamehub.chatservice.controller.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST 接口占位：历史消息/会话/未读等。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatRestController {

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}

