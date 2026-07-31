# Apache-Spark

Apache Spark 학습용 리포. 가치투자 백테스팅 파이프라인(`valuepick-batch/`)을 학습 대상으로 삼아, 데이터를 수집해 기준값(PER/PBR/배당수익률 등)을 정하고 그 기준에 맞는 종목을 샀을 경우 실제로 이득이었는지를 과거 데이터로 검증한다.

- 파이프라인 실행 방법·진행 상태: [valuepick-batch/README.md](valuepick-batch/README.md)
- Spark 개념 학습 기록 (파티션 → 지연 실행 → 셔플 → 비결정성 → 조인/브로드캐스트 → 성능): [valuepick-batch/docs/spark-learning/](valuepick-batch/docs/spark-learning/)

기존 `ValuePick`(Spring Boot, MySQL 프로덕션 서비스)과는 완전히 분리된 리포이며, 그쪽 코드·DB는 건드리지 않는다.
