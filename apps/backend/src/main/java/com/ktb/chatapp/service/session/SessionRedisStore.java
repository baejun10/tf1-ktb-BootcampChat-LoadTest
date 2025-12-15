package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.service.SessionService.SESSION_TTL_SEC;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class SessionRedisStore implements SessionStore {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SessionMongoStore mongoStore;

    private static final String SESSION_KEY_PREFIX = "session:user:";

    private String getUserSessionKey(String userId) {
        return SESSION_KEY_PREFIX + userId;
    }

    @Override
    public Optional<Session> findByUserId(String userId) {
        try {
            Session cached = (Session) redisTemplate.opsForValue().get(getUserSessionKey(userId));
            if (cached != null) {
                return Optional.of(cached);
            }

            Optional<Session> fromMongo = mongoStore.findByUserId(userId);
            fromMongo.ifPresent(session ->
                redisTemplate.opsForValue().set(getUserSessionKey(userId), session, SESSION_TTL_SEC, TimeUnit.SECONDS)
            );
            return fromMongo;
        } catch (Exception e) {
            log.warn("Redis read failed, fallback to MongoDB: {}", e.getMessage());
            return mongoStore.findByUserId(userId);
        }
    }

    @Override
    public Session save(Session session) {
        Session saved = mongoStore.save(session);
        try {
            redisTemplate.opsForValue().set(getUserSessionKey(session.getUserId()), saved, SESSION_TTL_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis write failed: {}", e.getMessage());
        }
        return saved;
    }

    @Override
    public void delete(String userId, String sessionId) {
        mongoStore.delete(userId, sessionId);
        try {
            redisTemplate.delete(getUserSessionKey(userId));
        } catch (Exception e) {
            log.warn("Redis delete failed: {}", e.getMessage());
        }
    }

    @Override
    public void deleteAll(String userId) {
        mongoStore.deleteAll(userId);
        try {
            redisTemplate.delete(getUserSessionKey(userId));
        } catch (Exception e) {
            log.warn("Redis delete failed: {}", e.getMessage());
        }
    }
}
