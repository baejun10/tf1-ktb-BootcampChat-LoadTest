package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String KEY_PREFIX = "chat:data:";
    private static final long DEFAULT_TTL = 24;

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            Object value = redisTemplate.opsForValue().get(KEY_PREFIX + key);
            if (value == null) {
                return Optional.empty();
            }

            if (type.isInstance(value)) {
                return Optional.of(type.cast(value));
            }

            T converted = objectMapper.convertValue(value, type);
            return Optional.of(converted);
        } catch (Exception e) {
            log.error("Failed to get value for key: {}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + key, value, DEFAULT_TTL, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Failed to set value for key: {}", key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            redisTemplate.delete(KEY_PREFIX + key);
        } catch (Exception e) {
            log.error("Failed to delete key: {}", key, e);
        }
    }

    @Override
    public int size() {
        try {
            var keys = redisTemplate.keys(KEY_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.error("Failed to get size", e);
            return 0;
        }
    }
}
