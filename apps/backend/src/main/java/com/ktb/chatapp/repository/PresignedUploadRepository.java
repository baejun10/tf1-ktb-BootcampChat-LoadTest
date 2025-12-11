package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.PresignedUpload;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PresignedUploadRepository extends MongoRepository<PresignedUpload, String> {
    Optional<PresignedUpload> findByToken(String token);
}
