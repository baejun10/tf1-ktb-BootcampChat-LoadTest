package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.util.FileUtil;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 로컬 디스크를 사용하는 FileService 구현체.
 * 개발/테스트 환경에서 사용되며 storage.provider=local 일 때만 활성화된다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalFileService implements FileService {

    private final Path fileStorageLocation;
    private final FileRepository fileRepository;
    private final MessageRepository messageRepository;
    private final RoomCacheService roomCacheService;

    public LocalFileService(@Value("${file.upload-dir:uploads}") String uploadDir,
                            FileRepository fileRepository,
                            MessageRepository messageRepository,
                            RoomCacheService roomCacheService) {
        this.fileRepository = fileRepository;
        this.messageRepository = messageRepository;
        this.fileStorageLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.roomCacheService = roomCacheService;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (Exception ex) {
            throw new RuntimeException("파일 저장 디렉터리를 생성할 수 없습니다.", ex);
        }
    }

    @Override
    public FileUploadResult uploadFile(MultipartFile file, String uploaderId) {
        try {
            FileUtil.validateFile(file);

            String originalFilename = resolveOriginalFilename(file);
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);
            Path filePath = fileStorageLocation.resolve(safeFileName);
            FileUtil.validatePath(filePath, fileStorageLocation);

            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            File fileEntity = File.builder()
                    .filename(safeFileName)
                    .originalname(FileUtil.normalizeOriginalFilename(originalFilename))
                    .mimetype(file.getContentType())
                    .size(file.getSize())
                    .path(filePath.toString())
                    .user(uploaderId)
                    .uploadDate(LocalDateTime.now())
                    .build();

            File savedFile = fileRepository.save(fileEntity);

            return FileUploadResult.builder()
                    .success(true)
                    .file(savedFile)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("파일 업로드에 실패했습니다.", e);
        }
    }

    @Override
    public String storeFile(MultipartFile file, String subDirectory) {
        try {
            FileUtil.validateFile(file);

            Path targetLocation = fileStorageLocation;
            if (StringUtils.hasText(subDirectory)) {
                targetLocation = fileStorageLocation.resolve(subDirectory);
                Files.createDirectories(targetLocation);
            }

            String originalFilename = resolveOriginalFilename(file);
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);

            Path filePath = targetLocation.resolve(safeFileName);
            FileUtil.validatePath(filePath, targetLocation);

            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            if (StringUtils.hasText(subDirectory)) {
                return "/api/uploads/" + subDirectory + "/" + safeFileName;
            }
            return "/api/uploads/" + safeFileName;
        } catch (IOException e) {
            throw new RuntimeException("파일 저장 중 오류가 발생했습니다.", e);
        }
    }

    @Override
    public Resource loadFileAsResource(String fileName, String requesterId) {
        try {
            //TODO 38 (HIGH): 파일 다운로드마다 file→message→room을 차례로 조회해 Mongo round-trip이 세 번 발생한다. Aggregation lookup이나 캐시를 사용해 단일 쿼리로 권한 검증과 메타데이터 조회를 끝내야 부하 테스트에서 I/O 병목을 피할 수 있다.
            File fileEntity = fileRepository.findByFilename(fileName)
                    .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileName));

            Message message = messageRepository.findByFileId(fileEntity.getId())
                    .orElseThrow(() -> new RuntimeException("파일과 연결된 메시지를 찾을 수 없습니다"));

            Room room = roomCacheService.findRoomById(message.getRoomId())
                    .orElseThrow(() -> new RuntimeException("방을 찾을 수 없습니다"));

            if (!room.getParticipantIds().contains(requesterId)) {
                throw new RuntimeException("파일에 접근할 권한이 없습니다");
            }

            Path filePath = this.fileStorageLocation.resolve(fileName).normalize();
            FileUtil.validatePath(filePath, this.fileStorageLocation);
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists()) {
                return resource;
            }
            throw new RuntimeException("파일을 찾을 수 없습니다: " + fileName);
        } catch (MalformedURLException ex) {
            throw new RuntimeException("파일을 찾을 수 없습니다: " + fileName, ex);
        }
    }

    @Override
    public boolean deleteFile(String fileId, String requesterId) {
        try {
            File fileEntity = fileRepository.findById(fileId)
                    .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

            if (!fileEntity.getUser().equals(requesterId)) {
                throw new RuntimeException("파일을 삭제할 권한이 없습니다.");
            }

            Path filePath = this.fileStorageLocation.resolve(fileEntity.getFilename());
            Files.deleteIfExists(filePath);

            fileRepository.delete(fileEntity);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("파일 삭제 중 오류가 발생했습니다.", e);
        }
    }

    @Override
    public void deleteStoredFileByUrl(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            return;
        }
        String relative = null;
        if (fileUrl.startsWith("/api/uploads/")) {
            relative = fileUrl.substring("/api/uploads/".length());
        } else if (fileUrl.startsWith("/uploads/")) {
            relative = fileUrl.substring("/uploads/".length());
        } else {
            return;
        }

        Path filePath = fileStorageLocation.resolve(relative).normalize();
        try {
            if (Files.exists(filePath) && filePath.startsWith(fileStorageLocation)) {
                Files.deleteIfExists(filePath);
            }
        } catch (IOException e) {
            log.warn("로컬 파일 삭제 실패: {}", filePath, e);
        }
    }

    private String resolveOriginalFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            return "file";
        }
        return StringUtils.cleanPath(originalFilename);
    }
}

