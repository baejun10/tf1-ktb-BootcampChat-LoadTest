package com.ktb.chatapp.repository.room;

import com.ktb.chatapp.dto.RoomWithUsers;
import com.ktb.chatapp.repository.RoomCustomRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.List;

@RequiredArgsConstructor
public class RoomCustomRepositoryImpl implements RoomCustomRepository {

    private final MongoTemplate mongoTemplate;

    @Override
    public RoomWithUsers findRoomWithUsersById(String roomId) {
        AggregationOperation addObjectIdFields = context -> new Document("$addFields",
                new Document("participantObjectIds",
                        new Document("$map",
                                new Document("input", "$participantIds")
                                        .append("as", "pid")
                                        .append("in", new Document("$toObjectId", "$$pid")))
                ).append("createObjectId",
                        new Document("cond", List.of(
                                new Document("$cond", List.of(
                                        new Document("$ifNull", List.of("$creator", false)),
                                        new Document("$toObjectId", "$creator"),
                                        new Document()
                                ))
                        ))));

        AggregationOperation lookupParticipants = context -> new Document("$lookup",
                new Document("from", "users")
                        .append("let", new Document("pids", "$participantObjectIds"))
                        .append("pipeline", List.of(
                                new Document("$match",
                                        new Document("$expr",
                                                new Document("$in", List.of("$_id", "$$pids"))
                                        )
                                )
                        ))
                        .append("as", "participants")
        );

        AggregationOperation lookupCreator = context -> new Document("$lookup",
                new Document("from", "users")
                        .append("let", new Document("cid", "$creatorObjectId"))
                        .append("pipeline", List.of(
                                new Document("$match",
                                        new Document("$expr",
                                                new Document("$eq", List.of("$_id", "$$cid"))
                                        )
                                )
                        ))
                        .append("as", "creatorUser")
        );

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("_id").is(new ObjectId(roomId))),
                addObjectIdFields,
                lookupParticipants,
                lookupCreator,
                Aggregation.unwind("creatorUser", true)
        );

        return mongoTemplate.aggregate(aggregation, "rooms", RoomWithUsers.class)
                .getUniqueMappedResult();
    }
}

