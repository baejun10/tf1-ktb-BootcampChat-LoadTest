package com.ktb.chatapp.dto;

import lombok.Data;

@Data
public class PresignedUploadRequest {
    private String filename;
    private String mimetype;
    private long size;
}
