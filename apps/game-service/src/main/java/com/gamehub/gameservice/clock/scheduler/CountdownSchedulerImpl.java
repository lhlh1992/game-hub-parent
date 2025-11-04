package com.gamehub.gameservice.clock.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.Serializable;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;

/**
 * CountdownSchedulerImpl
 * ---------------------------------------
 * 通用倒计时调度引擎的默认实现。
 *
 * 职责：
 *  - 使用 ScheduledThreadPoolExecutor 每秒调度任务。
 *  - 将倒计时状态（key/owner/version/deadline）持久化到 Redis，支持重启恢复。
 *  - 采用 Redis SETNX 实现的 holder 锁，确保分布式下只有一个节点做超时处理。
 *  - 暴露 tick/timeout 回调给上层业务协调器。
 *
 * 不做的事：
 *  - 不做任何业务逻辑（如广播、判负）。
 */
public class CountdownSchedulerImpl implements CountdownScheduler {

    private static final Logger log = LoggerFactory.getLogger(CountdownSchedulerImpl.class);

    // Redis 客户端，用于状态持久化与分布式锁
    private final RedisTemplate<String, Object> redis;
    // 调度器：每秒触发一次
    private final ScheduledThreadPoolExecutor scheduler;

    // 本节点标识，用于 holder 锁
    @Value("${instance.id:${spring.application.name}-${random.value}}")
    private String nodeId;

    // 每秒 TICK 的上层监听器（可为空）
    private volatile TickListener tickListener;

    // key -> 任务句柄
    private final ConcurrentMap<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    /**
     * 构造函数：注入 Redis 与调度线程池。
     * @param redis RedisTemplate 用于持久化状态与分布式锁
     * @param scheduler 每秒调度执行倒计时任务
     */
    public CountdownSchedulerImpl(RedisTemplate<String, Object> redis,
                                  ScheduledThreadPoolExecutor scheduler) {
        // 注入 Redis
        this.redis = redis;
        // 注入调度线程池
        this.scheduler = scheduler;
    }

    /**
     * 设置每秒 TICK 的回调监听器。
     * @param listener 上层监听器
     */
    @Override
    public void setTickListener(TickListener listener) {
        // 仅记录引用，回调用于每秒触发
        this.tickListener = listener;
    }

    /**
     * 启动或续上倒计时；若已到期则直接尝试触发超时，不再调度。
     * @param key 业务键
     * @param owner 被计时方
     * @param deadlineEpochMs 截止时间（毫秒）
     * @param version 回合版本
     * @param onTimeout 超时回调
     */
    @Override
    public void startOrResume(String key, String owner, long deadlineEpochMs, String version, TimeoutHandler onTimeout) {
        // 防止重复任务：先取消老任务
        stop(key);
        // 构造内存态
        CountdownState state = new CountdownState(key, owner, version, deadlineEpochMs);
        // 持久化到 Redis（便于恢复）
        saveState(state);
        // 计算剩余毫秒
        long remainMs = state.deadlineEpochMs - System.currentTimeMillis();
        // 已到期：直接尝试做超时
        if (remainMs <= 0) {
            // 抢占分布式 holder
            if (tryAcquireHolder(key)) {
                // 回调业务超时
                safeTimeout(onTimeout, state);
            }
            // 不再启动调度
            return;
        }
        // 立即首帧 TICK，提升前端体验
        fireTick(state);
        // 调度任务主体（首次延迟 1 秒，周期 1 秒）
        ScheduledFuture<?> fut = scheduler.scheduleAtFixedRate(
                () -> tickTask(state, onTimeout),
                1,
                1,
                TimeUnit.SECONDS);
        // 记录任务句柄
        activeTasks.put(key, fut);
    }

    /**
     * 停止指定 key 的调度任务（不打断正在执行）。
     * @param key 业务键
     */
    @Override
    public void stop(String key) {
        // 从表中移除
        ScheduledFuture<?> f = activeTasks.remove(key);
        // 取消调度，但不打断正在运行
        if (f != null) f.cancel(false);
        // 同步清理 Redis 中的状态与 holder 锁，避免重启时被误恢复
        try {
            redis.delete(stateKey(key));
            redis.delete(holderKey(key));
        } catch (Exception ignore) {}
    }

    /**
     * 恢复所有活跃倒计时：扫描 Redis 状态，过期则清理并尝试超时，未过期则重新调度。
     * @param onTimeout 超时回调
     * @return 成功恢复的数量
     */
    @Override
    public int restoreAllActive(TimeoutHandler onTimeout) {
        // 扫描所有持久化倒计时
        Set<String> keys = redis.keys(stateKey("*"));
        // 无可恢复任务
        if (keys == null || keys.isEmpty()) return 0;
        // 计数器
        int restored = 0;
        int expiredCleaned = 0;                                          // 被清理的已过期数量
        int expiredHandled = 0;                                          // 触发了超时处理的数量
        // 遍历每个持久化键
        for (String redisKey : keys) {
            // 加载状态
            CountdownState st = loadStateByRedisKey(redisKey);
            // 容错：被并发删除
            if (st == null) continue;
            // 剩余毫秒
            long remainMs = st.deadlineEpochMs - System.currentTimeMillis();
            // 已过期：清理并尝试超时
            if (remainMs <= 0) {
                // 清理陈旧状态
                redis.delete(redisKey);
                expiredCleaned++;
                // holder 才执行超时
                if (tryAcquireHolder(st.key)) { safeTimeout(onTimeout, st); expiredHandled++; }
                // 不再恢复调度
                continue;
            }
            // 恢复后立即推一帧 TICK
            fireTick(st);
            // 启动周期调度
            ScheduledFuture<?> fut = scheduler.scheduleAtFixedRate(
                    () -> tickTask(st, onTimeout), 1, 1, java.util.concurrent.TimeUnit.SECONDS);
            // 记录任务
            activeTasks.put(st.key, fut);
            restored++;
        }
        // 记录启动恢复的总体结果日志（与旧实现的统计信息等价）
        log.info("Countdown restoreAllActive done: restored={}, expiredCleaned={}, expiredHandled={}",
                restored, expiredCleaned, expiredHandled);
        // 返回恢复数量
        return restored;
    }

    /**
     * 周期任务：读取最新状态，判定到期则尝试 holder 并触发超时，否则发出一帧 TICK。
     */
    private void tickTask(CountdownState state, TimeoutHandler onTimeout) {
        // 以 Redis 为准，获取最新状态
        CountdownState latest = loadState(state.key);
        // 状态不存在 → 被停/删
        if (latest == null) {
            // 取消调度
            stop(state.key);
            // 结束本次执行
            return;
        }
        // 剩余毫秒
        long remainMs = latest.deadlineEpochMs - System.currentTimeMillis();
        // 到期判断
        if (remainMs <= 0) {
            // 抢占 holder（避免多节点重复）
            if (tryAcquireHolder(state.key)) {
                // 取消调度
                stop(state.key);
                // 执行超时回调
                safeTimeout(onTimeout, latest);
            }
            // 结束本次执行
            return;
        }
        // 正常 TICK 回调
        fireTick(latest);
    }

    /**
     * 触发一帧 TICK 回调（若监听器存在）。
     */
    private void fireTick(CountdownState state) {
        // 读取监听器快照
        TickListener l = tickListener;
        // 未设置则忽略
        if (l == null) return;
        // 计算整秒
        long left = Math.max(0, (state.deadlineEpochMs - System.currentTimeMillis()) / 1000);
        try { l.onTick(state.key, state.owner, state.deadlineEpochMs, left); } catch (Throwable ignore) {}
    }

    /**
     * 安全触发超时回调（吞掉回调中的异常）。
     */
    private void safeTimeout(TimeoutHandler onTimeout, CountdownState state) {
        // 容错：无处理器
        if (onTimeout == null) return;
        try { onTimeout.onTimeout(state.key, state.owner, state.version); } catch (Throwable ignore) {}
    }

    /**
     * 尝试获取分布式 holder 锁（SETNX，过期10s）。
     * @return true 表示本节点获得执行权
     */
    private boolean tryAcquireHolder(String key) {
        // holder 锁键
        String lockKey = holderKey(key);
        // SETNX + 10s 过期
        Boolean ok = redis.opsForValue().setIfAbsent(lockKey, nodeId, Duration.ofSeconds(10));
        // true=抢到
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 将状态持久化到 Redis（默认过期24小时）。
     */
    private void saveState(CountdownState st) {
        // 保存 24h 过期（可调）
        redis.opsForValue().set(stateKey(st.key), st, Duration.ofSeconds(24 * 60 * 60));
    }

    /**
     * 读取指定 key 的持久化状态。
     */
    private CountdownState loadState(String key) {
        // 读取当前 key 的状态
        return (CountdownState) redis.opsForValue().get(stateKey(key));
    }

    /**
     * 根据完整的 Redis 键读取状态（用于批量恢复遍历）。
     */
    private CountdownState loadStateByRedisKey(String redisKey) {
        // 用已知完整 redisKey 读取
        return (CountdownState) redis.opsForValue().get(redisKey);
    }

    // 状态键空间
    /**
     * 生成状态键名空间。
     */
    private String stateKey(String key) { return "countdown:" + key; }
    // holder 锁键空间
    /**
     * 生成 holder 锁键名空间。
     */
    private String holderKey(String key) { return "countdown:holder:" + key; }

    /**
     * 倒计时状态静态内部类
     */
    public static class CountdownState implements Serializable {
        // 业务键
        public String key;
        // 被计时的一方（字符串）
        public String owner;
        // 回合版本（上层用于幂等/保护）
        public String version;
        // 绝对截止时间（毫秒）
        public long deadlineEpochMs;
        public CountdownState(){};
        public CountdownState(String key, String owner, String version, long deadlineEpochMs) {
            this.key = key; this.owner = owner; this.version = version; this.deadlineEpochMs = deadlineEpochMs;
        }
    }
}


