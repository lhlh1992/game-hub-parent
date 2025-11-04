package com.gamehub.gameservice.clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时线程池配置类，用于统一创建全局的 ScheduledThreadPoolExecutor。
 *
 * 功能说明：
 * 1. 从配置文件读取核心线程数（scheduler.clock.corePoolSize）；
 * 2. 自定义线程工厂，线程命名为 countdown-N，便于调试；
 * 3. 设置为守护线程，JVM 退出时自动结束；
 * 4. 使用 DiscardPolicy 拒绝策略，任务满时直接丢弃；
 * 5. 启用 setRemoveOnCancelPolicy(true)，清理已取消任务。
 *
 * 主要用于回合倒计时等定时任务。
 */

@Configuration
public class ClockSchedulerConfig {

    @Value("${scheduler.clock.corePoolSize:2}")
    private int corePoolSize;

    @Bean(name = "turnClockScheduler")
    public ScheduledThreadPoolExecutor turnClockScheduler() {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "countdown-" + seq.getAndIncrement());
                // 非业务线程，允许JVM优雅退出时不用等它
                t.setDaemon(true);
                return t;
            }
        };
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(corePoolSize, tf, new ThreadPoolExecutor.DiscardPolicy());
        //定时任务cancel后，调度队列里干净地移除
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}


