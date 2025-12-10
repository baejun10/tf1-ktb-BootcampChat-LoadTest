package com.ktb.chatapp.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인프라 상태를 신속히 확인하기 위한 경량 헬스체크 컨트롤러.
 * 현재 프로파일과 타임스탬프만 내려 성능에 영향을 주지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final Environment environment;

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "ok");
        body.put("timestamp", Instant.now().toString());
        body.put("env", resolveEnvironment());
        return ResponseEntity.ok(body);
    }

    private String resolveEnvironment() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length > 0) {
            return activeProfiles[0];
        }

        return environment.getProperty("spring.profiles.active");
    }
}
