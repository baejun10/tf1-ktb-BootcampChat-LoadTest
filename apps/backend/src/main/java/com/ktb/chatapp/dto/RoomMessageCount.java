package com.ktb.chatapp.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Field;

@Getter
@RequiredArgsConstructor
public class RoomMessageCount {

    @Field("_id")
    private String roomId;

    private long count;
}
