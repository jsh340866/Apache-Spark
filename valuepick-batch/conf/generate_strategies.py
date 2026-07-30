"""conf/strategies.yaml 생성 스크립트 (PROJECT_INSTRUCTIONS.md 4.4)

전략 파라미터 그리드(PER상한 x PBR상한 x 배당수익률하한 x 리밸런싱주기 x 보유종목수)를
itertools.product로 전체 조합 생성해 strategies.yaml로 저장한다.
파라미터 범위를 바꾸고 싶으면 아래 후보값만 수정하고 다시 실행하면 된다.
"""

from __future__ import annotations

import itertools

import yaml

PER_MAX = [8, 10, 12, 15, 20]
PBR_MAX = [0.8, 1.0, 1.2, 1.5, 2.0]
DIVIDEND_YIELD_MIN = [None, 1.0, 2.0, 3.0]
REBALANCE = ["monthly", "quarterly"]
PORTFOLIO_SIZE = [10, 20, 30, 50, 100]


def strategy_name(per_max, pbr_max, dividend_yield_min, rebalance, portfolio_size) -> str:
    dy_label = "none" if dividend_yield_min is None else str(dividend_yield_min)
    return f"per{per_max}_pbr{pbr_max}_dy{dy_label}_{rebalance}_n{portfolio_size}"


def build_strategies() -> list[dict]:
    strategies = []
    for per_max, pbr_max, dividend_yield_min, rebalance, portfolio_size in itertools.product(
        PER_MAX, PBR_MAX, DIVIDEND_YIELD_MIN, REBALANCE, PORTFOLIO_SIZE
    ):
        strategies.append({
            "name": strategy_name(per_max, pbr_max, dividend_yield_min, rebalance, portfolio_size),
            "per_max": per_max,
            "pbr_max": pbr_max,
            "dividend_yield_min": dividend_yield_min,
            "rebalance": rebalance,
            "portfolio_size": portfolio_size,
        })
    return strategies


def main():
    strategies = build_strategies()
    print(f"생성된 전략 조합 수: {len(strategies)}")

    with open("strategies.yaml", "w", encoding="utf-8") as f:
        yaml.dump({"strategies": strategies}, f, allow_unicode=True, sort_keys=False)
    print("conf/strategies.yaml 저장 완료")


if __name__ == "__main__":
    main()

