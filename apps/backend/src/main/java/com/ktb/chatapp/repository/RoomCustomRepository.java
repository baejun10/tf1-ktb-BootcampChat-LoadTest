package com.ktb.chatapp.repository;

import com.ktb.chatapp.dto.RoomWithUsers;

public interface RoomCustomRepository {

    RoomWithUsers findRoomWithUsersById(String roomId);
}
