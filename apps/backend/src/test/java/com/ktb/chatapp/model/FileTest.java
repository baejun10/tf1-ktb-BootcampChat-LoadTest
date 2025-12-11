package com.ktb.chatapp.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileTest {

    @Test
    @DisplayName("미리보기 가능한 MIME 타입은 파라미터가 있어도 true 를 반환한다")
    void previewableMimeTypeWithParameters() {
        File file = File.builder()
                .mimetype("application/pdf; charset=UTF-8")
                .build();

        assertTrue(file.isPreviewable());
    }

    @Test
    @DisplayName("지원하지 않는 MIME 타입은 미리보기를 false 로 반환한다")
    void unsupportedMimeType() {
        File file = File.builder()
                .mimetype("application/zip")
                .build();

        assertFalse(file.isPreviewable());
    }
}
