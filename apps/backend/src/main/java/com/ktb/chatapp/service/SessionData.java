package com.ktb.chatapp.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionData {
    private String userId;
    private String sessionId;
    private long createdAt;
    private long lastActivity;
    private SessionMetadata metadata;
}
