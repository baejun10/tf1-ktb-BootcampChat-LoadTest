package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of SessionStore.
 * Uses SessionRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class SessionMongoStore implements SessionStore {
    
    private final SessionRepository sessionRepository;
    private final MongoTemplate mongoTemplate;
    
    @Override
    public Optional<Session> findByUserId(String userId) {
        return sessionRepository.findByUserId(userId);
    }

    /**
     * userId 기준으로 upsert 수행.
     * - 유저당 세션 1개 정책을 MongoDB 가 원자적으로 보장
     * - 다중 서버에서 동시 로그인 요청이 들어와도 세션 문서는 1개만 유지됨
     */
    @Override
    public Session save(Session session) {
        Query query = Query.query(Criteria.where("userId").is(session.getUserId()));

        Update update = new Update()
                .set("sessionId", session.getSessionId())
                .set("createdAt", session.getCreatedAt())
                .set("lastActivity", session.getLastActivity())
                .set("metadata", session.getMetadata())
                .set("expiresAt", session.getExpiresAt());

        FindAndModifyOptions options = FindAndModifyOptions.options()
                .upsert(true)
                .returnNew(true);

        return mongoTemplate.findAndModify(query, update, options, Session.class);
    }

    @Override
    public void delete(String userId, String sessionId) {
        Query query = Query.query(
                Criteria.where("userId").is(userId)
                        .and("sessionId").is(sessionId)
        );
        mongoTemplate.remove(query, Session.class);
    }

    @Override
    public void deleteAll(String userId) {
        Query query = Query.query(Criteria.where("userId").is(userId));
        mongoTemplate.remove(query, Session.class);
    }
}
