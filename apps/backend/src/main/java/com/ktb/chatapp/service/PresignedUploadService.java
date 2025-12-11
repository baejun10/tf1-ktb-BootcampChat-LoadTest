package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.PresignedUploadRequest;
import com.ktb.chatapp.dto.PresignedUploadResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.PresignedUpload;
import com.ktb.chatapp.model.PresignedUploadStatus;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.PresignedUploadRepository;
import com.ktb.chatapp.util.FileUtil;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@Slf4j
public class PresignedUploadService {

    private final PresignedUploadRepository presignedUploadRepository;
    private final FileRepository fileRepository;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;
    private final String baseDirectory;
    private final long expirationSeconds;

    public PresignedUploadService(PresignedUploadRepository presignedUploadRepository,
                                  FileRepository fileRepository,
                                  S3Client s3Client,
                                  S3Presigner s3Presigner,
                                  @Value("${storage.s3.bucket}") String bucketName,
                                  @Value("${storage.s3.base-dir:uploads}") String baseDir,
                                  @Value("${storage.s3.presign-expiration-seconds:900}") long expirationSeconds) {
        Assert.hasText(bucketName, "storage.s3.bucket 설정은 필수입니다.");
        this.presignedUploadRepository = presignedUploadRepository;
        this.fileRepository = fileRepository;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucketName = bucketName;
        this.baseDirectory = sanitizeDirectory(baseDir);
        this.expirationSeconds = expirationSeconds;
    }

    public PresignedUploadResponse createUploadRequest(PresignedUploadRequest request, String userId) {
        FileUtil.validateFileMetadata(request.getFilename(), request.getMimetype(), request.getSize());

        String safeFileName = FileUtil.generateSafeFileName(request.getFilename());
        String normalizedOriginal = FileUtil.normalizeOriginalFilename(request.getFilename());
        String key = buildObjectKey(safeFileName);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(expirationSeconds);

        PresignedUpload upload = PresignedUpload.builder()
                .token(UUID.randomUUID().toString().replace("-", ""))
                .filename(safeFileName)
                .originalname(normalizedOriginal)
                .mimetype(request.getMimetype())
                .expectedSize(request.getSize())
                .uploadedSize(0L)
                .path(key)
                .userId(userId)
                .status(PresignedUploadStatus.PENDING)
                .expiresAt(expiresAt)
                .build();
        presignedUploadRepository.save(upload);

        PresignedPutObjectRequest presignedRequest = generatePresignedUrl(key, request.getMimetype());
        Map<String, String> headers = flattenHeaders(presignedRequest.signedHeaders(), request.getMimetype());

        return PresignedUploadResponse.builder()
                .uploadId(upload.getId())
                .uploadUrl(presignedRequest.url().toString())
                .headers(headers)
                .expiresIn(Duration.between(LocalDateTime.now(), expiresAt).toSeconds())
                .build();
    }

    public File finalizeUpload(String uploadId, String userId) {
        long startTime = System.currentTimeMillis();

        log.info("Finalize upload started - uploadId: {}", uploadId);
        long step1 = System.currentTimeMillis();
        PresignedUpload upload = presignedUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("업로드 세션을 찾을 수 없습니다."));
        log.info("MongoDB findById elapsed: {}ms", System.currentTimeMillis() - step1);

        if (!upload.getUserId().equals(userId)) {
            throw new RuntimeException("업로드를 완료할 권한이 없습니다.");
        }

        if (upload.isExpired()) {
            throw new RuntimeException("업로드 URL이 만료되었습니다.");
        }

        if (upload.getStatus() == PresignedUploadStatus.COMPLETED) {
            throw new RuntimeException("이미 완료된 업로드입니다.");
        }

        HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(upload.getPath())
                .build();

        long contentLength;
        try {
            long step2 = System.currentTimeMillis();
            var headResponse = s3Client.headObject(headRequest);
            contentLength = headResponse.contentLength();
            log.info("S3 headObject elapsed: {}ms", System.currentTimeMillis() - step2);
        } catch (NoSuchKeyException e) {
            throw new RuntimeException("S3에 업로드된 파일을 찾을 수 없습니다.", e);
        }

        upload.setUploadedSize(contentLength);
        upload.setStatus(PresignedUploadStatus.UPLOADED);

        long step3 = System.currentTimeMillis();
        presignedUploadRepository.save(upload);
        log.info("MongoDB save(UPLOADED) elapsed: {}ms", System.currentTimeMillis() - step3);

        File fileEntity = File.builder()
                .filename(upload.getFilename())
                .originalname(upload.getOriginalname())
                .mimetype(upload.getMimetype())
                .size(upload.getUploadedSize())
                .path(upload.getPath())
                .user(userId)
                .uploadDate(LocalDateTime.now())
                .build();

        long step4 = System.currentTimeMillis();
        File savedFile = fileRepository.save(fileEntity);
        log.info("MongoDB save(File) elapsed: {}ms", System.currentTimeMillis() - step4);

        upload.setStatus(PresignedUploadStatus.COMPLETED);

        long step5 = System.currentTimeMillis();
        presignedUploadRepository.save(upload);
        log.info("MongoDB save(COMPLETED) elapsed: {}ms", System.currentTimeMillis() - step5);

        log.info("Finalize upload completed - total elapsed: {}ms", System.currentTimeMillis() - startTime);
        return savedFile;
    }

    private PresignedPutObjectRequest generatePresignedUrl(String key, String mimetype) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(mimetype)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expirationSeconds))
                .putObjectRequest(objectRequest)
                .build();

        return s3Presigner.presignPutObject(presignRequest);
    }

    private Map<String, String> flattenHeaders(Map<String, List<String>> signedHeaders, String mimetype) {
        Map<String, String> headers = new HashMap<>();
        signedHeaders.forEach((key, values) -> {
            if (!values.isEmpty() && !key.equalsIgnoreCase("host")) {
                headers.put(key, values.get(0));
            }
        });
        headers.putIfAbsent("Content-Type", mimetype);
        return headers;
    }

    private String buildObjectKey(String safeFileName) {
        if (!StringUtils.hasText(baseDirectory)) {
            return safeFileName;
        }
        return baseDirectory + "/" + safeFileName;
    }

    private String sanitizeDirectory(String directory) {
        if (!StringUtils.hasText(directory)) {
            return "";
        }
        String sanitized = directory.replace("\\", "/")
                .replaceAll("^\\./", "")
                .replaceAll("^/", "");
        if (sanitized.endsWith("/")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized;
    }
}
