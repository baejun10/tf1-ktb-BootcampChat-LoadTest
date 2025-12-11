package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 읽음 상태 처리 핸들러
 * 메시지 읽음 상태 업데이트 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReadHandler {
    
    private final SocketIOServer socketIOServer;
    private final MessageReadStatusService messageReadStatusService;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final UserRooms userRooms;
    
    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (data == null || data.getMessageIds() == null || data.getMessageIds().isEmpty()) {
                return;
            }

            String roomId = data.getRoomId();

            if (roomId == null || roomId.isBlank()) {
                roomId = messageRepository.findById(data.getMessageIds().getFirst())
                        .map(Message::getRoomId).orElse(null);
            }

            if (roomId == null || roomId.isBlank()) {
                log.warn("Invalid room for user {} with messageIds {}", userId, data.getMessageIds());
                client.sendEvent(ERROR, Map.of("message", "Invalid room"));
                return;
            }

            //TODO : 015 : 세션 검증 과정에서 이미 사용자 정보를 보유하고 있으므로 userRepository.findById 호출을 캐시하거나 skip 하면 읽음 이벤트 처리 지연을 줄일 수 있다.
            if (!userRooms.isInRoom(userId, roomId)) {
                log.warn("User {} not in room {} (UserRooms check)", userId, roomId);
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
                return;
            }

            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                log.warn("Room {} not found in cache for user {}", roomId, userId);
                client.sendEvent(ERROR, Map.of("message", "Room not found"));
                return;
            }

            if (!room.getParticipantIds().contains(userId)) {
                log.warn("User {} not in participants list of room {}. Participants: {}",
                    userId, roomId, room.getParticipantIds());
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
                return;
            }

            // TODO : 022 : updateReadStatus 를 비동기(@Async)로 처리 (고려) => 최종 일관성 문제 있을 수 있음!!!!
            messageReadStatusService.updateReadStatus(data.getMessageIds(), userId);

            MessagesReadResponse response = new MessagesReadResponse(userId, data.getMessageIds());

            // Broadcast to room
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGES_READ, response);

        } catch (Exception e) {
            log.error("Error handling markMessagesAsRead", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
            ));
        }
    }
    
    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user.id();
    }
}
