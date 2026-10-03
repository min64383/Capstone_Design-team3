"""분석 도구 자체 점검 (M8 완료 기준). 먼저 `./gradlew :core:test`로 합성 세션과 오프라인 재생 로그를 만든다.

    python test_analysis.py
합성(잡음 0)에서 방향 오차·과대추정 ≈ 0, 잡음 주입 시 지표가 기대 방향으로 변하는지 확인한다.
"""
import tempfile
from pathlib import Path

import pandas as pd

from metrics import compute, run_metrics
from report import combine
from sweep import plan

ROOT = Path(__file__).resolve().parents[2] / "core/build/test-output/replay"


def run(name):
    return compute(ROOT / name / "session", ROOT / name / "run")


def check_run_only():
    """세션 없는 실행 로그(사용자 모드 T01): 2분, 둘째 분만 UNKNOWN 절반·발열 2 (M10)."""
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        s = 1_000_000_000
        t = [i * s // 10 for i in range(1200)]  # 0.1 s 간격 120 s
        state = ["UNKNOWN" if i >= 600 and i % 2 else "NORMAL" for i in range(1200)]
        pd.DataFrame({"tBlockNs": t, "poseTNs": t, "snapshotTNs": [x - 200_000_000 for x in t], "state": state,
                      "obstacleId": 1, "azimuthDeg": 0.0, "distanceM": 2.0, "band": "WARN", "sound": "X",
                      "infoAgeMs": 200.0, "headingDeg": 0.0}).to_csv(d / "guidance.csv", index=False)
        ts = list(range(0, 120 * s, s // 20))  # 느린 경로 20 Hz, 계산 30 ms
        pd.DataFrame({"tCaptureNs": [x - 150_000_000 for x in ts], "tStartNs": ts, "tDoneNs": [x + 30_000_000 for x in ts],
                      "nPoints": 3600, "nVoxels": 500, "nObstacles": 1, "floorY": -1.0, "mapHealth": "OK"}).to_csv(d / "slow_path.csv", index=False)
        pd.DataFrame({"tNs": [0, 30 * s, 61 * s, 100 * s], "thermalStatus": [0, 1, 2, 2], "batteryPct": 70,
                      "audioOutputLatencyMs": 50.0}).to_csv(d / "device.csv", index=False)
        cfg = {"repPoint": {"strategy": "CORRIDOR_NEAREST"}}
        m = run_metrics(d, cfg)
        assert m["thermalMax"] == 2 and abs(m["snapshotAgeMs"]["p50"] - 200) < 1e-6, m
        pm = m["perMinute"]
        assert [r["minute"] for r in pm] == [0, 1], pm
        assert pm[0]["unknownFraction"] == 0 and abs(pm[1]["unknownFraction"] - 0.5) < 0.01, pm
        assert pm[0]["thermalMax"] == 1 and pm[1]["thermalMax"] == 2, pm
        assert abs(pm[0]["slowPathHz"] - 20) < 0.5 and abs(pm[0]["slowComputeP50Ms"] - 30) < 1e-6, pm
        assert abs(pm[0]["captureToDoneP50Ms"] - 180) < 1e-6, pm
        assert m["warnFraction"] == 1.0, m


def check_group_and_plan():
    """회차 묶기(중앙값·합계·딕셔너리)와 sweep 조합 (M10)."""
    assert combine([1.0, None, 3.0, 2.0], "median") == 2.0
    assert combine([1, 0, 2], "sum") == 3
    assert combine([{"a": 1.0}, {"a": 3.0, "b": None}], "median") == {"a": 2.0, "b": None}
    assert combine([None, None], "median") is None
    v = [{"name": "default", "overrides": {}}, {"name": "rep_NEAREST", "overrides": {"repPoint": {"strategy": "NEAREST"}}}]
    jobs = plan(v, [Path("s/A_S02"), Path("s/B_S03")], Path("out"))
    assert len(jobs) == 4 and jobs[1][2] == Path("out/default/B_S03") and jobs[2][0]["name"] == "rep_NEAREST", jobs
    try:
        plan(v + [v[0]], [Path("s/A")], Path("out"))
        raise AssertionError("duplicate variant names must fail")
    except ValueError:
        pass


def main():
    clean = run("clean")
    assert clean["directionErrorDeg"]["p95"] < 2.0, clean["directionErrorDeg"]
    assert clean["overestimate1p5to2p5"]["p95"] < 0.03, clean["overestimate1p5to2p5"]
    assert clean["missedStop"] == 0 and clean["falseAlarmFraction"] == 0
    assert abs(clean["warnTimingErrorS"]["box"]) < 0.2, clean["warnTimingErrorS"]
    assert 2.3 < clean["firstWarnDistanceM"]["box"] <= 2.5, clean["firstWarnDistanceM"]  # 경고 구간 2.5 m
    assert 0.85 < clean["firstStopDistanceM"]["box"] <= 1.0, clean["firstStopDistanceM"]  # 정지 구간 1.0 m

    scale = run("scale_plus10")  # 깊이 +10% → 멀게 추정(머리 기준이라 10%보다 조금 작다)
    assert 0.06 < scale["overestimate1p5to2p5"]["p95"] < 0.15, scale["overestimate1p5to2p5"]

    noisy = run("pose_noise")  # 자세 잡음 → 음원 흔들림 증가
    assert noisy["sourceJitterDegStd"] > 10 * clean["sourceJitterDegStd"], (noisy["sourceJitterDegStd"], clean["sourceJitterDegStd"])

    grip = run("grip_right")  # 폰이 몸 옆: 머리 기준 방향이 맞아야 한다
    assert grip["directionErrorDeg"]["p95"] < 3.0, grip["directionErrorDeg"]

    check_run_only()
    check_group_and_plan()
    print("analysis self-check ok")


if __name__ == "__main__":
    main()
