package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 입장 처리 핸들러
 * 채팅방 입장, 참가자 관리, 초기 메시지 로드 담당
 *
 * 주요 기능:
 * 1. 사용자 인증 및 검증
 * 2. 채팅방 참가자 목록 업데이트 (MongoDB $addToSet 원자적 연산)
 * 3. 초기 메시지 30개 로드
 * 4. 입장 시스템 메시지 생성 및 브로드캐스트
 * 5. 참가자 목록 업데이트 이벤트 전송
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomJoinHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageLoader messageLoader;
    private final MessageResponseMapper messageResponseMapper;
    private final RoomLeaveHandler roomLeaveHandler;


    /**
     * 방 입장 이벤트 핸들러
     *
     * 흐름:
     * 1. 사용자 인증 (userId 추출)
     * 2. 사용자 존재 여부 검증
     * 3. 채팅방 존재 여부 검증
     * 4. 중복 입장 체크 (이미 입장한 경우 조기 반환)
     * 5. 참가자 목록 업데이트 (MongoDB $addToSet)
     * 6. Socket.IO room 입장 + 메모리 상태 업데이트
     * 7. 입장 시스템 메시지 생성 및 저장
     * 8. 초기 메시지 30개 로드
     * 9. 참가자 정보 조회 (N+1 문제 있음 - TODO 020)
     * 10. 클라이언트에 JOIN_ROOM_SUCCESS 응답
     * 11. 방 전체에 입장 메시지 브로드캐스트
     * 12. 방 전체에 참가자 목록 업데이트 브로드캐스트
     *
     * @param client Socket.IO 클라이언트
     * @param roomId 입장할 채팅방 ID
     */
    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        try {
            String userId = getUserId(client);
            String userName = getUserName(client);

            if (userId == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
                return;
            }
            
            if (userRepository.findById(userId).isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "User not found"));
                return;
            }
            
            if (roomRepository.findById(roomId).isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }
            
            // 이미 해당 방에 참여 중인지 확인
            if (userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} already in room {}", userId, roomId);
                client.joinRoom(roomId);
                client.sendEvent(JOIN_ROOM_SUCCESS, Map.of("roomId", roomId));
                return;
            }

            // MongoDB의 $addToSet 연산자를 사용한 원자적 업데이트
            roomRepository.addParticipant(roomId, userId);

            // Join socket room and add to user's room set
            client.joinRoom(roomId);
            userRooms.add(userId, roomId);

            Message joinMessage = Message.builder()
                .roomId(roomId)
                .content(userName + "님이 입장하였습니다.")
                .type(MessageType.system)
                .timestamp(LocalDateTime.now())
                .mentions(new ArrayList<>())
                .isDeleted(false)
                .reactions(new HashMap<>())
                .readers(new ArrayList<>())
                .metadata(new HashMap<>())
                .build();

            joinMessage = messageRepository.save(joinMessage);

            // 초기 메시지 로드
            FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse messageLoadResult = messageLoader.loadMessages(req, userId);

            //TODO : 019 : 방 재조회 없이 Mongo update 결과를 반환받거나 캐시에서 참가자 목록을 유지하면 재입장 시 불필요한 findById 를 줄일 수 있다.
            // 업데이트된 room 다시 조회하여 최신 participantIds 가져오기
            Optional<Room> roomOpt = roomRepository.findById(roomId);
            if (roomOpt.isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            // 참가자 정보 조회
            //TODO : 020 : 참가자 정보를 매번 userRepository.findById 로 순차 조회하는 대신 findAllById 또는 Redis 캐시를 사용해 대규모 방의 참가자 리스트 응답 시간을 줄일 수 있다.
            //TODO : 024 : Stream에서 map(userRepository::findById)는 참가자 수만큼 DB 쿼리를 발생시키므로 N+1 문제가 발생한다. userRepository.findAllById()로 batch 조회하라.
            List<UserResponse> participants = roomOpt.get().getParticipantIds()
                    .stream()
                    .map(userRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(UserResponse::from)
                    .toList();
            
            JoinRoomSuccessResponse response = JoinRoomSuccessResponse.builder()
                .roomId(roomId)
                .participants(participants)
                .messages(messageLoadResult.getMessages())
                .hasMore(messageLoadResult.isHasMore())
                .activeStreams(Collections.emptyList())
                .build();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            // 입장 메시지 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                .sendEvent(MESSAGE, messageResponseMapper.mapToMessageResponse(joinMessage, null));

            // 참가자 목록 업데이트 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                .sendEvent(PARTICIPANTS_UPDATE, participants);

            log.info("User {} joined room {} successfully. Message count: {}, hasMore: {}",
                userName, roomId, messageLoadResult.getMessages().size(), messageLoadResult.isHasMore());

        } catch (Exception e) {
            log.error("Error handling joinRoom", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                "message", e.getMessage() != null ? e.getMessage() : "채팅방 입장에 실패했습니다."
            ));
        }
    }
    
    private SocketUser getUser(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.name() : null;
    }
}
