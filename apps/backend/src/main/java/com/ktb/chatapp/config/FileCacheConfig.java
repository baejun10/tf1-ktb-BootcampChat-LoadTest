package com.ktb.chatapp.config;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileCacheConfig {

    @Bean
    public LoadingCache<String, Optional<File>> fileLoadingCache(
            FileRepository fileRepository,
            FileCacheProperties fileCacheProperties
    ) {
        return Caffeine.newBuilder()
                .maximumSize(fileCacheProperties.getMaximumSize())
                .expireAfterAccess(fileCacheProperties.getExpireAfterAccess())
                .recordStats()
                .build(new CacheLoader<>() {
                    @Override
                    public Optional<File> load(String key) {
                        return fileRepository.findById(key);
                    }

                    @Override
                    public Map<String, Optional<File>> loadAll(Set<? extends String> keys) {
                        if (keys.isEmpty()) {
                            return Collections.emptyMap();
                        }

                        Set<String> orderedKeys = new LinkedHashSet<>(keys);
                        Map<String, Optional<File>> result = new HashMap<>();
                        fileRepository.findAllById(orderedKeys)
                                .forEach(file -> result.put(file.getId(), Optional.of(file)));
                        orderedKeys.forEach(id -> result.putIfAbsent(id, Optional.empty()));
                        return result;
                    }
                });
    }
}
