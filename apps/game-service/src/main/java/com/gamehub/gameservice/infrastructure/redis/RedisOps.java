package com.gamehub.gameservice.infrastructure.redis;

import com.gamehub.gameservice.games.gomoku.domain.dto.GameStateRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 公用 Redis 工具类：
 * - 封装常用 String/Hash/Key/自增/脚本 操作
 * - 仅提供“原语级”方法；业务键名与字段名放在 Repo/Service 层组织
 * - 便于在全项目低耦合复用；将来切换到哨兵/集群/云Redis时无需改代码
 */
@Component
@RequiredArgsConstructor
public class RedisOps {
    /** 通用对象模板：用于 JSON 存储与反序列化 */
    private final RedisTemplate<String, Object> redis;
    /** 字符串模板：用于轻量 String 操作 */
    private final StringRedisTemplate strRedis;

    // -------------- String --------------
    /**
     * 写入键值（无 TTL）
     */
    public boolean set(String key, Object val) {
        redis.opsForValue().set(key, val);
        return true;
    }
    /**
     * 写入键值（带 TTL）
     */
    public boolean setEx(String key, Object val, Duration ttl) {
        redis.opsForValue().set(key, val, ttl);
        return true;
    }
    /**
     * 写入键值（仅当不存在时，SETNX）
     * @return true 表示写入成功，false 表示键已存在
     */
    public boolean setNx(String key, Object val, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, val, ttl);
        return Boolean.TRUE.equals(ok);
    }
    /**
     * 获取键值并自动反序列化为指定类型
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = redis.opsForValue().get(key);
        return (v == null) ? null : (T) v;
    }

    /**
     * 自增操作（整数累加）
     * @return 新值
     */
    public Long incrBy(String key, long delta) {
        return redis.opsForValue().increment(key, delta);
    }

    // -------------- Hash --------------
    /**
     * 写入 Hash 字段
     */
    public boolean hSet(String key, String field, Object val) {
        redis.opsForHash().put(key, field, val);
        return true;
    }

    /**
     * 批量写入 Hash
     */
    public boolean hSetAll(String key, Map<String, ?> map) {
        redis.opsForHash().putAll(key, map);
        return true;
    }
    /**
     * 获取单个 Hash 字段并反序列化
     */
    @SuppressWarnings("unchecked")
    public <T> T hGet(String key, String field, Class<T> type) {
        Object v = redis.opsForHash().get(key, field);
        return (v == null) ? null : (T) v;
    }
    /**
     * 获取整个 Hash（转为 Map<String,Object>）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> hGetAll(String key) {
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        Map<String, Object> out = new HashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
    /**
     * 删除指定 Hash 字段
     */
    public Long hDel(String key, String... fields) {
        return redis.opsForHash().delete(key, (Object[]) fields);
    }
    /**
     * Hash 字段自增（整数）
     */
    public Long hIncrBy(String key, String field, long delta) {
        return redis.opsForHash().increment(key, field, delta);
    }

    // -------------- Key & TTL --------------
    /**
     * 设置过期时间（TTL）
     */
    public Boolean expire(String key, Duration ttl) {
        return redis.expire(key, ttl);
    }
    /**
     * 获取剩余 TTL（秒）
     */
    public Long ttl(String key) {
        return redis.getExpire(key, TimeUnit.SECONDS);
    }
    /**
     * 判断 Key 是否存在
     */
    public Boolean exists(String key) {
        Boolean has = redis.hasKey(key);
        return Boolean.TRUE.equals(has);
    }
    /**
     * 删除一个或多个 Key
     */
    public Long del(String... keys) {
        return redis.delete(Arrays.asList(keys));
    }

    // -------------- Script --------------

    /**
     * 执行 Lua 脚本（原子操作）
     * -------------------------------------------------------
     * 常用于：
     *  - 分布式锁；
     *  - 抢座 / 棋位占用；
     *  - 乐观锁更新；
     *  - 限流与幂等。
     *
     * @param script Lua 文本内容
     * @param keys   KEYS[...] 参数列表
     * @param args   ARGV[...] 参数列表
     * @param resultType  返回类型
     * @param <T>     泛型返回值
     * @return Lua 脚本执行结果
     */
    public <T> T eval(String script, List<String> keys, List<Object> args, Class<T> resultType) {
        DefaultRedisScript<T> rs = new DefaultRedisScript<>();
        rs.setResultType(resultType);
        rs.setScriptText(script);
        return redis.execute(rs, keys, args.toArray());
    }

    // -------------- String 强类型便捷（可选） --------------
    /**
     * 写入简单字符串键值（带 TTL）
     */
    public boolean setString(String key, String val, Duration ttl) {
        strRedis.opsForValue().set(key, val, ttl);
        return true;
    }
    /**
     * 仅当不存在时写入字符串键值（SETNX），带 TTL。
     * @return true 表示写入成功，false 表示已存在
     */
    public boolean setStringNx(String key, String val, Duration ttl) {
        Boolean ok = strRedis.opsForValue().setIfAbsent(key, val, ttl);
        return Boolean.TRUE.equals(ok);
    }
    /**
     * 获取简单字符串值
     */
    public String getString(String key) {
        return strRedis.opsForValue().get(key);
    }


}
