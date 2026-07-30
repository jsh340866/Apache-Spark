package com.example.demo.domain.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "이메일은 필수 항목입니다.")
    @Email(message = "example@example.com 형식으로 입력하세요.")
    private String email;

    @NotBlank(message = "비밀번호는 필수 항목입니다.")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "비밀번호는 영문+숫자 조합 8자 이상이어야 합니다.")
    private String password;

    @NotBlank(message = "닉네임은 필수 항목입니다.")
    private String nickname;
}
