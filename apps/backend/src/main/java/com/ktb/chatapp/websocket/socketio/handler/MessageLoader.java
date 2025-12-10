package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.List;
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
     * - DB 쿼리: 1회 (메시지 조회)
     * - N+1 문제: User 조회 N회 + File 조회 M회 (TODO 014, 018 참고)
     * - 메모리: O(limit) - 메시지 목록을 메모리에 적재
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
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        Page<Message> messagePage = messageRepository
                .findByRoomIdAndIsDeletedAndTimestampBefore(roomId, false, before, pageable);

        List<Message> messages = messagePage.getContent();

        // DESC로 조회했으므로 ASC로 재정렬 (채팅 UI 표시 순서)
        List<Message> sortedMessages = messages.reversed();
        
        var messageIds = sortedMessages.stream().map(Message::getId).toList();
        messageReadStatusService.updateReadStatus(messageIds, userId);

        //TODO : 014 : 메시지 송신자 정보를 메시지 목록과 함께 batch 로딩하거나 projection 으로 합쳐 가져오면 메시지 수만큼 userRepository.findById 를 호출하는 N+1 문제를 줄일 수 있다.
        //TODO : 022 : sortedMessages 를 stream() 으로 2번 순회하는 대신, 한 번의 순회로 messageIds 추출과 MessageResponse 생성을 동시에 처리하면 성능 개선 가능 (미미한 개선이지만 코드 간결화)
        //TODO : 023 : messageReadStatusService.updateReadStatus 를 비동기(@Async)로 처리하면 메시지 로드 응답 속도를 개선할 수 있다 (읽음 상태는 eventual consistency 허용 가능)
        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(message -> {
                    var user = findUserById(message.getSenderId());
                    return messageResponseMapper.mapToMessageResponse(message, user);
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

    /**
     * AI 경우 null 반환 가능
     */
    @Nullable
    private User findUserById(String id) {
        if (id == null) {
            return null;
        }
        return userRepository.findById(id)
                .orElse(null);
    }
}
