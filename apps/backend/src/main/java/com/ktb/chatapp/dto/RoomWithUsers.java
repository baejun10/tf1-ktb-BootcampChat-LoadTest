package com.ktb.chatapp.dto;

import com.ktb.chatapp.model.User;
import lombok.Getter;
import org.springframework.data.annotation.Id;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class RoomWithUsers {

    @Id
    private String id;

    private String name;
    private String creator;
    private boolean hasPassword;
    private String password;
    private LocalDateTime createdAt;

    // $lookup 으로 붙은 creator (단일)
    private User creatorUser;

    // $lookup 으로 붙은 participants (배열)
    private List<User> participants;
}
