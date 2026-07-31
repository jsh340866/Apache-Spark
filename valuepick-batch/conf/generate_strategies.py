"""conf/strategies.yaml 생성 스크립트 (PROJECT_INSTRUCTIONS.md 4.4)

기존 PER/PBR/배당수익률 문턱값 그리드(conf/generate_strategies_threshold.py로 백업 보존)를
ValuePick Top100Service.scoreAll()과 동일한 "7팩터 백분위 가중합산 점수" 방식으로 대체한다.
전략 그리드는 리밸런싱주기(2) x portfolio_size(5) x 가중치프리셋(3^7=2,187) = 21,870개 조합으로,
가중치 프리셋 자체를 팩터별 후보값의 곱 조합으로 구성해 "전략을 곱하면 연산량이 폭증한다"는
이 리포의 학습 목표를 점수 방식에서도 유지한다(docs/PLAN_가치주점수_전환_20260731.md 참고).
"""

from __future__ import annotations

import itertools
from pathlib import Path

import yaml

# 2026-07-31 실측: docker exec의 기본 작업 디렉토리(/opt/spark/work-dir)에서 실행하면 상대경로
# "strategies.yaml"이 스크립트 옆이 아니라 그 작업 디렉토리에 저장되는 사고가 있었다.
# 스크립트 파일 위치 기준 절대경로로 고정해 실행 위치와 무관하게 항상 같은 곳에 쓰도록 한다.
STRATEGIES_PATH = Path(__file__).resolve().parent / "strategies.yaml"

REBALANCE = ["monthly", "quarterly"]
PORTFOLIO_SIZE = [3, 10, 30, 50, 100]

# 팩터별 가중치 후보값(저/원본/고) - Top100Service.scoreAll()의 원본 가중치(퍼센트 단위) 기준
# +-10%p, 단 EPS성장률은 원본이 5%로 작아 +-10%p면 음수가 나오므로 +-2%p만 적용.
WEIGHT_CANDIDATES = {
    "weight_per": [15, 25, 35],
    "weight_pbr": [5, 15, 25],
    "weight_roe": [10, 20, 30],
    "weight_roa": [0, 10, 20],
    "weight_debt_ratio": [5, 15, 25],
    "weight_eps_growth": [3, 5, 7],
    "weight_momentum": [0, 10, 20],
}
WEIGHT_KEYS = list(WEIGHT_CANDIDATES.keys())


def normalize_weights(raw: dict[str, float]) -> dict[str, float]:
    """7개 가중치 합이 정확히 100이 되도록 비율을 유지한 채 재조정한다.
    합이 이미 100인 조합도 동일하게 정규화를 거치므로 별도 예외 처리가 필요 없다."""
    total = sum(raw.values())
    return {k: v / total * 100 for k, v in raw.items()}


def weight_preset_label(idx: int) -> str:
    """21,870개 조합에서 가중치 7개 값을 그대로 이름에 이어붙이면 너무 길어져 가독성보다
    유일성/일관성을 우선해 프리셋 인덱스로 식별한다."""
    return f"w{idx:04d}"


def build_weight_presets() -> list[dict]:
    presets = []
    for idx, combo in enumerate(itertools.product(*(WEIGHT_CANDIDATES[k] for k in WEIGHT_KEYS))):
        raw = dict(zip(WEIGHT_KEYS, combo))
        normalized = normalize_weights(raw)
        presets.append({"label": weight_preset_label(idx), **normalized})
    return presets


def strategy_name(rebalance, portfolio_size, weight_label) -> str:
    return f"{weight_label}_{rebalance}_n{portfolio_size}"


def build_strategies() -> list[dict]:
    weight_presets = build_weight_presets()
    strategies = []
    for rebalance, portfolio_size, preset in itertools.product(REBALANCE, PORTFOLIO_SIZE, weight_presets):
        weights = {k: v for k, v in preset.items() if k != "label"}
        strategies.append({
            "name": strategy_name(rebalance, portfolio_size, preset["label"]),
            "rebalance": rebalance,
            "portfolio_size": portfolio_size,
            **weights,
        })
    return strategies


def main():
    strategies = build_strategies()
    print(f"생성된 전략 조합 수: {len(strategies)}")

    with open(STRATEGIES_PATH, "w", encoding="utf-8") as f:
        yaml.dump({"strategies": strategies}, f, allow_unicode=True, sort_keys=False)
    print(f"저장 완료: {STRATEGIES_PATH}")


if __name__ == "__main__":
    main()
