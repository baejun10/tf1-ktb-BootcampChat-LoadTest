package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Room;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoomRepository extends MongoRepository<Room, String>, RoomCustomRepository {

    // 페이지네이션과 함께 모든 방 조회
    Page<Room> findAll(Pageable pageable);

    // 검색어와 함께 페이지네이션 조회
    Page<Room> findByNameContainingIgnoreCase(String name, Pageable pageable);

    @Query(value = "{ '_id':  ?0 }", fields = "{ 'participantIds': 0 }")
    Optional<Room> findWithoutParticipantIds(String roomId);

    @Query(value = "{ '_id':  ?0 }", fields = "{ 'participantIds': 1 }")
    Optional<Room> findParticipantIdsOnly(String roomId);

    // 가장 최근에 생성된 방 조회 (Health Check용)
    @Query(value = "{}", sort = "{ 'createdAt': -1 }")
    Optional<Room> findMostRecentRoom();

    // Health Check용 단순 조회 (지연 시간 측정)
    @Query(value = "{}", fields = "{ '_id': 1 }")
    Optional<Room> findOneForHealthCheck();

    // MongoDB의 $addToSet 연산자를 사용한 원자적 업데이트
    @Query("{'_id': ?0}")
    @Update("{'$addToSet': {'participantIds': ?1}}")
    void addParticipant(String roomId, String userId);

    @Query("{'_id': ?0}")
    @Update("{'$pull': {'participantIds': ?1}}")
    void removeParticipant(String roomId, String userId);
}
