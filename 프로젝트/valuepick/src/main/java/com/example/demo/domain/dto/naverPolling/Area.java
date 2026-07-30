package com.example.demo.domain.dto.naverPolling;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.util.ArrayList;

@lombok.Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Area{
    public String name;
    public ArrayList<Data> datas;
}