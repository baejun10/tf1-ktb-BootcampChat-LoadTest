package com.ktb.chatapp.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "presigned_uploads")
public class PresignedUpload {

    @Id
    private String id;

    @Indexed
    private String token;

    private String filename;

    private String originalname;

    private String mimetype;

    private long expectedSize;

    private long uploadedSize;

    private String path;

    private String userId;

    private PresignedUploadStatus status;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }
}
