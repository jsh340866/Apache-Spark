package com.example.demo.domain.dart;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class DartResponse {

    private String status;
    private String message;

    @JsonProperty("list")
    private List<DartItem> list;
}
