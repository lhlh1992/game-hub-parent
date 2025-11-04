package com.gamehub.gameservice.games.gomoku.domain.model;
/**
 * 一步棋：在 (x,y) 落下 piece（'X' 或 'O'）
 * 作用：描述一次落子动作，包含坐标与棋子颜色。
 * 用 Java 21 的 record 简洁表达不可变数据。
 * */
public record Move(int x, int y, char piece) {
}
