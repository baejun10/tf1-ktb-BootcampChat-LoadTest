package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class RoomCacheService {

    private final RoomRepository roomRepository;

    // Room 조회 결과를 캐시
    @Cacheable(value = "rooms", key = "#roomId", unless = "#result == null")
    public Room findRoomById(String roomId) {
        return roomRepository.findById(roomId).orElse(null);
    }

    // 참가자 목록 업데이트 시 Room 캐시 삭제
    @CacheEvict(value = "rooms", key = "#roomId")
    public void addParticipant(String roomId, String userId) {
        roomRepository.addParticipant(roomId, userId);
    }

    // 참가자 목록 업데이트 시 Room 캐시 삭제
    @CacheEvict(value = "rooms", key = "#roomId")
    public void removeParticipant(String roomId, String userId) {
        roomRepository.removeParticipant(roomId, userId);
    }
}
