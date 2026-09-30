"""분석 도구 자체 점검 (M8 완료 기준). 먼저 `./gradlew :core:test`로 합성 세션과 오프라인 재생 로그를 만든다.

    python test_analysis.py
합성(잡음 0)에서 방향 오차·과대추정 ≈ 0, 잡음 주입 시 지표가 기대 방향으로 변하는지 확인한다.
"""
from pathlib import Path

from metrics import compute

ROOT = Path(__file__).resolve().parents[2] / "core/build/test-output/replay"


def run(name):
    return compute(ROOT / name / "session", ROOT / name / "run")


def main():
    clean = run("clean")
    assert clean["directionErrorDeg"]["p95"] < 2.0, clean["directionErrorDeg"]
    assert clean["overestimate1p5to2p5"]["p95"] < 0.03, clean["overestimate1p5to2p5"]
    assert clean["missedStop"] == 0 and clean["falseAlarmFraction"] == 0
    assert abs(clean["warnTimingErrorS"]["box"]) < 0.2, clean["warnTimingErrorS"]

    scale = run("scale_plus10")  # 깊이 +10% → 멀게 추정(머리 기준이라 10%보다 조금 작다)
    assert 0.06 < scale["overestimate1p5to2p5"]["p95"] < 0.15, scale["overestimate1p5to2p5"]

    noisy = run("pose_noise")  # 자세 잡음 → 음원 흔들림 증가
    assert noisy["sourceJitterDegStd"] > 10 * clean["sourceJitterDegStd"], (noisy["sourceJitterDegStd"], clean["sourceJitterDegStd"])

    grip = run("grip_right")  # 폰이 몸 옆: 머리 기준 방향이 맞아야 한다
    assert grip["directionErrorDeg"]["p95"] < 3.0, grip["directionErrorDeg"]
    print("analysis self-check ok")


if __name__ == "__main__":
    main()
