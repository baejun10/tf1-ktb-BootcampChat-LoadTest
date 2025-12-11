package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.FileCacheService;
import com.ktb.chatapp.service.MessageReadStatusService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageResponseMapper messageResponseMapper;
    private final FileCacheService fileCacheService;
    private final MessageReadStatusService messageReadStatusService;

    private static final int BATCH_SIZE = 30;

    /**
     * 메시지 로드
     */
    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            return loadMessagesInternal(data.roomId(), data.limit(BATCH_SIZE), data.before(LocalDateTime.now()), userId);
        } catch (Exception e) {
            log.error("Error loading initial messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    /**
     * 메시지 로드 내부 로직
     *
     * 흐름:
     * 1. 페이징 설정 (timestamp 내림차순으로 최신 메시지부터 조회)
     * 2. DB에서 메시지 조회 (roomId, isDeleted=false, timestamp < before 조건)
     * 3. 메시지 순서 재정렬 (DESC → ASC: 채팅 UI는 오래된 메시지가 위에 표시)
     * 4. 읽음 상태 업데이트 (현재 사용자가 메시지를 읽음 처리)
     * 5. MessageResponse 생성 (사용자 정보, 파일 정보 포함)
     * 6. 응답 반환 (메시지 목록 + hasMore 플래그)
     *
     * 성능 특성:
     * - DB 쿼리: 2회 (메시지 조회 1회 + User batch 조회 1회)
     * - N+1 문제 해결: User는 batch loading으로 해결 (TODO 014 완료)
     * - 남은 N+1: File 조회 M회 (TODO 018 참고)
     * - 메모리: O(limit) - 메시지 목록 + User Map 적재
     *
     * @param roomId 조회할 채팅방 ID
     * @param limit 조회할 메시지 수 (페이지 크기)
     * @param before 이 시각 이전의 메시지만 조회 (페이지네이션 커서)
     * @param userId 현재 사용자 ID (읽음 상태 업데이트용)
     * @return 메시지 목록과 추가 페이지 존재 여부
     */
    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId) {
        /// 페이징 설정 (timestamp 내림차순으로 최신 메시지부터 조회)
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        /// 2. DB에서 메시지 조회 (roomId, isDeleted=false, timestamp < before 조건)
        Page<Message> messagePage = messageRepository
                .findByRoomIdAndIsDeletedAndTimestampBefore(roomId, false, before, pageable);

        List<Message> messages = messagePage.getContent();

        /// 메시지 순서 재정렬 (DESC → ASC: 채팅 UI는 오래된 메시지가 위에 표시)
        List<Message> sortedMessages = messages.reversed();

        /// [개선 014, 023] 한 번의 순회로 messageIds와 senderIds를 동시에 추출하여 Stream 이중 순회 제거

        List<String> messageIds = new ArrayList<>(sortedMessages.size());
        List<String> senderIds = new ArrayList<>(sortedMessages.size());

        List<String> fileIds = new ArrayList<>(sortedMessages.size());

        for (Message message : sortedMessages) {
            messageIds.add(message.getId());
            if (message.getSenderId() != null) {
                senderIds.add(message.getSenderId());
            }
            if (message.getFileId() != null) {
                fileIds.add(message.getFileId());
            }
        }

        //TODO : 022 : messageReadStatusService.updateReadStatus 를 비동기(@Async)로 처리하면 메시지 로드 응답 속도를 개선할 수 있다 (읽음 상태는 eventual consistency 허용 가능)
        messageReadStatusService.updateReadStatus(messageIds, userId);

        /// [개선 014] Batch loading으로 User N+1 문제 해결: N회 쿼리 → 1회 쿼리
        Map<String, User> userMap = userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        Map<String, File> fileMap = fileCacheService.getFiles(fileIds);

        /// [개선 014] Map에서 User 정보를 O(1)로 조회하여 MessageResponse 생성 + File cache 활용
        /// [개선 018] : mapToMessageResponse을 사용하는 곳으로 파일 캐싱으로 해결
        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(message -> {
                    var user = userMap.get(message.getSenderId());
                    return messageResponseMapper.mapToMessageResponse(
                            message,
                            user,
                            fileMap.get(message.getFileId())
                    );
                })
                .collect(Collectors.toList());

        boolean hasMore = messagePage.hasNext();

        log.debug("Messages loaded - roomId: {}, limit: {}, count: {}, hasMore: {}",
                roomId, limit, messageResponses.size(), hasMore);

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .build();
    }

}
