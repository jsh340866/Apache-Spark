# Apache-Spark

Apache Spark 기반 **가치투자 백테스팅 파이프라인**. [ValuePick](https://github.com/project-valuepick/valuepick) 서비스가 운영 중인 종목 추천 로직이 실제로 수익을 냈는지, 2021~2023년 실제 데이터로 **21,870개 전략을 동시에 백테스트**해 검증했습니다.

주요 결과 — 시장 범위(KOSPI vs 전체)를 바꾸는 것만으로 87%의 전략에서 성과가 더 좋아졌고, 최고 성과 전략을 추적해보니 로직이 저평가 우량주를 찾아낸 게 아니라 **일회성 회계 착시를 우량 지표로 오독한 결과**가 우연히 주가 급등과 겹친 사례였습니다.

## → [valuepick-batch/](valuepick-batch/README.md)

설계 · 트러블슈팅 10건 · 분석 결과 — **메인 문서입니다.**

| | |
|---|---|
| [아키텍처 · 검증 · 성능 문서](valuepick-batch/docs/) | ARCHITECTURE / VALIDATION / PERFORMANCE |
| [Spark 학습 기록](valuepick-batch/docs/spark-learning/) | 파티션 → 지연 실행 → 셔플 → 비결정성 → 조인 → 성능 |
| [ValuePick 본 서비스](https://github.com/project-valuepick/valuepick) | 검증 대상 (Spring Boot · MySQL, 별도 리포) |

---

검증 대상인 ValuePick(Spring Boot, MySQL 프로덕션 서비스)은 [별도 리포](https://github.com/project-valuepick/valuepick)에서 관리하며, 이 리포는 그 코드·DB를 건드리지 않습니다.
