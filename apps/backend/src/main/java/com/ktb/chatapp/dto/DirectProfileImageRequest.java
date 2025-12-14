package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DirectProfileImageRequest {

    @NotBlank(message = "이미지 URL을 입력해주세요.")
    private String imageUrl;
}
