package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class RoomCacheService {

    private final RoomRepository roomRepository;

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public Optional<Room> addParticipant(String roomId, String userId) {
        roomRepository.addParticipant(roomId, userId);
        return roomRepository.findById(roomId);
    }

    public Optional<Room> removeParticipant(String roomId, String userId) {
        roomRepository.removeParticipant(roomId, userId);
        return roomRepository.findById(roomId);
    }
}
