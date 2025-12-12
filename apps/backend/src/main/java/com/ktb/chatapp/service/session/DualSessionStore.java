package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class DualSessionStore implements SessionStore {

    private final SessionRedisStore redisStore;
    private final SessionMongoStore mongoStore;

    @Override
    public Optional<Session> findByUserId(String userId) {
        try {
            Optional<Session> redisSession = redisStore.findByUserId(userId);
            if (redisSession.isPresent()) {
                log.debug("Session found in Redis for userId: {}", userId);
                return redisSession;
            }

            Optional<Session> mongoSession = mongoStore.findByUserId(userId);
            if (mongoSession.isPresent()) {
                log.debug("Session found in MongoDB for userId: {}, migrating to Redis", userId);
                redisStore.save(mongoSession.get());
            }
            return mongoSession;
        } catch (Exception e) {
            log.error("Error finding session for userId: {}", userId, e);
            return mongoStore.findByUserId(userId);
        }
    }

    @Override
    public Session save(Session session) {
        try {
            redisStore.save(session);
            mongoStore.save(session);
            log.debug("Session saved to both Redis and MongoDB for userId: {}", session.getUserId());
            return session;
        } catch (Exception e) {
            log.error("Error saving session to dual stores for userId: {}", session.getUserId(), e);
            throw new RuntimeException("Failed to save session to dual stores", e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        try {
            redisStore.delete(userId, sessionId);
            mongoStore.delete(userId, sessionId);
            log.debug("Session deleted from both Redis and MongoDB for userId: {}, sessionId: {}", userId, sessionId);
        } catch (Exception e) {
            log.error("Error deleting session from dual stores for userId: {}, sessionId: {}", userId, sessionId, e);
            throw new RuntimeException("Failed to delete session from dual stores", e);
        }
    }

    @Override
    public void deleteAll(String userId) {
        try {
            redisStore.deleteAll(userId);
            mongoStore.deleteAll(userId);
            log.debug("All sessions deleted from both Redis and MongoDB for userId: {}", userId);
        } catch (Exception e) {
            log.error("Error deleting all sessions from dual stores for userId: {}", userId, e);
            throw new RuntimeException("Failed to delete all sessions from dual stores", e);
        }
    }
}
