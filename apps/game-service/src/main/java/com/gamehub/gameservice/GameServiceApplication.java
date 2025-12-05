package com.gamehub.gameservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * game-service 启动入口。
 * 通过 @EnableFeignClients 统一启用基础设施层的 Feign Client。
 */
@SpringBootApplication
@EnableFeignClients
public class GameServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GameServiceApplication.class, args);
    }
}

