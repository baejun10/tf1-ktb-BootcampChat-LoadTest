package com.ktb.chatapp.model;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "files")
public class File {

    @Id
    private String id;

    @Indexed
    private String filename;

    private String originalname;

    private String mimetype;

    private long size;

    private String path;

    @Field("user")
    @Indexed
    private String user;

    @Field("uploadDate")
    @CreatedDate
    @Indexed
    private LocalDateTime uploadDate;

    private static final Set<String> PREVIEWABLE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "video/mp4", "video/webm",
            "audio/mpeg", "audio/wav",
            "application/pdf"
    );

    /**
     * 미리보기 지원 여부 확인
     */
    public boolean isPreviewable() {
        if (this.mimetype == null || this.mimetype.trim().isEmpty()) {
            return false;
        }
        String normalized = normalizeMimeType(this.mimetype);
        return PREVIEWABLE_TYPES.contains(normalized);
    }

    private String normalizeMimeType(String mimetype) {
        String normalized = mimetype.trim().toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf(';');
        if (separatorIndex > -1) {
            normalized = normalized.substring(0, separatorIndex);
        }
        return normalized;
    }
}
