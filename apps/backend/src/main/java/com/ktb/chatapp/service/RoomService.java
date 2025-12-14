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
import java.util.*;
import java.util.function.Function;
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

    /**
     * 개선: N+1 문제 해결 (방 N, 방당 참가자 M 기준)
     * - 개선 전: room 1 + N * (creator 1 + participants × M + recentMessageCount 1) = 총 쿼리 NxM+1개
     * - 개선 후: room 1 + creator&participants 1 + recentMessageCount 1 = 총 쿼리 3개
     */
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

            // 모든 creator와 participant 정보를 배치로 조회
            List<RoomResponse> roomResponses = mapRoomsToResponses(roomPage.getContent(), name);

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

        // 새로 생성한 방에는 채팅이 없고 참가자는 방장 한 명, 메시지는 0개
        RoomResponse roomResponse = mapToRoomResponse(savedRoom, name, creator, List.of(UserResponse.from(creator)), 0);

        // Publish event for room created
        try {
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }

        return roomResponse;
    }

    /**
     * 개선: Aggregation Pipeline 사용
     * - 개선 전: room 1 + creator 1 + participants × N + recentMessageCount 1 = 총 쿼리 N+3개
     * - 개선 후: aggregation 쿼리 1 + recentMessageCount 1 = 총 쿼리 2개
     */
    public RoomResponse findRoomById(String roomId, String name) {
        RoomWithUsers roomWithUsers = roomRepository.findRoomWithUsersById(roomId);
        if (roomWithUsers == null) {
            return null;
        }

        long recentMessageCount = messageRepository.countRecentMessagesByRoomId(roomWithUsers.getId(), tenMinutesAgo);
        User creator = roomWithUsers.getCreatorUser();
        List<User> participants = new ArrayList<>(roomWithUsers.getParticipants());
        boolean isCreator = creator != null && creator.getId().equals(name);

        return mapToRoomResponse(roomWithUsers, creator, participants, isCreator, recentMessageCount);
    }

    /**
     * 개선: Aggregation Pipeline + Atomic Update
     * - 개선 전: room 1 + creator 1 + participants × N + recentMessageCount 1 = 총 쿼리 N+3개 & 전체 Room 저장
     * - 개선 후: aggregation 쿼리 1 + recentMessageCount 1 = 총 쿼리 2개 & atomic update
     */
    public RoomResponse joinRoom(String roomId, String password, String name) {
        // 현재 사용자 조회
        User user = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        // 참가자 추가 (atomic 연산)
        roomRepository.addParticipant(roomId, user.getId());

        // Aggregation으로 room + creator + participants를 한 번에 조회
        RoomWithUsers roomAgg = roomRepository.findRoomWithUsersById(roomId);
        if (roomAgg == null) {
            return null;
        }

        // 비밀번호 검증
        if (roomAgg.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, roomAgg.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        // 최근 메시지 카운트 조회
        long recentMessageCount = messageRepository.countRecentMessagesByRoomId(roomAgg.getId(), tenMinutesAgo);

        // 응답 생성
        User creator = roomAgg.getCreatorUser();
        List<User> participants = new ArrayList<>(roomAgg.getParticipants());
        participants.add(user); // 방금 추가한 사용자를 응답에 포함
        boolean isCreator = creator != null && creator.getId().equals(user.getId());

        RoomResponse roomResponse = mapToRoomResponse(roomAgg, creator, participants, isCreator, recentMessageCount);

        // Room 업데이트 이벤트 발행
        try {
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return roomResponse;
    }

    private List<RoomResponse> mapRoomsToResponses(List<Room> rooms, String name) {
        if (rooms.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. creator와 participants ID 수집
        Set<String> userIds = new HashSet<>();
        rooms.forEach(room -> {
            if (room.getCreator() != null) {
                userIds.add(room.getCreator());
            }
            if (room.getParticipantIds() != null) {
                userIds.addAll(room.getParticipantIds());
            }
        });

        // 2. 배치로 creator와 participants 조회
        List<User> users = userRepository.findAllById(userIds);
        Map<String, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        // 3: 모든 방의 ID 수집 및 메시지 카운트 조회
        List<String> roomIds = rooms.stream()
                .map(Room::getId)
                .collect(Collectors.toList());

        Map<String, Long> recentMessageCountMap = getRecentMessageCountByRoomIds(roomIds);

        // 4: RoomResponse 생성
        return rooms.stream()
                .map(room -> createRoomResponse(room, userMap, recentMessageCountMap, name))
                .collect(Collectors.toList());
    }

    private Map<String, Long> getRecentMessageCountByRoomIds(List<String> roomIds) {
        if (roomIds.isEmpty()) {
            return new HashMap<>();
        }

        List<RoomMessageCount> results = messageRepository.countRecentMessagesByRoomIds(roomIds, RoomService.tenMinutesAgo);

        Map<String, Long> countMap = new HashMap<>();
        results.forEach(roomMessage -> {
            countMap.put(roomMessage.getRoomId(), roomMessage.getCount());
        });

        return countMap;
    }

    /**
     * Batch 로드된 데이터로부터 RoomResponse 생성 (페이징 조회용)
     */
    private RoomResponse createRoomResponse(Room room, Map<String, User> userMap, Map<String, Long> recentMessageCountMap, String name) {
        if (room == null) {
            return null;
        }

        User creator = userMap.get(room.getCreator());
        List<UserResponse> participantResponses = room.getParticipantIds().stream()
                .map(userMap::get)
                .filter(Objects::nonNull)
                .map(UserResponse::from)
                .collect(Collectors.toList());

        long recentMessageCount = recentMessageCountMap.getOrDefault(room.getId(), 0L);

        return mapToRoomResponse(room, name, creator, participantResponses, (int) recentMessageCount);
    }

    /**
     * Aggregation 결과로부터 RoomResponse 생성
     */
    private RoomResponse mapToRoomResponse(RoomWithUsers roomWithUsers, User creator, List<User> participants, boolean isCreator, long recentMessageCount) {
        return RoomResponse.builder()
                .id(roomWithUsers.getId())
                .name(roomWithUsers.getName() != null ? roomWithUsers.getName() : "제목 없음")
                .hasPassword(roomWithUsers.isHasPassword())
                .creator(creator != null ? UserResponse.from(creator) : null)
                .participants(participants.stream()
                        .filter(p -> p != null && p.getId() != null)
                        .map(UserResponse::from)
                        .collect(Collectors.toList()))
                .createdAtDateTime(roomWithUsers.getCreatedAt())
                .isCreator(isCreator)
                .recentMessageCount((int) recentMessageCount)
                .build();
    }

    private static RoomResponse mapToRoomResponse(Room room, String name, User creator, List<UserResponse> participantResponses, int recentMessageCount) {
        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword())
                .creator(creator != null ? UserResponse.from(creator) : null)
                .participants(participantResponses)
                .createdAtDateTime(room.getCreatedAt())
                .isCreator(creator != null && creator.getId().equals(name))
                .recentMessageCount(recentMessageCount)
                .build();
    }
}