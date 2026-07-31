# 실행 환경별 리소스 설정

이 프로젝트는 사양이 다른 두 컴퓨터에서 번갈아 작업한다.
호스트 메모리 차이가 커서 워커/executor 메모리를 환경마다 바꿔야 하는데,
**설정이 흩어져 있어 한쪽만 고치면 클러스터가 안 뜨거나 잡이 죽는다.**
컴퓨터를 옮길 때마다 이 문서의 표대로 세 곳을 함께 바꾼다.

## 기준이 되는 메모리는 Windows 총량이 아니라 Docker VM 가용량이다

Spark은 Windows에서 직접 도는 게 아니라 **Docker Desktop이 띄운 리눅스 VM 안에서** 돈다.
WSL2는 기본적으로 호스트 물리 메모리의 절반만 VM에 할당하므로,
`Get-CimInstance Win32_OperatingSystem`이 보여주는 Windows 총량을 기준으로 잡으면
실제 쓸 수 있는 양의 두 배를 가정하게 된다.

항상 이 명령으로 확인한 값을 기준으로 삼는다.

```bash
docker info --format "{{.MemTotal}} {{.NCPU}}"
```

## 환경 비교

| | 학원 컴퓨터 | 집 컴퓨터 |
|---|---|---|
| **Docker VM 가용 메모리** | **미측정** | **15.59GiB** (16,739,577,856 bytes) |
| Windows 물리 메모리 (참고용) | 7.7GB | 31.9GB |
| 논리 코어 (Docker NCPU) | 미측정 | 6 |
| 측정일 | PROGRESS.md 기록 | 2026-07-31 |

학원 컴퓨터의 7.7GB는 PROGRESS.md에 남은 값인데 **Windows 총량인지 Docker VM 값인지 불명확하다.**
Windows 총량이었다면 실제 Docker 가용량은 4GB 안팎이었을 수 있고,
그렇다면 2026-07-30~31의 04번 반복 실패가 훨씬 잘 설명된다.
학원 복귀 시 위 `docker info` 명령으로 먼저 실측해 이 표를 채울 것.

## 바꿔야 할 3곳

| # | 파일 | 항목 | 학원 | 집(VM 15.59GiB) |
|---|---|---|---|---|
| 1 | `docker/docker-compose.yml` | spark-worker-1 `--memory` | `2G` | `3G` |
| 2 | `docker/docker-compose.yml` | spark-worker-2 `--memory` | `2G` | `3G` |
| 3 | `conf/spark-defaults.conf` | `spark.executor.memory` | `1g` | `2g` |
| 4 | (spark-submit CLI 플래그) | `--driver-memory` | `3g` | `4g` |

집 환경 예약량 합계: 워커 3G×2 + 드라이버 4g + 나머지 컨테이너 약 1.2GB = **약 11.2GB / 15.59GiB**.

> 위 3곳 외에 클러스터 스펙이 **서술로만** 적힌 곳이 더 있다.
> 값을 바꾸면 함께 갱신할 것: `.claude/CLAUDE.md` 5절(매 세션 자동 로드되므로 가장 중요),
> `valuepick-batch/README.md` 아키텍처 다이어그램, `docs/spark-learning/02_파티션.md`.

`--cores 2`와 `spark.sql.shuffle.partitions 4`는 **양쪽 환경에서 동일하게 유지**한다.
집 컴퓨터가 6코어이긴 하지만 코어 수를 바꾸면 셔플 파티션 수도 같이 조정해야 하고,
그러면 학원 환경과 성능 비교가 불가능해진다.

### 왜 워커 메모리와 executor 메모리를 둘 다 바꿔야 하나

워커의 `--memory`는 **그 워커가 executor들에게 배분할 수 있는 총량**이지
executor 하나의 크기가 아니다. 이 프로젝트는 워커당 executor 1개가 뜨므로
executor가 워커 메모리 전체를 쓰지만, `spark.executor.memory`를 따로 지정하지 않으면
Spark 기본값 1GB가 적용된다 (2026-07-30 세션에서 이것 때문에 04번이 반복 실패했다.
`docker logs spark-worker-1`에서 `-Xmx1024M`, `finished with state KILLED` `exitStatus 143` 관측).

executor 메모리를 워커 메모리보다 작게 잡는 이유는 JVM 자체 오버헤드
(힙 외 메모리, GC 구조체 등) 여유를 남기기 위해서다.

### 드라이버 메모리는 CLI 플래그로만 먹는다

`spark-defaults.conf`에 `spark.driver.memory`를 써도 클라이언트 모드에서는 반영되지 않는다.
반드시 `spark-submit --driver-memory 4g` 형태로 준다. (CLAUDE.md 실행 규칙에도 기록됨)

## 되돌리는 절차

```bash
# 1. docker-compose.yml에서 worker-1, worker-2의 --memory 값 변경
# 2. conf/spark-defaults.conf의 spark.executor.memory 값 변경
# 3. 컨테이너 재생성 (restart로는 command 변경이 반영되지 않는다)
cd valuepick-batch/docker
docker compose down
docker compose up -d

# 4. 실제 반영 확인 — conf 파일만 보고 믿지 말 것
docker exec spark-worker-1 ps aux | grep Xmx
```

4번을 꼭 한다. 2026-07-31 세션에서 `generate_strategies.py`가 "저장 완료"를 출력했는데
실제로는 엉뚱한 경로에 쓰고 있어서 옛 설정으로 두 번이나 잡을 돌린 사고가 있었다.
**설정 파일 내용이 아니라 실행 중인 프로세스의 실제 값을 확인한다.**

## 미해결 검증 항목

2026-07-30~31 세션에서 04번(`04_backtest_grid.py`)이 세 번 연속 실패했는데,
executor 자체의 힙 부족인지 호스트 7.7GB의 메모리 압박인지 **분리 검증을 못 했다**
(실행 중 Docker Desktop이 응답 불가에 빠져 판단이 흐려짐).

집 컴퓨터에서는 호스트 메모리 여유가 충분하므로 이 변수가 제거된다.
여기서 04번이 성공하면 "호스트 압박이 원인이었다"는 쪽에 무게가 실리고,
여기서도 실패하면 04번 코드 자체(crossJoin 규모, 캐시 전략)의 문제로 좁혀진다.
학원 복귀 후 같은 잡을 2G/1536m으로 돌려보면 이 가설을 양쪽에서 확인할 수 있다.
