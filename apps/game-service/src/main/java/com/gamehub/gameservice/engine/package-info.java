package com.gamehub.gameservice.engine;
//协议适配层
//放对外协议的通用壳，比如 {type, roomId, game, payload}；REST/WS 共用的 DTO、序列化工具等。
//让控制器只关心命令/事件对象，和具体游戏解耦。