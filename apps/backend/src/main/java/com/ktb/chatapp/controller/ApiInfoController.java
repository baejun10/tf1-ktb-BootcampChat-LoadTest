package com.ktb.chatapp.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배포된 API 엔드포인트 목록을 한 번에 보여주는 루트 엔드포인트.
 * 모노레포 구조에서도 백엔드 기능 지도를 빠르게 파악할 수 있도록 정적 정보만 반환한다.
 */
@RestController
@RequestMapping("/api")
public class ApiInfoController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> getApiInfo() {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("base", "/auth");

        Map<String, Map<String, String>> authRoutes = new LinkedHashMap<>();
        authRoutes.put("register", route("POST", "/register"));
        authRoutes.put("login", route("POST", "/login"));
        authRoutes.put("logout", route("POST", "/logout"));
        authRoutes.put("verifyToken", route("POST", "/verify-token"));
        authRoutes.put("refreshToken", route("POST", "/refresh-token"));
        auth.put("routes", authRoutes);

        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("auth", auth);
        endpoints.put("users", "/users");
        endpoints.put("rooms", "/rooms");
        endpoints.put("files", "/files");
        endpoints.put("message", "/message");
        endpoints.put("ai", "/ai");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Chat App API");
        body.put("version", "1.0.0");
        body.put("timestamp", Instant.now().toString());
        body.put("endpoints", endpoints);

        return ResponseEntity.ok(body);
    }

    private Map<String, String> route(String method, String path) {
        return Map.of(
                "method", method,
                "path", path
        );
    }
}
