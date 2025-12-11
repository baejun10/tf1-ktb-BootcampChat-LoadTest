package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.util.FileUtil;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3를 기반으로 파일을 저장/조회하는 FileService 구현체.
 * Presigned 업로드가 완료된 파일은 이 서비스를 통해 메타데이터를 검증하고 스트리밍된다.
 */
@Slf4j
@Service
public class S3FileService implements FileService {

    private final String bucketName;
    private final String region;
    private final String baseDirectory;
    private final String publicBaseUrl;
    private final FileRepository fileRepository;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final S3Client s3Client;

    public S3FileService(@Value("${storage.s3.bucket}") String bucketName,
                         @Value("${storage.s3.region}") String region,
                         @Value("${storage.s3.base-dir:uploads}") String baseDir,
                         @Value("${storage.s3.public-base-url:}") String publicBaseUrl,
                         FileRepository fileRepository,
                         MessageRepository messageRepository,
                         RoomRepository roomRepository,
                         S3Client s3Client) {
        Assert.hasText(bucketName, "storage.s3.bucket 설정은 필수입니다.");
        Assert.hasText(region, "storage.s3.region 설정은 필수입니다.");

        this.bucketName = bucketName;
        this.region = region;
        this.baseDirectory = sanitizeDirectory(baseDir);
        this.publicBaseUrl = normalizePublicBaseUrl(
                StringUtils.hasText(publicBaseUrl) ? publicBaseUrl : buildDefaultBaseUrl(bucketName, region)
        );
        this.fileRepository = fileRepository;
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.s3Client = s3Client;
    }

    @Override
    public FileUploadResult uploadFile(MultipartFile file, String uploaderId) {
        try (InputStream inputStream = file.getInputStream()) {
            FileUtil.validateFile(file);

            String originalFilename = resolveOriginalFilename(file);
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);
            String key = buildObjectKey(safeFileName);

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(inputStream, file.getSize()));

            File fileEntity = File.builder()
                    .filename(safeFileName)
                    .originalname(FileUtil.normalizeOriginalFilename(originalFilename))
                    .mimetype(file.getContentType())
                    .size(file.getSize())
                    .path(key)
                    .user(uploaderId)
                    .uploadDate(LocalDateTime.now())
                    .build();

            File savedFile = fileRepository.save(fileEntity);
            return FileUploadResult.builder()
                    .success(true)
                    .file(savedFile)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("파일 업로드 중 오류가 발생했습니다.", e);
        }
    }

    @Override
    public String storeFile(MultipartFile file, String subDirectory) {
        try (InputStream inputStream = file.getInputStream()) {
            FileUtil.validateFile(file);

            String originalFilename = resolveOriginalFilename(file);
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);
            String key = buildObjectKey(safeFileName, sanitizeDirectory(subDirectory));

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(inputStream, file.getSize()));
            log.info("프로필 파일 업로드 완료: {}", key);

            return buildPublicUrl(key);
        } catch (IOException e) {
            throw new RuntimeException("파일 저장 중 오류가 발생했습니다.", e);
        }
    }

    @Override
    public Resource loadFileAsResource(String fileName, String requesterId) {
        File fileEntity = fileRepository.findByFilename(fileName)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileName));

        Message message = messageRepository.findByFileId(fileEntity.getId())
                .orElseThrow(() -> new RuntimeException("파일과 연결된 메시지를 찾을 수 없습니다"));

        Room room = roomRepository.findById(message.getRoomId())
                .orElseThrow(() -> new RuntimeException("방을 찾을 수 없습니다"));

        if (!room.getParticipantIds().contains(requesterId)) {
            throw new RuntimeException("파일에 접근할 권한이 없습니다");
        }

        String key = resolveObjectKey(fileEntity);

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        return new InputStreamResource(s3Client.getObject(getRequest));
    }

    @Override
    public boolean deleteFile(String fileId, String requesterId) {
        File fileEntity = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

        if (!fileEntity.getUser().equals(requesterId)) {
            throw new RuntimeException("파일을 삭제할 권한이 없습니다.");
        }

        deleteObjectQuietly(resolveObjectKey(fileEntity));
        fileRepository.delete(fileEntity);
        return true;
    }

    @Override
    public void deleteStoredFileByUrl(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            return;
        }

        if (StringUtils.hasText(publicBaseUrl) && fileUrl.startsWith(publicBaseUrl)) {
            String key = fileUrl.substring(publicBaseUrl.length());
            if (key.startsWith("/")) {
                key = key.substring(1);
            }
            if (StringUtils.hasText(key)) {
                deleteObjectQuietly(key);
            }
        } else if (fileUrl.startsWith("/uploads/")) {
            // Legacy 로컬 경로 호환
            String legacyKey = fileUrl.substring("/uploads/".length());
            String normalized = legacyKey.startsWith("/") ? legacyKey.substring(1) : legacyKey;
            if (StringUtils.hasText(normalized)) {
                deleteObjectQuietly(combineDirectories(baseDirectory, normalized));
            }
        }
    }

    private void deleteObjectQuietly(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
        } catch (NoSuchKeyException ignored) {
            log.debug("S3 객체가 이미 삭제되었습니다: {}", key);
        } catch (Exception e) {
            log.warn("S3 객체 삭제 실패: {}", key, e);
        }
    }

    private String resolveOriginalFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            return "file";
        }
        return StringUtils.cleanPath(originalFilename);
    }

    private String resolveObjectKey(File fileEntity) {
        String path = fileEntity.getPath();
        if (StringUtils.hasText(path)) {
            return path;
        }
        return buildObjectKey(fileEntity.getFilename());
    }

    private String buildObjectKey(String safeFileName) {
        return buildObjectKey(safeFileName, "");
    }

    private String buildObjectKey(String safeFileName, String extraDirectory) {
        String directory = combineDirectories(baseDirectory, extraDirectory);
        if (!StringUtils.hasText(directory)) {
            return safeFileName;
        }
        return directory + "/" + safeFileName;
    }

    private String combineDirectories(String primary, String secondary) {
        if (!StringUtils.hasText(primary) && !StringUtils.hasText(secondary)) {
            return "";
        }
        if (!StringUtils.hasText(primary)) {
            return secondary;
        }
        if (!StringUtils.hasText(secondary)) {
            return primary;
        }
        return primary + "/" + secondary;
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

    private String normalizePublicBaseUrl(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return "";
        }
        if (candidate.endsWith("/")) {
            return candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private String buildDefaultBaseUrl(String bucket, String region) {
        return String.format(Locale.US, "https://%s.s3.%s.amazonaws.com", bucket, region);
    }

    private String buildPublicUrl(String key) {
        String normalizedKey = key.startsWith("/") ? key.substring(1) : key;
        return String.format("%s/%s", publicBaseUrl, normalizedKey);
    }
}
