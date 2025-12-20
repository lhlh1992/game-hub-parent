package com.gamehub.gameservice.games.gomoku.infrastructure.redis.repo;

import com.gamehub.gameservice.application.user.UserProfileView;
import com.gamehub.gameservice.games.gomoku.domain.model.SeriesView;
import com.gamehub.gameservice.games.gomoku.domain.repository.RoomRepository;
import com.gamehub.gameservice.games.gomoku.infrastructure.redis.RedisKeys;
import com.gamehub.gameservice.games.gomoku.domain.dto.RoomMeta;
import com.gamehub.gameservice.games.gomoku.domain.dto.SeatsBinding;
import com.gamehub.gameservice.infrastructure.redis.RedisOps;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
/**
 * RedisRoomRepository
 * -------------------------------------------------------
 * 房间与座位绑定的 Redis 仓储实现。
 * - 仅做数据映射与 TTL 管理，不承载业务规则；
 * - 键名通过 RedisKeys 统一生成，避免字符串散落；
 * - 使用 RedisOps 封装的原语，便于切换底层实现。
 */
@Repository
@RequiredArgsConstructor
public class RedisRoomRepository implements RoomRepository {
    /** 通用 Redis 操作封装（String/Hash/Script 等） */
    private final RedisOps ops;

    // 新增：直接用 Spring Data Redis 的 HashOperations，避免封装名差异导致的红线
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 保存房间元信息（JSON 存储，带 TTL）
     */
    @Override
    public void saveRoomMeta(String roomId, RoomMeta meta, Duration ttl) {
        String key = RedisKeys.room(roomId);
        ops.setEx(key, meta, ttl);
    }

    /**
     * 获取房间元信息
     */
    @Override
    public Optional<RoomMeta> getRoomMeta(String roomId) {
        String key = RedisKeys.room(roomId);
        RoomMeta meta = ops.get(key, RoomMeta.class);
        return Optional.ofNullable(meta);
    }
    /**
     * 删除房间元信息
     */
    @Override
    public void deleteRoom(String roomId) {
        ops.del(RedisKeys.room(roomId));
    }

    /**
     * 保存座位绑定（JSON 存储，带 TTL）
     */
    @Override
    public void saveSeats(String roomId, SeatsBinding seats, Duration ttl) {
        String key = RedisKeys.roomSeats(roomId);
        ops.setEx(key, seats, ttl);
    }

    /**
     * 获取座位绑定
     */
    @Override
    public Optional<SeatsBinding> getSeats(String roomId) {
        String key = RedisKeys.roomSeats(roomId);
        SeatsBinding s = ops.get(key, SeatsBinding.class);
        return Optional.ofNullable(s);
    }

    /**
     * 删除座位绑定
     */
    @Override
    public void deleteSeats(String roomId) {
        ops.del(RedisKeys.roomSeats(roomId));
    }

    /**
     * 删除房间的所有 seatKey
     */
    @Override
    public void deleteSeatKeys(String roomId) {
        Set<String> keys = redisTemplate.keys(RedisKeys.roomSeatKeyPrefix(roomId) + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /**
     * 设置一次性 seatKey（SETNX 语义），并设置 TTL。
     * 返回 true 表示本次占用成功；false 表示 seatKey 已存在。
     */
    @Override
    public boolean setSeatKey(String roomId, String seatKey, String seatChar, Duration ttl) {
        String key = RedisKeys.roomSeatKey(roomId, seatKey);
        return ops.setNx(key, seatChar, ttl); // 占坑（一次性），避免重复绑定
    }


    /**
     * 读取 seatKey 对应的座位标识（"X"/"O"/null）
     */
    @Override
    public Character getSeatKey(String roomId, String seatKey) {
        String raw = ops.getString(RedisKeys.roomSeatKey(roomId, seatKey));
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        // 去掉一次成对包裹的引号（支持单/双引号），然后再 trim 一次
        if (s.length() >= 2) {
            char first = s.charAt(0), last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        // 严格只接受单字符
        if (s.length() != 1) return null;
        char c = Character.toUpperCase(s.charAt(0)); // 只会是 ASCII，安全
        return (c == 'X' || c == 'O') ? c : null;
    }

    /**
     * 删除 seatKey
     */
    @Override
    public void deleteSeatKey(String roomId, String seatKey) {
        ops.del(RedisKeys.roomSeatKey(roomId, seatKey));
    }

    /**
     * 尝试占用座位锁（原子）：本人占用则续期，其他人占用则返回 false。
     */
    @Override
    public boolean tryLockSeat(String roomId, char seat, String userId, Duration ttl) {
        // 座位锁 key：按房间+座位隔离
        String key = RedisKeys.roomSeatLock(roomId, seat);
        // 已是本人占用：续期并视为成功
        String existing = ops.getString(key);
        if (existing != null && existing.equals(userId)) {
            ops.expire(key, ttl);
            return true;
        }
        // 尝试占用（SETNX 语义）
        return ops.setStringNx(key, userId, ttl);
    }

    /**
     * 释放座位锁：仅在锁为空或持有者为当前用户时删除，避免误删他人锁。
     */
    @Override
    public void releaseSeatLock(String roomId, char seat, String userId) {
        // 仅在锁空或持有者为当前用户时释放，避免误删他人锁
        String key = RedisKeys.roomSeatLock(roomId, seat);
        String existing = ops.getString(key);
        if (existing == null || existing.equals(userId)) {
            ops.del(key);
        }
    }

    /**
     * 删除房间的座位锁（销毁房间时清理）。
     */
    @Override
    public void deleteSeatLocks(String roomId) {
        // 销毁房间时清理座位锁
        ops.del(
                RedisKeys.roomSeatLock(roomId, 'X'),
                RedisKeys.roomSeatLock(roomId, 'O')
        );
    }

    /**
     * 保存用户档案到房间缓存（Hash 结构）
     */
    @Override
    public void saveUserProfile(String roomId, String userId, UserProfileView profile, Duration ttl) {
        if (roomId == null || roomId.isBlank() || userId == null || userId.isBlank() || profile == null) {
            return;
        }
        String key = RedisKeys.roomUserProfiles(roomId);
        redisTemplate.opsForHash().put(key, userId, profile);
        if (ttl != null) {
            redisTemplate.expire(key, ttl);
        }
    }

    /**
     * 从房间缓存获取用户档案
     */
    @Override
    public Optional<UserProfileView> getUserProfile(String roomId, String userId) {
        if (roomId == null || roomId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        String key = RedisKeys.roomUserProfiles(roomId);
        Object val = redisTemplate.opsForHash().get(key, userId);
        if (val == null) {
            return Optional.empty();
        }
        if (val instanceof UserProfileView up) {
            return Optional.of(up);
        }
        return Optional.empty();
    }

    /**
     * 从房间缓存删除用户档案
     */
    @Override
    public void deleteUserProfile(String roomId, String userId) {
        if (roomId == null || roomId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        String key = RedisKeys.roomUserProfiles(roomId);
        redisTemplate.opsForHash().delete(key, userId);
    }

    /**
     * 删除房间的所有用户资料缓存
     */
    @Override
    public void deleteAllUserProfiles(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return;
        }
        String key = RedisKeys.roomUserProfiles(roomId);
        redisTemplate.delete(key);
    }

    // ===== 终局累计：round / blackWins / whiteWins / draws =====
    @Override
    public void incrSeriesOnFinish(String roomId, Character winner) {
        final String key = RedisKeys.roomSeries(roomId);
        HashOperations<String, Object, Object> h = redisTemplate.opsForHash();

        // HINCRBY 原子自增
        if (winner == null) {
            h.increment(key, "draws", 1L);
        } else if (winner == 'X') {
            h.increment(key, "blackWins", 1L);
        } else if (winner == 'O') {
            h.increment(key, "whiteWins", 1L);
        } else {
            // 无效的winner值，记录为和棋
            h.increment(key, "draws", 1L);
        }
        h.increment(key, "round", 1L);

        // 维持与房间一致的 TTL（48h）
        redisTemplate.expire(key, Duration.ofHours(48));
    }

    // ===== 读取系列哈希（不存在就初始化 1/0/0/0）=====
    @Override
    public Map<Object, Object> readSeriesHash(String roomId) {
        final String key = RedisKeys.roomSeries(roomId);
        HashOperations<String, Object, Object> h = redisTemplate.opsForHash();
        Map<Object, Object> map = h.entries(key);

        if (map == null || map.isEmpty()) {
            Map<Object, Object> init = new java.util.HashMap<>();
            init.put("round", 1L);        // 使用Long类型，不是字符串
            init.put("blackWins", 0L);    // 使用Long类型，不是字符串
            init.put("whiteWins", 0L);    // 使用Long类型，不是字符串
            init.put("draws", 0L);        // 使用Long类型，不是字符串
            h.putAll(key, init);
            redisTemplate.expire(key, Duration.ofHours(48));
            return init;
        }
        return map;
    }

    // ===== 返回 SeriesView（注意：用 setter，不用构造器）=====
    @Override
    public SeriesView getSeries(String roomId) {
        Map<Object, Object> h = readSeriesHash(roomId);

        int round = parseInt(h.get("round"), 1);
        int bwins = parseInt(h.get("blackWins"), 0);
        int owins = parseInt(h.get("whiteWins"), 0);
        int draws = parseInt(h.get("draws"), 0);

        SeriesView sv = new SeriesView();       // 注意：你的类没有四参构造器
        // 以下 setter 名称与常见 Lombok @Data 保持一致；若你的字段名不同，请按你的类名改动
        sv.setIndex(round);
        sv.setBlackWins(bwins);
        sv.setWhiteWins(owins);
        sv.setDraws(draws);
        return sv;
    }

    /**
     * 删除系列比分缓存
     */
    @Override
    public void deleteSeries(String roomId) {
        ops.del(RedisKeys.roomSeries(roomId));
    }

    // ===== 在线房间索引（ZSET，按创建时间排序） =====

    @Override
    public void addRoomIndex(String roomId, long createdAt, Duration ttl) {
        String key = RedisKeys.roomIndexKey();
        redisTemplate.opsForZSet().add(key, roomId, createdAt);
        // 维持与房间一致的 TTL，避免僵尸索引
        redisTemplate.expire(key, ttl);
    }

    @Override
    public void removeRoomIndex(String roomId) {
        String key = RedisKeys.roomIndexKey();
        redisTemplate.opsForZSet().remove(key, roomId);
    }

    // ===== 私有工具：安全解析 int =====
    private int parseInt(Object v, int def) {
        try { return v == null ? def : Integer.parseInt(v.toString()); }
        catch (Exception e) { return def; }
    }


}
