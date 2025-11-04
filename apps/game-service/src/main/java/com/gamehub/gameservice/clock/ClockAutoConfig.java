package com.gamehub.gameservice.clock;

import com.gamehub.gameservice.clock.scheduler.CountdownScheduler;
import com.gamehub.gameservice.clock.scheduler.CountdownSchedulerImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * ClockAutoConfig
 * ---------------------------------------
 * 倒计时相关 Bean 的自动装配：将调度线程池与 Redis 注入到通用调度引擎中。
 *
 * 说明：
 *  - 线程池由 {@link com.gamehub.gameservice.clock.ClockSchedulerConfig} 提供。
 *  - 这里不关心任何业务细节，只负责把基础设施拼起来。
 */
@Configuration
public class ClockAutoConfig {

    /**
     * 注册通用倒计时调度器。
     * @param redisTemplate       Redis 客户端（用于状态/锁）
     * @param turnClockScheduler  调度线程池（守护线程，每秒触发）
     * @return CountdownScheduler 实例
     */
    @Bean
    public CountdownScheduler countdownScheduler(RedisTemplate<String, Object> redisTemplate,
                                                 @Qualifier("turnClockScheduler")ScheduledThreadPoolExecutor turnClockScheduler) {
        return new CountdownSchedulerImpl(redisTemplate, turnClockScheduler); // 纯引擎，无业务逻辑
    }
}


