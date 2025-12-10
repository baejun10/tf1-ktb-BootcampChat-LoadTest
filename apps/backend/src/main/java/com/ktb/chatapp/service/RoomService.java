package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    /**
     * 채팅방 목록/생성/참여/헬스체크 로직을 담당하는 핵심 도메인 서비스.
     * MongoRepository와 이벤트 퍼블리셔를 조합해 REST와 Socket.IO 양쪽에 동일한 상태를 전달한다.
     */

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    private static final LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);

    public RoomsResponse getAllRoomsWithPagination(
            com.ktb.chatapp.dto.PageRequest pageRequest, String name) {

        try {
            // 정렬 설정 검증
            if (!pageRequest.isValidSortField()) {
                pageRequest.setSortField("createdAt");
            }
            if (!pageRequest.isValidSortOrder()) {
                pageRequest.setSortOrder("desc");
            }

            // 정렬 방향 설정
            Sort.Direction direction = "desc".equals(pageRequest.getSortOrder())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

            // 정렬 필드 매핑 (participantsCount는 특별 처리 필요)
            String sortField = pageRequest.getSortField();
            if ("participantsCount".equals(sortField)) {
                sortField = "participantIds"; // MongoDB 필드명으로 변경
            }

            // Pageable 객체 생성
            PageRequest springPageRequest = PageRequest.of(
                pageRequest.getPage(),
                pageRequest.getPageSize(),
                Sort.by(direction, sortField)
            );

            // 검색어가 있는 경우와 없는 경우 분리
            Page<Room> roomPage;
            if (pageRequest.getSearch() != null && !pageRequest.getSearch().trim().isEmpty()) {
                roomPage = roomRepository.findByNameContainingIgnoreCase(
                    pageRequest.getSearch().trim(), springPageRequest);
            } else {
                roomPage = roomRepository.findAll(springPageRequest);
            }

            List<String> roomIds = roomPage.getContent().stream()
                    .map(Room::getId)
                    .toList();

            // Room의 최근 10분간 메시지 수 조회
            Map<String, Long> recentMessageCountMap = messageRepository.countRecentMessagesByRoomIds(roomIds, tenMinutesAgo).stream()
                    .collect(Collectors.toMap(
                            RoomMessageCount::getRoomId,
                            RoomMessageCount::getCount
                    ));

            // Room을 RoomResponse로 변환
            List<RoomResponse> roomResponses = roomPage.getContent().stream()
                .map(room -> mapToRoomResponse(room, name, recentMessageCountMap.getOrDefault(room.getId(), 0L)))
                .collect(Collectors.toList());

            // 메타데이터 생성
            PageMetadata metadata = PageMetadata.builder()
                .total(roomPage.getTotalElements())
                .page(pageRequest.getPage())
                .pageSize(pageRequest.getPageSize())
                .totalPages(roomPage.getTotalPages())
                .hasMore(roomPage.hasNext())
                .currentCount(roomResponses.size())
                .sort(PageMetadata.SortInfo.builder()
                    .field(pageRequest.getSortField())
                    .order(pageRequest.getSortOrder())
                    .build())
                .build();

            return RoomsResponse.builder()
                .success(true)
                .data(roomResponses)
                .metadata(metadata)
                .build();

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                .success(false)
                .data(List.of())
                .build();
        }
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();

            // MongoDB 연결 상태 확인
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                // 간단한 쿼리로 연결 상태 및 지연 시간 측정
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            // 최근 활동 조회
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            // 서비스 상태 정보 구성
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                .connected(isMongoConnected)
                .latency(latency)
                .build());

            return HealthResponse.builder()
                .success(true)
                .services(services)
                .lastActivity(lastActivity)
                .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                .success(false)
                .services(new HashMap<>())
                .build();
        }
    }

    public RoomResponse createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);

        // 새로 생성한 룸에는 채팅이 없음
        RoomResponse roomResponse = mapToRoomResponse(savedRoom, name, 0L);

        // Publish event for room created
        try {
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }

        return roomResponse;
    }

    public RoomResponse findRoomById(String roomId, String name) {
        Room room = roomRepository.findById(roomId).orElse(null);

        long recentMessageCount = messageRepository.countRecentMessagesByRoomId(roomId, tenMinutesAgo);

        return mapToRoomResponse(room, name, recentMessageCount);
    }

    public RoomResponse joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        // 비밀번호 확인
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        // 이미 참여중인지 확인
        if (!room.getParticipantIds().contains(user.getId())) {
            //TODO : 021 : 참가자 추가를 전체 Room 문서를 읽고 저장하는 대신 Mongo $addToSet 업데이트로 처리하면 경합과 write volume 을 줄일 수 있다.
            // 채팅방 참여
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }

        long recentMessageCount = messageRepository.countRecentMessagesByRoomId(room.getId(), tenMinutesAgo);
        RoomResponse roomResponse = mapToRoomResponse(room, name, recentMessageCount);

        // Publish event for room updated
        try {
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return roomResponse;
    }

    private RoomResponse mapToRoomResponse(Room room, String name, long recentMessageCount) {
        if (room == null) return null;

        User creator = null;
        if (room.getCreator() != null) {
            creator = userRepository.findById(room.getCreator()).orElse(null);
        }

        //TODO : 001 : room participantIds 를 한 번에 로딩할 수 있도록 batch query 또는 projection 으로 N+1 조회를 제거하면 대규모 방 목록 조회가 빨라진다.
        // -> MongoDB의 $in 연산자를 사용해 배치 쿼리 1번만 실행합니다.
        List<User> participants = userRepository.findByIdIn(room.getParticipantIds());

        //TODO : 002 : 방 목록 페이징 시 매번 countRecentMessagesByRoomId 를 호출하면 Mongo 쿼리가 방 개수만큼 발생하므로, aggregation 으로 일괄 조회하거나 캐시 레이어를 둬서 호출 빈도를 낮춰야 한다.

        return RoomResponse.builder()
            .id(room.getId())
            .name(room.getName() != null ? room.getName() : "제목 없음")
            .hasPassword(room.isHasPassword())
            .creator(creator != null ? UserResponse.builder()
                .id(creator.getId())
                .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                .email(creator.getEmail() != null ? creator.getEmail() : "")
                .build() : null)
            .participants(participants.stream()
                .filter(p -> p != null && p.getId() != null)
                .map(p -> UserResponse.builder()
                    .id(p.getId())
                    .name(p.getName() != null ? p.getName() : "알 수 없음")
                    .email(p.getEmail() != null ? p.getEmail() : "")
                    .build())
                .collect(Collectors.toList()))
            .createdAtDateTime(room.getCreatedAt())
            .isCreator(creator != null && creator.getId().equals(name))
            .recentMessageCount((int) recentMessageCount)
            .build();
    }
}
