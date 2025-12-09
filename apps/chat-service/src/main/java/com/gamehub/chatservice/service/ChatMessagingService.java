package com.gamehub.chatservice.service;

public interface ChatMessagingService {

    void sendLobbyMessage(String userId, String content);

    void sendRoomMessage(String userId, String roomId, String content);
}

