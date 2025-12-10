package com.ktb.chatapp.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.ktb.chatapp.model.File;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

    /**
     * File 조회를 위한 Caffeine 기반 캐시.
     * MessageResponse 매핑 전에 batch 조회 결과를 캐시해 N+1을 제거한다.
     */
    @Service
    public class FileCacheService {

    private final LoadingCache<String, Optional<File>> cache;

    public FileCacheService(LoadingCache<String, Optional<File>> cache) {
        this.cache = cache;
    }

    /**
     * 파일 ID 목록을 캐시/Batched 조회 후 Map으로 반환.
     */
    public Map<String, File> getFiles(Collection<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<String> uniqueIds = fileIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (uniqueIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Optional<File>> cachedEntries = cache.getAll(uniqueIds);
        Map<String, File> resolved = new HashMap<>();
        cachedEntries.forEach((id, maybeFile) -> maybeFile.ifPresent(file -> resolved.put(id, file)));
        return resolved;
    }

    /**
     * 단일 파일 조회 (캐시 활용).
     */
    public Optional<File> getFile(String fileId) {
        if (fileId == null) {
            return Optional.empty();
        }
        return cache.get(fileId);
    }
}
