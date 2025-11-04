package com.gamehub.gameservice.clock.scheduler;

/**
 * CountdownScheduler
 * ---------------------------------------
 * 通用的“倒计时调度器”接口，完全独立于具体业务（如五子棋、订单等）。
 *
 * 设计目标：
 *  - 提供统一的倒计时能力（启动/恢复/停止/全量恢复）。
 *  - 暴露“每秒 tick 回调”和“到期 timeout 回调”。
 *  - 不关心消息广播、游戏规则等业务细节，由上层协调器负责。
 */
public interface CountdownScheduler {

    /**
     * TickListener
     * ---------------------------------------
     * 每秒触发一次，用于向上层报告：当前 key 的 owner、绝对截止时间、剩余秒数。
     */
    interface TickListener {
        /**
         * 每秒调用一次的回调。
         * @param key              业务键（如 "gomoku:{roomId}"）
         * @param owner            当前被计时的一方（字符串表现，如 "X"/"O"）
         * @param deadlineEpochMs  本回合的绝对截止时间（毫秒）
         * @param remainingSeconds 剩余秒数（服务端计算）
         */
        void onTick(String key, String owner, long deadlineEpochMs, long remainingSeconds);
    }

    /**
     * TimeoutHandler
     * ---------------------------------------
     * 到期时（服务端计算剩余时间≤0）回调一次，由上层做权威业务处理。
     */
    interface TimeoutHandler {
        /**
         * 倒计时到期时触发。
         * @param key     业务键
         * @param owner   当前被计时的一方
         * @param version 回合版本（用于上层幂等/保护，格式由上层定义）
         */
        void onTimeout(String key, String owner, String version);
    }

    /**
     * 设置每秒 TICK 的监听器。
     * @param listener 回调实现
     */
    void setTickListener(TickListener listener);

    /**
     * 启动或恢复指定 key 的倒计时。
     * 注意：实现可根据 key 将状态持久化（如 Redis），用于重启恢复。
     * @param key             业务键
     * @param owner           当前被计时的一方
     * @param deadlineEpochMs 本回合的绝对截止时间（毫秒）
     * @param version         回合版本（上层自定义，便于幂等/跨局保护）
     * @param onTimeout       到期回调
     */
    void startOrResume(String key, String owner, long deadlineEpochMs, String version, TimeoutHandler onTimeout);

    /**
     * 停止指定 key 的倒计时（仅停止调度，不强制清理持久化状态，具体策略由实现决定）。
     * @param key 业务键
     */
    void stop(String key);

    /**
     * 从持久化介质恢复所有“仍未到期”的倒计时。
     * @param onTimeout 到期回调（用于恢复后直接可能触发的到期处理）
     * @return 恢复的任务数
     */
    int restoreAllActive(TimeoutHandler onTimeout);
}


