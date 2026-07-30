package com.example.demo.domain.dart;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DartItem {

    @JsonProperty("account_nm")
    private String accountNm;

    // IFRS/DART 표준 계정 코드 - 회사마다 계정명(account_nm) 표기가 달라도(예: "매출액"/"영업수익"/"수익(매출액)") 항상 동일
    @JsonProperty("account_id")
    private String accountId;

    @JsonProperty("thstrm_amount")
    private String amount;

    @JsonProperty("frmtrm_amount")
    private String frmtrm_amount;

    @JsonProperty("bfefrmtrm_amount")
    private String bfefrmtrm_amount;

    @JsonProperty("se")
    private String se;          // 구분 (현금배당수익률(%) 등)

    @JsonProperty("stock_knd")
    private String stockKnd;    // 주식 종류 (보통주/우선주)

    @JsonProperty("thstrm")
    private String thstrm;      // 당기 값

    @JsonProperty("stlm_dt")
    private String stlmDt;      // 결산일

    @JsonProperty("fs_div")
    private String fsDiv;       // CFS OFS

    @JsonProperty("currency")
    private String currency; // 통화 단위 (KRW, USD 등)
}
