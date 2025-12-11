package com.ktb.chatapp.dto;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PresignedUploadResponse {
    private String uploadUrl;
    private String uploadId;
    private Map<String, String> headers;
    private long expiresIn;
}
