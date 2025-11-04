package com.gamehub.gameservice.infrastructure.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 这个调度器专门用于控制 PVE 模式下 AI 的思考延迟，与玩家倒计时的调度器分开，以防止线程池任务相互影响游戏的实时性和流畅性。
 */
@Configuration
public class AiSchedulerConfig {

	@Bean("aiScheduler")
	public ScheduledExecutorService aiScheduler() {
		int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
		ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(poolSize, new ThreadFactory() {
			private final AtomicInteger idx = new AtomicInteger(1);
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "ai-delay-" + idx.getAndIncrement());
                // 设置为守护线程
				t.setDaemon(true);
				return t;
			}
		});
		exec.setRemoveOnCancelPolicy(true);
		return exec;
	}
}


