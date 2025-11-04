package com.gamehub.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {


    @GetMapping("/api/test")
    public String test() {
        return "登录成功！这是 Gateway 返回的测试接口";
    }
}
