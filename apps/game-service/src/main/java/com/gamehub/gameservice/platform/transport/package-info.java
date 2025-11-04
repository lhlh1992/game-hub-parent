package com.gamehub.gameservice.platform.transport;
/**
 * 通信协议适配层,消息结构和规范
 * 它是整个游戏引擎中，“网络传输”与“游戏逻辑”之间的桥梁层。
 * 它定义的是 —— “服务器和客户端之间交换的消息结构”，
 * 但它 不关心具体的业务逻辑（落子、胜负、匹配都不管）。
 */
/**
 * [前端 / 客户端]  <---- WebSocket / HTTP JSON ---->  [transport 层]
 *         ↓
 *         [service 应用层]
 *         ↓
 *         [rule / ai / model]
 */

/**
 * 前端发来的请求（JSON 数据包）先经过 transport 层解析成统一的“命令格式”；
 * 服务端内部的事件广播、AI 建议、对局结果，都通过 transport 层包装成标准消息再发回客户端。
 */

