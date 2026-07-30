package com.example.demo.domain.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JournalPageResult {

    private List<JournalListItemDto> content;
    private int page;
    private int totalPages;
    private long totalElements;
}
