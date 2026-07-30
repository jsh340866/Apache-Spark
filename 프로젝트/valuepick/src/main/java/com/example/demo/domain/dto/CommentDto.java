package com.example.demo.domain.dto;

import com.example.demo.domain.entity.Comment;
import com.example.demo.domain.entity.InvestmentJournal;
import com.example.demo.domain.entity.User;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommentDto {

    private Long id;
    private Long journalId;
    private Long userId;

    @NotBlank(message = "댓글 내용은 필수 항목입니다.")
    private String content;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Comment toEntity(InvestmentJournal journal, User user) {
        return Comment.builder()
                .journal(journal)
                .user(user)
                .content(this.content)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    public static CommentDto from(Comment entity) {
        return CommentDto.builder()
                .id(entity.getId())
                .journalId(entity.getJournal().getId())
                .userId(entity.getUser().getId())
                .content(entity.getContent())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
