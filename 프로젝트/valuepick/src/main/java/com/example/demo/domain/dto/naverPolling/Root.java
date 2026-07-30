package com.example.demo.domain.dto.naverPolling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Root{
    public String resultCode;
    public Result result;
}
