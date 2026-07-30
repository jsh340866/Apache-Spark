package com.example.demo.domain.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateNicknameRequest {

    @NotBlank(message = "닉네임은 필수 항목입니다.")
    private String nickname;
}
