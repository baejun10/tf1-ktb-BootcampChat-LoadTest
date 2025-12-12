package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.redis.CacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component("sessionRedisStore")
@RequiredArgsConstructor
public class SessionRedisStore implements SessionStore {

    private static final String KEY_PREFIX = "session:user:";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private final CacheRepository cacheRepository;

    @Override
    public Optional<Session> findByUserId(String userId) {
        try {
            String key = KEY_PREFIX + userId;
            Session session = cacheRepository.get(key, Session.class);
            return Optional.ofNullable(session);
        } catch (Exception e) {
            log.error("Error finding session for userId: {}", userId, e);
            return Optional.empty();
        }
    }

    @Override
    public Session save(Session session) {
        try {
            String key = KEY_PREFIX + session.getUserId();
            cacheRepository.save(key, session, SESSION_TTL);
            return session;
        } catch (Exception e) {
            log.error("Error saving session for userId: {}", session.getUserId(), e);
            throw new RuntimeException("Failed to save session to Redis", e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        try {
            Session session = findByUserId(userId).orElse(null);
            if (session != null && sessionId.equals(session.getSessionId())) {
                cacheRepository.delete(KEY_PREFIX + userId);
                log.debug("Session deleted for userId: {}, sessionId: {}", userId, sessionId);
            }
        } catch (Exception e) {
            log.error("Error deleting session for userId: {}, sessionId: {}", userId, sessionId, e);
            throw new RuntimeException("Failed to delete session from Redis", e);
        }
    }

    @Override
    public void deleteAll(String userId) {
        try {
            cacheRepository.delete(KEY_PREFIX + userId);
            log.debug("All sessions deleted for userId: {}", userId);
        } catch (Exception e) {
            log.error("Error deleting all sessions for userId: {}", userId, e);
            throw new RuntimeException("Failed to delete all sessions from Redis", e);
        }
    }
}
