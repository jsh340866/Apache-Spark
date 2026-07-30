package com.example.demo.domain.dto;

import com.example.demo.domain.entity.InvestmentJournal;
import com.example.demo.domain.entity.User;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentJournalDto {

    private Long id;
    private Long userId;

    @NotBlank(message = "제목은 필수 항목입니다.")
    private String title;

    @NotBlank(message = "내용은 필수 항목입니다.")
    private String content;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public InvestmentJournal toEntity(User user) {
        return InvestmentJournal.builder()
                .user(user)
                .title(this.title)
                .createdAt(this.createdAt)
                .build();
    }

    public static InvestmentJournalDto from(InvestmentJournal entity) {
        return InvestmentJournalDto.builder()
                .id(entity.getId())
                .userId(entity.getUser().getId())
                .title(entity.getTitle())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
