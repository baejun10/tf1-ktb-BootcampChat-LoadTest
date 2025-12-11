package com.ktb.chatapp.repository.room;

import com.ktb.chatapp.dto.RoomWithUsers;
import com.ktb.chatapp.repository.RoomCustomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.LookupOperation;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.aggregation.UnwindOperation;
import org.springframework.data.mongodb.core.query.Criteria;

@RequiredArgsConstructor
public class RoomCustomRepositoryImpl implements RoomCustomRepository {

    private final MongoTemplate mongoTemplate;

    @Override
    public RoomWithUsers findRoomWithUsersById(String roomId) {
        // 1. 해당 room만 매칭
        MatchOperation match = Aggregation.match(Criteria.where("_id").is(roomId));

        // 2. participants 조인
        LookupOperation lookupParticipants = LookupOperation.newLookup()
                .from("users")
                .localField("participantIds")
                .foreignField("_id")
                .as("participants");

        // 3. creator 조인
        LookupOperation lookupCreator = LookupOperation.newLookup()
                .from("users")
                .localField("creator")
                .foreignField("_id")
                .as("creatorUser");

        // 4. creatorUser 는 배열이므로 단일 객체로 풀어줌
        UnwindOperation unwindCreator = Aggregation.unwind("creatorUser", true);

        // 필요시 projection으로 Room 필드/유저 필드 줄일 수 있음
        Aggregation aggregation = Aggregation.newAggregation(
                match,
                lookupParticipants,
                lookupCreator,
                unwindCreator
        );

        return mongoTemplate.aggregate(aggregation, "rooms", RoomWithUsers.class)
                .getUniqueMappedResult();
    }
}

