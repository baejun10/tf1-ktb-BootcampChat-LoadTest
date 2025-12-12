package com.ktb.chatapp.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SessionRedisStoreTest {

    @Autowired
    private SessionRedisStore sessionRedisStore;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void testSessionSerializationAndDeserialization() throws Exception {
        String userId = "test-user-123";
        String sessionId = "test-session-456";

        SessionMetadata metadata = new SessionMetadata(
                "Mozilla/5.0",
                "127.0.0.1",
                "Test Device"
        );

        Session session = Session.builder()
                .userId(userId)
                .sessionId(sessionId)
                .createdAt(System.currentTimeMillis())
                .lastActivity(System.currentTimeMillis())
                .metadata(metadata)
                .expiresAt(Instant.now().plusSeconds(1800))
                .build();

        sessionRedisStore.save(session);

        Optional<Session> retrieved = sessionRedisStore.findByUserId(userId);

        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getUserId()).isEqualTo(userId);
        assertThat(retrieved.get().getSessionId()).isEqualTo(sessionId);
        assertThat(retrieved.get().getMetadata()).isNotNull();
        assertThat(retrieved.get().getMetadata().userAgent()).isEqualTo("Mozilla/5.0");
        assertThat(retrieved.get().getExpiresAt()).isNotNull();

        sessionRedisStore.deleteAll(userId);
    }

    @Test
    void testInstantSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        Instant now = Instant.now();
        String json = mapper.writeValueAsString(now);
        Instant deserialized = mapper.readValue(json, Instant.class);

        assertThat(deserialized).isEqualTo(now);
    }

    @Test
    void testSessionMetadataRecord() {
        SessionMetadata metadata = new SessionMetadata(
                "User-Agent-Test",
                "192.168.1.1",
                "Device-Test"
        );

        assertThat(metadata.userAgent()).isEqualTo("User-Agent-Test");
        assertThat(metadata.ipAddress()).isEqualTo("192.168.1.1");
        assertThat(metadata.deviceInfo()).isEqualTo("Device-Test");
    }
}
