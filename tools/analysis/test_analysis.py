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
from front_diag import block_causes, classify
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


def check_merged_fraction():
    """합쳐짐 비율(M13.1): 정답 물체와 겹치는 추정 물체가 물체 뒤 0.5 m 넘게 이어진 단계만 센다."""
    from metrics import merged_fraction

    al = {"origin": [0.0, 0.0], "dir": [0.0, 1.0], "floorY": 0.0}  # 월드 +Z = 정답 +z, 정답 x = 월드 −x
    truth = [{"name": "box", "kind": "object", "min": [-0.2, 0.0, 2.0], "max": [0.2, 0.6, 2.3]},
             {"name": "wall", "kind": "structure", "min": [-1.0, 0.0, 4.0], "max": [1.0, 2.4, 4.1]}]
    obs = pd.DataFrame({"tCaptureNs": [1, 2, 3, 3], "aabbMinX": -0.2, "aabbMaxX": 0.2, "aabbMinZ": [2.0, 2.0, 2.0, 3.9],
                        "aabbMaxZ": [2.3, 4.1, 2.5, 4.1]})  # 1: 상자만, 2: 벽까지 이어짐, 3: 상자 + 따로 잡힌 벽
    assert abs(merged_fraction(obs, truth, al) - 1 / 3) < 1e-9, merged_fraction(obs, truth, al)
    assert merged_fraction(obs.iloc[:0], truth, al) is None


def check_depth_error():
    """깊이 오차 모델(M13.5): 바닥 띠는 보행선 안·끝 벽 앞·물체 자리 밖만, 강건 통계는 중앙값·IQR/1.349·꼬리 비율."""
    import numpy as np
    from depth_error import TAIL_M, floor_mask, robust

    truth = [{"name": "box", "kind": "object", "min": [-0.2, 0.0, 2.0], "max": [0.2, 0.6, 2.3]}]
    x = np.array([0.0, 0.0, 0.5, 0.0, 0.0])
    z = np.array([1.0, 2.1, 1.0, 3.9, 3.0])  # 바닥, 물체 자리, 보행선 밖, 끝 벽 바로 앞, 바닥
    assert floor_mask(x, z, truth, 4.0).tolist() == [True, False, False, False, True]
    med, sig, tail = robust(np.array([0.0] * 8 + [TAIL_M * 3] * 2))
    assert med == 0.0 and sig == 0.0 and abs(tail - 0.2) < 1e-9


def check_object_shape():
    """형상 지표(M13): 겹침이 가장 큰 추정 상자의 앞면 오차·앞뒤 길이·폭 중앙값."""
    from metrics import object_shape

    al = {"origin": [0.0, 0.0], "dir": [0.0, 1.0], "floorY": 0.0}  # 월드 +Z = 정답 +z, 정답 x = 월드 −x
    truth = [{"name": "box", "kind": "object", "min": [-0.2, 0.0, 2.0], "max": [0.2, 0.6, 2.3]}]
    # 단계 1: 앞면 0.1 m 앞, 길이 0.5, 폭 0.4 / 단계 2: 큰 상자 + 작은 조각(겹침이 큰 쪽만)
    obs = pd.DataFrame({"tCaptureNs": [1, 2, 2], "aabbMinX": [-0.2, -0.25, 0.1], "aabbMaxX": [0.2, 0.25, 0.15],
                        "aabbMinZ": [1.9, 2.0, 2.2], "aabbMaxZ": [2.4, 2.6, 2.25]})
    s = object_shape(obs, truth, al)["box"]
    assert abs(s["frontErrorM"] - (-0.05)) < 1e-9 and abs(s["depthM"] - 0.55) < 1e-9 and abs(s["widthM"] - 0.45) < 1e-9, s
    assert s["n"] == 2 and object_shape(obs.iloc[:0], truth, al) is None


def check_id_switches():
    """id 전환(§9.2): 정답 물체마다 짝지어진 경고의 서로 다른 추적 id 수 − 1, 구조물·SILENT·짝 없는 경고는 세지 않는다."""
    from metrics import id_switches

    rows = [{"band": "WARN", "match": 0, "oid": 1}, {"band": "STOP", "match": 0, "oid": 2}, {"band": "WARN", "match": 0, "oid": 2},
            {"band": "SILENT", "match": 0, "oid": 3}, {"band": "WARN", "match": 1, "oid": 4}, {"band": "WARN", "match": None, "oid": 5},
            {"band": "WARN", "match": 2, "oid": 6}]
    assert id_switches(rows, {0, 2}) == 1  # 물체 0: id 1→2, 물체 2: 하나, 1은 구조물
    assert id_switches([], {0}) is None


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


CORRIDOR = {"corridor": {"widthM": 0.8, "heightM": 2.0, "lengthM": 3.5, "behindM": 0.2}}


def check_round_trip_corridor():
    """T1 왕복 통로(M12.2): +z로 갈 때는 끝 벽만, −z로 돌아올 때는 출발 뒤 상자만 통로 안. 돌아올 때 x +0.2 상자는 사용자 왼쪽(방위 −)."""
    from metrics import truth_nearest

    wall = {"min": [-0.62, 0.0, 4.0], "max": [0.62, 2.4, 4.1]}
    box = {"min": [0.1, 0.0, -1.2], "max": [0.3, 0.5, -1.0]}
    a = truth_nearest(wall, 0.0, 1.0, CORRIDOR, 1.0)
    assert a is not None and abs(a[0] - 3.0) < 1e-9 and abs(a[2]) < 1e-9, a
    assert truth_nearest(box, 0.0, 1.0, CORRIDOR, 1.0) is None
    assert truth_nearest(wall, 0.0, 1.0, CORRIDOR, -1.0) is None
    b = truth_nearest(box, 0.0, 1.0, CORRIDOR, -1.0)
    assert b is not None and abs(b[0] - 2.0) < 1e-9 and b[2] < 0 and abs(b[2] - np.degrees(np.arctan2(-0.1, 2.0))) < 1e-9, b
    assert truth_nearest(box, 0.0, 1.0, CORRIDOR) == truth_nearest(box, 0.0, 1.0, CORRIDOR, 1.0)  # 기본값 +z(기존 호출)


def check_walk_sign():
    """T2 방향 유지(M12.2): 1 s에 0.15 m 미만이면 직전 부호, 뒤 블록을 바꿔도 앞 블록 부호는 같다(과거만 사용)."""
    from metrics import walk_sign

    t = np.arange(0, 14, 0.1)
    hz = np.where(t < 6, 0.5 * t, np.where(t < 8, 3.0 - 0.1 * (t - 6), 2.8 - 0.5 * (t - 8)))  # 갈 때 0.5 m/s, 돌아섬 0.1 m/s, 올 때
    s = walk_sign(t, hz, 1.0, 0.15)
    assert (s[t < 8] == 1).all() and s[-1] == -1, s
    flip = t[np.nonzero(s < 0)[0][0]]
    assert 8.0 < flip < 9.0, flip
    hz2 = hz.copy()
    hz2[t > 10] += 5.0  # 미래를 바꿈
    k = int(np.searchsorted(t, 10.0, side="right"))
    assert (walk_sign(t, hz2, 1.0, 0.15)[:k] == s[:k]).all()


def check_zone_stats():
    """T3 거리 오차·T4 통로 안팎(M12.2): 구간별 부호 있는 오차, 경고 놓침, 통로 밖 경고, 출발 거리·구간 시간."""
    from metrics import zone_stats

    pol = {"stopM": 1.0, "warnMaxM": 2.5, "silentMaxM": 3.0}
    truth = [{"name": "box", "kind": "object"}, {"name": "wall", "kind": "structure"}]
    row = lambda t, tn, band, match, dist=None: {"t": t, "state": "NORMAL", "tn": tn, "band": band, "match": match, "dist": dist}  # noqa: E731
    # T3: 정답 0.8·1.2·2.0 m, 추정은 0.10 m 멀게
    rows = [row(i * 0.1, [(d, d, 0.0), None], "WARN" if d >= 1 else "STOP", 0, d + 0.1) for i, d in enumerate((2.0, 1.2, 0.8))]
    de = zone_stats(rows, truth, pol)["distanceErrorM"]
    assert list(de) == ["0-1", "1-1.5", "1.5-"] and all(abs(v["p50"] - 0.1) < 1e-9 and v["n"] == 1 for v in de.values()), de
    # T4: 통로 안 상자 경고 6, 옆 벽(통로 밖)과 짝지은 경고 4, 정답은 WARN인데 경고 없음 2
    rows = ([row(i * 0.1, [(2.0, 2.0, 0.0), None], "WARN", 0, 2.0) for i in range(6)] +
            [row(0.6 + i * 0.1, [None, None], "WARN", 1, 0.5) for i in range(4)] +
            [row(1.0 + i * 0.1, [(1.8, 1.8, 0.0), None], None, None) for i in range(2)])
    z = zone_stats(rows, truth, pol)
    assert abs(z["outsideCorridorFraction"] - 0.4) < 1e-9 and abs(z["missedWarnFraction"] - 0.25) < 1e-9, z
    assert z["startDistanceM"] == {"box": 2.0} and abs(z["warnZoneS"]["box"] - 0.8) < 1e-9 and z["stopZoneS"] == {}, z
    assert zone_stats([], truth, pol)["missedWarnFraction"] is None


def check_miss_reasons():
    """S5 놓침 사유(M12.3): 바닥 없음 3블록, 군집 없음 2블록, SILENT 1블록, 군집 전부 거름·추적 미확인·구간 밖 각 1블록."""
    import tempfile
    from metrics import miss_reason, zone_stats

    pol = {"stopM": 1.0, "warnMaxM": 2.5, "silentMaxM": 3.0}
    truth = [{"name": "wall", "kind": "structure"}]
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        # 스냅샷 시각: 1 바닥 없음, 2 군집 없음, 3 군집 전부 거름, 4 추적 미확인, 5 장애물 있음(명령 없음 = 구간 밖)
        pd.DataFrame({"tCaptureNs": [1, 2, 3, 4, 5], "floorY": [None, -1.0, -1.0, -1.0, -1.0]}).to_csv(d / "slow_path.csv", index=False)
        pd.DataFrame({"tCaptureNs": [3, 4, 4], "filtered": [True, True, False]}).to_csv(d / "cluster_debug.csv", index=False)
        pd.DataFrame({"tCaptureNs": [5], "id": [1]}).to_csv(d / "obstacles.csv", index=False)
        snaps = [1, 1, 1, 2, 2, 5, 3, 4, 5]
        bands = [None] * 5 + ["SILENT", None, None, None]
        rows = [{"t": k * 1.0, "state": "DEGRADED", "tn": [(1.5, 1.5, 0.0)], "band": b, "match": None, "dist": None, "snap": s}
                for k, (s, b) in enumerate(zip(snaps, bands))]
        rows.append({"t": 9.0, "state": "NORMAL", "tn": [(1.5, 1.5, 0.0)], "band": "WARN", "match": 0, "dist": 1.5, "snap": 5})
        z = zone_stats(rows, truth, pol, miss_reason(d))
    want = {"noFloor": 3.0, "noCluster": 2.0, "silent": 1.0, "outOfBand": 1.0, "allFiltered": 1.0, "unconfirmed": 1.0}
    assert z["missedWarnReasons"] == dict(sorted(want.items())), z["missedWarnReasons"]
    assert abs(z["missedWarnFraction"] - 9 / 10) < 1e-9 and abs(sum(z["missedWarnReasons"].values()) - 9.0) < 1e-9


def check_heading_floor_error():
    """S6 진단 지표(M18): 진행 방향 오차는 걷는 블록만, 정렬 ±z 기준. 바닥 오차는 첫 추정 뒤만."""
    from metrics import floor_error, heading_error

    al = {"dir": [0.0, -1.0], "floorY": -1.0}  # 정답 +z = 월드 −z → toDeg 0°
    t = np.arange(0, 4.0, 0.5)
    cz = np.array([0, 0, 0, 0.5, 1.0, 1.5, 2.0, 2.5])  # 1.5 s부터 1 m/s(처음 세 블록은 서 있음)
    sgn = np.ones_like(t)
    hd = np.array([50, 50, 50, 2, -2, 10, -10, 2], dtype=float)
    e = heading_error(hd, t, cz, sgn, al)
    assert e["n"] == 5 and abs(e["p50"] - 2.0) < 1e-9 and abs(e["p95"] - 10.0) < 1e-9, e
    e = heading_error(hd + 180, t, cz, -sgn, al)  # 돌아오는 방향
    assert abs(e["p50"] - 2.0) < 1e-9, e
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        pd.DataFrame({"tCaptureNs": range(6), "floorY": [None, -0.9, -0.9, None, None, -1.0]}).to_csv(d / "slow_path.csv", index=False)
        f = floor_error(d, al)
    assert abs(f["max"] - 0.1) < 1e-6 and abs(f["lostFraction"] - 2 / 5) < 1e-9 and abs(f["p05"] - 0.0) < 0.02, f


def check_scorecard():
    """T5 판정(M12.2): 합격·경계·불합격·해당 없음, 첫 경고는 0.9 × min(출발 거리, warnMaxM) ± 줄자 여유, 구간 1 s 미만은 판정 안 함."""
    from scorecard import BORDER, FAIL, NA, PASS, judge, verdict

    assert [verdict(v, "<=", 6.0, 1.0) for v in (4.5, 6.5, 7.5, None)] == [PASS, BORDER, FAIL, NA]
    assert verdict(0, "<=", 0) == PASS and verdict(1, "<=", 0) == FAIL
    cfg = {"tapeM": 0.03, "zoneMinS": 1.0}
    item = {"key": "firstWarn", "kind": "firstWarn", "op": ">=", "target": 0.9}
    base = {"config": {"warnMaxM": 2.5, "stopM": 1.0}, "startDistanceM": {"box": 3.0}, "warnZoneS": {"box": 2.0}}
    j = lambda d, **kw: judge({**base, "firstWarnDistanceM": {"box": d}, **kw}, item, cfg)  # noqa: E731
    assert j(2.4)[0] == PASS and j(2.24)[0] == BORDER and j(2.0)[0] == FAIL and j(None) == (FAIL, "없음/2.25"), (j(2.24), j(None))
    assert j(2.1, startDistanceM={"box": 2.2})[0] == PASS  # 출발이 경고 구간 안: 분모 2.2 m → 기준 1.98 m
    assert j(2.0, warnZoneS={"box": 0.5}) == (NA, "–")
    angle = {"key": "direction", "metric": "directionErrorDeg.p95", "op": "<=", "target": 6.0, "margin": "angle"}
    m = {"directionErrorDeg": {"p95": 6.8}, "truth": {"align": {"angleUncertaintyDeg": 1.0}}}
    assert judge(m, angle, cfg)[0] == BORDER


def check_front_diag():
    """M20 대표점 진단: 합성 재생의 블록마다 군집 기록 짝과 대표점 칸 상태가 있고, 평활 전 대표점으로 잰 방향도 맞는다."""
    df = block_causes(ROOT / "clean" / "session", ROOT / "clean" / "run")
    assert len(df) > 0 and (df.cause != "짝 없음").all(), df.cause.value_counts()
    assert (df.state != "").all() and df.pairM.max() < 0.05, (df.state.value_counts(), df.pairM.max())
    assert (df.rawErr < 3.0).all(), df.rawErr.max()
    assert classify("HIT", 1.0, 1.1) == "지금 앞면 당김" and classify("HIT", 1.08, 1.1) == "지금 다른 위치"
    assert classify("OUT_OF_VIEW", 0, 0) == "시야 밖" and classify("NO_DEPTH", 0, 0) == "확인 못 함" and classify("", 0, 0) == "짝 없음"


def main():
    clean = run("clean")
    assert clean["directionErrorDeg"]["p95"] < 2.0, clean["directionErrorDeg"]
    assert clean["directionErrorAheadDeg"] == clean["directionErrorDeg"] and clean["besideFraction"] == 0, clean["besideFraction"]
    assert clean["overestimate1p5to2p5"]["p95"] < 0.03, clean["overestimate1p5to2p5"]
    assert clean["missedStop"] == 0 and clean["falseAlarmFraction"] == 0 and clean["walkSignFlips"] == 0
    assert clean["objectMergedFraction"] == 0, clean["objectMergedFraction"]  # 뒤 벽 없는 상자: 합쳐짐 없음
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
    check_merged_fraction()
    check_group_and_plan()
    check_depth_error()
    check_object_shape()
    check_id_switches()
    check_round_trip_corridor()
    check_walk_sign()
    check_zone_stats()
    check_miss_reasons()
    check_heading_floor_error()
    check_scorecard()
    check_front_diag()
    print("analysis self-check ok")


if __name__ == "__main__":
    main()
