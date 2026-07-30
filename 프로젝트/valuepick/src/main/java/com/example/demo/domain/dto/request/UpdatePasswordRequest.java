package com.example.demo.domain.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdatePasswordRequest {

    @NotBlank(message = "현재 비밀번호는 필수 항목입니다.")
    private String currentPassword;

    @NotBlank(message = "새 비밀번호는 필수 항목입니다.")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "비밀번호는 영문+숫자 조합 8자 이상이어야 합니다.")
    private String newPassword;
}
