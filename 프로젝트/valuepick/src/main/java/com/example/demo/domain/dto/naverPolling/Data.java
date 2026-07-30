package com.example.demo.domain.dto.naverPolling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@lombok.Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Data{
    public String cd;
    public String nm;
    public int sv;
    public int nv;
    public int cv;
    public double cr;
    public String rf;
    public String mt;
    public String ms;
    public String tyn;
    public int pcv;
    public int ov;
    public int hv;
    public int lv;
    public int ul;
    public int ll;
    public int aq;
    public double aa;
    public Object nav;
    public int keps;
    public int eps;
    public double bps;
    public Object cnsEps;
    public double dv;
    public Long countOfListedStock;
    public Object nxtOverMarketPriceInfo;
}
