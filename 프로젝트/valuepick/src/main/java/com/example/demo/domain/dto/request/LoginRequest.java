package com.example.demo.domain.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "이메일은 필수 항목입니다.")
    @Email(message = "example@example.com 형식으로 입력하세요.")
    private String email;

    @NotBlank(message = "비밀번호는 필수 항목입니다.")
    private String password;
}
