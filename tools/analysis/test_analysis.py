"""분석 도구 자체 점검 (M8 완료 기준). 먼저 `./gradlew :core:test`로 합성 세션과 오프라인 재생 로그를 만든다.

    python test_analysis.py
합성(잡음 0)에서 방향 오차·과대추정 ≈ 0, 잡음 주입 시 지표가 기대 방향으로 변하는지 확인한다.
"""
import tempfile
from pathlib import Path

import numpy as np
import pandas as pd

import json

from align import fit
from metrics import compute, load_truth, run_metrics
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
        pd.DataFrame({"tCaptureNs": [1, 2, 3], "mapNs": [10_000_000, 20_000_000, 30_000_000], "clusterNs": 1_000_000,
                      "trackNs": 0}).to_csv(d / "stage_timing.csv", index=False)
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
        assert m["stageMs"]["map"]["p50"] == 20.0 and m["stageMs"]["cluster"]["p95"] == 1.0, m["stageMs"]


def check_truth_v2():
    """정답 v2: 카메라 기준 거리 → 머리 원점(+0.39 m), kind 기본값 object (IMPROVE_SPEC §9.1, M11)."""
    cfg = {"head": {"offsetFromCameraM": [0.0, 0.5, -0.39]}}
    with tempfile.TemporaryDirectory() as d:
        ses = Path(d)
        (ses / "annotations").mkdir()
        (ses / "annotations/obstacles.json").write_text(json.dumps({
            "version": 2, "distanceFrom": "camera", "obstacles": [
                {"name": "box", "type": "FLOOR", "min": [-0.3, 0, 2.0], "max": [0.3, 0.4, 2.3]},
                {"name": "wall", "type": "FLOOR", "kind": "structure", "min": [0.7, 0, 0], "max": [0.75, 2.4, 6]}]}), encoding="utf-8")
        obs, est = load_truth(ses, cfg)
        assert abs(obs[0]["min"][2] - 2.39) < 1e-9 and abs(obs[0]["max"][2] - 2.69) < 1e-9 and obs[0]["min"][0] == -0.3, obs[0]
        assert obs[0]["kind"] == "object" and obs[1]["kind"] == "structure" and est is False, obs
        (ses / "annotations/obstacles.json").write_text(json.dumps({"obstacles": [
            {"name": "box", "type": "FLOOR", "min": [-0.3, 0, 2.0], "max": [0.3, 0.4, 2.3]}]}), encoding="utf-8")
        obs, _ = load_truth(ses, cfg)
        assert obs[0]["min"][2] == 2.0, obs  # v1(시작 표시 기준)은 그대로


def check_align_heading():
    """정지 녹화는 카메라 시선으로, 걷는 녹화는 궤적으로 정렬 (M12.0, Kotlin `Alignment`와 같은 계산)."""
    import numpy as np

    cfg = {"fitLengthM": 2.0, "minTravelM": 0.5, "headingWindowS": 3.0}
    off = [0.0, 0.5, -0.39]
    n = 120
    for yaw in (0.0, 30.0, -75.0, 170.0):
        a = np.radians(yaw) / 2
        i = np.arange(n)
        fr = pd.DataFrame({"tNs": 33_333_333 * i, "tracking": "TRACKING",
                           "tx": 1 + 0.02 * np.cos(i * 2.399), "ty": 0.1, "tz": -2 + 0.02 * np.sin(i * 2.399),
                           "qx": 0.0, "qy": np.sin(a), "qz": 0.0, "qw": np.cos(a)})
        al = fit(fr, cfg, off, -1.0)
        assert al["byHeading"] and abs(al["dir"][0] + np.sin(np.radians(yaw))) < 1e-6 and abs(al["dir"][1] + np.cos(np.radians(yaw))) < 1e-6, (yaw, al)
    walk = pd.DataFrame({"tNs": 33_333_333 * np.arange(101), "tracking": "TRACKING", "tx": 0.0, "ty": 0.1,
                         "tz": -3.0 * np.arange(101) / 100, "qx": 0.0, "qy": 0.0, "qz": 0.0, "qw": 1.0})
    assert not fit(walk, cfg, off, -1.0)["byHeading"]


def check_error_decomp():
    """M12.0 오차 분해(IMPROVE_SPEC §11.1): 알고 넣은 오차를 기대 방향·크기로 읽는지. `./gradlew :core:test`의 M12SessionsTest가 세션을 쓴다."""
    from error_decomp import analyze

    m12 = ROOT.parent / "m12"
    o = {n: analyze(m12 / n) for n in ("e01_clean", "e01_scale10", "e02_clean", "e02_jump", "e03_clean", "e03_drift1", "e03_drift2")}
    p50 = lambda s, name: o[s]["targets"][name]["all"]["p50"]  # noqa: E731

    # T1·T2: 잡음 없으면 모두 0, 깊이 +10%면 카메라에서 2.0 m 앞면 +0.2 m, 4.0 m 끝 벽 +0.4 m
    c = o["e01_clean"]
    assert all(abs(v["all"]["p50"]) < 0.01 for v in c["targets"].values()), c["targets"]
    assert c["ghost"]["p50"] < 0.01 and c["floor"]["spreadM"] < 0.01 and not c["jumps"], c
    assert c["targets"]["suitcase"]["convergence"]["convergedS"] == 0.0
    assert 0.18 < p50("e01_scale10", "suitcase") < 0.22 and 0.36 < p50("e01_scale10", "wall_end") < 0.44

    # T3: 한 방향 보행, 점프는 시각·크기로 검출하고 그 뒤 끝 벽 오차가 커진다
    assert [l["dir"] for l in o["e02_clean"]["legs"]] == [1] and abs(p50("e02_clean", "wall_end")) < 0.01
    j = o["e02_jump"]["jumps"]
    assert len(j) == 1 and abs(j[0]["tS"] - 4.5) < 0.05 and abs(j[0]["rotDeg"] - 5) < 0.1, j
    assert abs(o["e02_jump"]["walkCurve"]["wall_end"]["bins"][-1]["errM"]) > 0.1

    # T4: 왕복. 머리는 시작 자리로, 카메라는 앞뒤 오프셋(0.3 m)의 두 배만큼 다른 자리로 돌아온다
    e = o["e03_clean"]
    assert [l["dir"] for l in e["legs"]] == [1, -1] and abs(e["turnsDeg"][0] - 180) < 1, (e["legs"], e["turnsDeg"])
    lc = e["loopClosure"][0]
    assert lc["headM"] < 0.02 and abs(lc["cameraM"] - 0.6) < 0.02 and abs(lc["yawFrom180Deg"]) < 0.5, lc
    assert all(abs(g["dShiftM"]) < 0.01 and abs(g["dWidthM"]) < 0.01 for g in e["wallGap"]), e["wallGap"]
    # 누적 요 드리프트 1°/m·2°/m(6 m): 방향 닫힘 6°·12°, 갈 때·올 때 옆 벽 이동 차는 드리프트에 비례(폭은 그대로)
    for name, deg in (("e03_drift1", 6.0), ("e03_drift2", 12.0)):
        assert abs(o[name]["loopClosure"][0]["yawFrom180Deg"] - deg) < 0.5, o[name]["loopClosure"]
    dx = {n: abs(o[n]["wallGap"][0]["dShiftM"]) for n in ("e03_drift1", "e03_drift2")}
    assert dx["e03_drift1"] > 0.02 and 1.7 < dx["e03_drift2"] / dx["e03_drift1"] < 2.3, dx
    assert all(abs(o[n]["wallGap"][0]["dWidthM"]) < 0.02 for n in dx), [o[n]["wallGap"] for n in dx]


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
    check_truth_v2()
    check_align_heading()
    check_error_decomp()
    check_group_and_plan()
    print("analysis self-check ok")


if __name__ == "__main__":
    main()
