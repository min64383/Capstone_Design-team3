"""장면 지표 집계 (M30, IMPROVE_SPEC §9.4). core `SceneEval`이 재생마다 쓴 `scene_eval.json`을 묶음별로 모은다.

    python scene_eval.py synth <합성 출력 폴더>                       → 열화 강도별 평균·95% 신뢰구간, 단조성, s=0 대 s=1
    python scene_eval.py real <재생 폴더> <변형> <변형> [--scene E]   → 세션 단위 변형 비교(예: B D), 판정 세트는 E 장면
    python scene_eval.py bookmarks <기준 재생 폴더> <출력 json> <세션 폴더> ...   → 정성 평가 책갈피 시각(M30 설계 표 6)
    python scene_eval.py m32real <재생 폴더> <기준> <변형> ... [--scene E]      → M32 완료 기준 ②④(바닥 맞은 칸 제외 지표, 안전 관문)
    python scene_eval.py m32synth <출력 json> <이름>=<합성 출력 폴더> ... [--s 1.0] → M32 기준 ③④(장면 지표, 마주침 STOP 검출)
    python scene_eval.py m33real <재생 폴더> <기준> <변형> ...                   → M33 미리 정한 주 지표(설계 표 7), 세션 쌍 차이 구간
    python scene_eval.py m37 <재생 폴더> <변형>:<기준> ...                        → M37 지도 비교: M32 주 지표 세션 쌍 차 구간과 나음·비슷·나쁨

신뢰구간: 부트스트랩 2,000회, 장면(합성)·세션(녹화) 단위 재표본(Efron & Tibshirani 1993). 한 세션 안의 칸은 서로 상관되어
칸 단위 재표본은 구간을 지나치게 좁힌다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

# 주 지표(M30 설계 표 2의 ★)와 나빠지는 방향(+1 = 클수록 나쁨)
HEADLINE = {"surfaceErrorP90M": +1, "falseRatio": +1, "completenessWallObject": -1}
EXTRA = ["surfaceErrorP50M", "insideRatio", "completenessAll", "f1tau", "unknownRatio"]
N_BOOT = 2000
SEED = 30


def load(path: Path) -> dict:
    d = json.loads(path.read_text(encoding="utf-8"))
    d["f1tau"] = d["fScore"]["1tau"]
    d["completenessFloor"] = d.get("byElement", {}).get("FLOOR", {}).get("completeness")
    return d


# M32 주 지표: 바닥에 맞은 칸을 뺀 헛 칸 비율·표면 오차(지도에 바닥이 들어오면 쉬운 맞은 칸이 늘어난다, M32 설계 3.6절)
HEADLINE32 = {"falseRatioNoFloor": +1, "surfaceErrorP90NoFloorM": +1, "completenessWallObject": -1}
EXTRA32 = ["falseRatioAboveFloorBand", "f1tau", "unknownRatio", "erasedRatio", "insideRatioNoFloor", "surfaceErrorP50NoFloorM", "completenessFloor",
           "falseRatio", "surfaceErrorP90M"]


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float, float]:
    """비율 k/n과 Wilson 95% 구간(Wilson 1927)."""
    if n == 0:
        return np.nan, np.nan, np.nan
    p = k / n
    c = (p + z * z / (2 * n)) / (1 + z * z / n)
    h = z * np.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return p, c - h, c + h


def unknown_state(m: dict) -> float:
    """UNKNOWN 상태 시간 비율. 상태 비율이 없으면(지표가 없음) 0이 아니라 NaN(A1 FA11: 빠진 값이 안전 관문을 그냥 통과시키지 않게)."""
    sf = m.get("stateFraction")
    return np.nan if sf is None else float(sf.get("UNKNOWN", 0.0))


def with_metrics(runs: list[Path], dropped: list[Path]) -> list[Path]:
    """metrics.json이 있는 재생 폴더만 돌려주고 나머지는 [dropped]에 넣는다(A1 FA11: 중단된 sweep의 폴더가 STOP 누락 0·UNKNOWN 0으로
    들어가지 않게)."""
    keep = [r for r in runs if (r / "metrics.json").is_file()]
    dropped += [r for r in runs if not (r / "metrics.json").is_file()]
    return keep


def report_dropped(dropped: list[Path], out: dict) -> None:
    """뺀 재생을 결과(`droppedNoMetrics`)와 stderr에 남긴다."""
    out["droppedNoMetrics"] = [str(p) for p in dropped]
    if dropped:
        print(f"주의: metrics.json 없는 재생 {len(dropped)}개를 뺐다: {', '.join(str(p) for p in dropped[:5])}{' …' if len(dropped) > 5 else ''}",
              file=sys.stderr)


def missed_stop(sub: pd.DataFrame) -> dict:
    """STOP 누락 합(값이 있는 세션만)과 값이 없는 세션 수. 없는 세션이 있으면 "누락 없음"이라 하지 않는다."""
    return {"missedStop": int(sub.missedStop.dropna().sum()), "missedStopUnknown": int(sub.missedStop.isna().sum())}


def m32_row(f: Path) -> dict:
    """재생 폴더 하나의 M32 지표(장면 지표 + 안전 지표)."""
    d = load(f)
    m = json.loads((f.parent / "metrics.json").read_text(encoding="utf-8")) if (f.parent / "metrics.json").is_file() else {}
    r = {k: (np.nan if d.get(k) is None else float(d[k])) for k in [*HEADLINE32, *EXTRA32]}
    r.update({"scene": m.get("scene", ""), "unknownState": unknown_state(m),
              "missedStop": m.get("missedStop"), "stopNeeded": m.get("stopNeeded"),
              "infoAgeMaxMs": (m.get("infoAgeMs") or {}).get("max"), "falseAlarm": m.get("falseAlarmFraction"),
              "firstWarn": m.get("firstWarnDistanceM") or {}, "firstStop": m.get("firstStopDistanceM") or {}})
    return r


def m32_real(root: Path, base: str, variants: list[str], scene: str | None) -> dict:
    """완료 기준 ②(헛 칸 비율 구간이 기준보다 낮게 분리, 표면 오차·완전성은 평균이 기준 구간 안이거나 더 좋음)와 ④(STOP 누락·확인 불가)."""
    rows, dropped = [], []
    for v in [base, *variants]:
        for run in with_metrics([f.parent for f in sorted((root / v).glob("*/scene_eval.json"))], dropped):
            r = m32_row(run / "scene_eval.json")
            if scene and not r["scene"].startswith(scene):
                continue
            rows.append({"variant": v, "session": run.name, **r})
    df = pd.DataFrame(rows)
    keys = [*HEADLINE32, *EXTRA32, "unknownState"]
    ci = {v: {k: boot_ci(df[df.variant == v][k].to_numpy(float)) for k in keys} for v in [base, *variants]}
    out = {"sessions": int(df.session.nunique()), "variants": {}}
    report_dropped(dropped, out)
    for v in [base, *variants]:
        sub = df[df.variant == v]
        e: dict = {k: {"mean": ci[v][k][0], "lo": ci[v][k][1], "hi": ci[v][k][2]} for k in keys}
        e.update(missed_stop(sub))
        e["infoAgeMaxMs"] = float(sub.infoAgeMaxMs.max())
        if v != base:
            b = ci[base]
            e["criterion2"] = {
                "falseSeparatedLower": bool(ci[v]["falseRatioNoFloor"][2] < b["falseRatioNoFloor"][1]),
                "p90NotWorse": bool(ci[v]["surfaceErrorP90NoFloorM"][0] <= b["surfaceErrorP90NoFloorM"][2]),
                "completenessNotWorse": bool(ci[v]["completenessWallObject"][0] >= b["completenessWallObject"][1]),
            }
            e["criterion4"] = {"noMissedStop": e["missedStop"] == 0 and e["missedStopUnknown"] == 0,
                               "unknownNotWorse": bool(ci[v]["unknownState"][0] <= b["unknownState"][2])}
        out["variants"][v] = e
    df.drop(columns=["firstWarn", "firstStop"]).to_csv(root / f"m32_{base}_{'_'.join(variants)}.csv", index=False)
    out["firstWarnStop"] = {f"{r.variant}/{r.session}": {"warn": r.firstWarn, "stop": r.firstStop} for r in df.itertuples()}
    return out


# M33 미리 정한 주 지표(설계 표 7)와 나빠지는 방향(+1 = 클수록 나쁨). 세션 평균, 비교는 같은 세션끼리의 차(변형 − 기준).
M33_GUIDE = {"falseAlarmPer10m": +1, "endWallFirstWarn": -1, "unknownState": +1}
M33_MAP = {"footprint": +1, "contradictionRaw": +1, "wallThicknessM": +1, "floorFlatnessM": +1, "floorBelow": +1, "roundTripSideM": +1, "roundTripFloorM": +1}
M33_REPORT = ["contradictionSmoothed", "contradictionOwn", "distanceErrorP50M", "distanceErrorFloorP50M", "distanceErrorAboveP50M", "edgeUndecidedWarnFraction", "sideWallWarnFraction", "bandedFalseRatio", "undecidedRatio",
              "completenessAllPixelsWallObject", "falseRatioNoFloor", "erasedRatio", "unknownRatio"]


def m33_row(run: Path) -> dict:
    """재생 폴더 하나의 M33 지표(map_check.json, scene_eval.json, metrics.json)."""
    def js(name):
        f = run / name
        return json.loads(f.read_text(encoding="utf-8")) if f.is_file() else {}
    c, s, m = js("map_check.json"), js("scene_eval.json"), js("metrics.json")
    dg = m.get("distanceGuidance") or {}
    rt = c.get("roundTripM") or {}
    raw = (m.get("replayInfo") or {}).get("depthSource") == "RAW" or "RAW" in json.dumps((json.loads((run / "replay_info.json").read_text(encoding="utf-8")) if (run / "replay_info.json").is_file() else {}).get("configOverrides", {}))
    contra = c.get("contradiction") or {}
    thick = [v for v in (c.get("thicknessM") or {}).values() if v is not None]
    def r(d):
        return np.nan if not d or d.get("ratio") is None else float(d["ratio"])
    side = [abs(v) for k, v in rt.items() if k != "floor"]
    return {
        "scene": m.get("scene", ""),
        "missedStop": m.get("missedStop"),
        "falseAlarmPer10m": np.nan if dg.get("falseAlarmPer10m") is None else float(dg["falseAlarmPer10m"]),
        "endWallFirstWarn": float("wall_end" in (dg.get("firstWarnAlongM") or {})) if dg.get("walkedM", 0) >= 1.0 else np.nan,
        "unknownState": unknown_state(m),
        "footprint": r(c.get("footprint")),
        "contradictionRaw": r(contra.get("raw")),
        "contradictionSmoothed": r(contra.get("smoothed")),
        "contradictionOwn": r(contra.get("raw" if raw else "smoothed")),
        "wallThicknessM": float(np.mean(thick)) if thick else np.nan,
        "floorFlatnessM": np.nan if c.get("floorFlatnessM") is None else float(c["floorFlatnessM"]),
        "floorBelow": r(c.get("floorBelow")),
        "roundTripSideM": float(np.mean(side)) if side else np.nan,
        "roundTripFloorM": abs(float(rt["floor"])) if "floor" in rt else np.nan,
        "distanceErrorP50M": np.nan if (dg.get("distanceErrorM") or {}).get("p50") is None else float(dg["distanceErrorM"]["p50"]),
        "distanceErrorFloorP50M": np.nan if (dg.get("distanceErrorFloorM") or {}).get("p50") is None else float(dg["distanceErrorFloorM"]["p50"]),
        "distanceErrorAboveP50M": np.nan if (dg.get("distanceErrorAboveM") or {}).get("p50") is None else float(dg["distanceErrorAboveM"]["p50"]),
        "edgeUndecidedWarnFraction": np.nan if dg.get("edgeUndecidedWarnFraction") is None else float(dg["edgeUndecidedWarnFraction"]),
        "sideWallWarnFraction": np.nan if dg.get("sideWallWarnFraction") is None else float(dg["sideWallWarnFraction"]),
        "bandedFalseRatio": np.nan if (s.get("banded") or {}).get("falseRatio") is None else float(s["banded"]["falseRatio"]),
        "undecidedRatio": np.nan if (s.get("banded") or {}).get("undecidedRatio") is None else float(s["banded"]["undecidedRatio"]),
        **{k: (np.nan if s.get(k) is None else float(s[k])) for k in ("completenessAllPixelsWallObject", "falseRatioNoFloor", "erasedRatio", "unknownRatio")},
    }


def paired_ci(a: np.ndarray, b: np.ndarray, n: int = N_BOOT, seed: int = SEED) -> tuple[float, float, float, int]:
    """같은 세션끼리의 차 a − b의 평균과 95% 부트스트랩 구간, 쓴 세션 수(둘 중 하나라도 값이 없는 세션은 뺀다)."""
    ok = ~(np.isnan(a) | np.isnan(b))
    m, lo, hi = boot_ci((a - b)[ok], n, seed)
    return m, lo, hi, int(ok.sum())


def m33_real(root: Path, base: str, variants: list[str], scene: str | None) -> dict:
    rows, dropped = [], []
    for v in [base, *variants]:
        for run in with_metrics(sorted(p for p in (root / v).iterdir() if p.is_dir()), dropped):
            r = m33_row(run)
            if scene and not r["scene"].startswith(scene):
                continue
            rows.append({"variant": v, "session": run.name, **r})
    df = pd.DataFrame(rows)
    keys = [*M33_GUIDE, *M33_MAP, *M33_REPORT]
    out = {"sessions": int(df.session.nunique()), "variants": {}}
    report_dropped(dropped, out)
    piv = {k: df.pivot(index="session", columns="variant", values=k) for k in keys}
    for v in [base, *variants]:
        sub = df[df.variant == v]
        e: dict = missed_stop(sub)
        for k in keys:
            mean, lo, hi = boot_ci(sub[k].to_numpy(float))
            e[k] = {"mean": mean, "lo": lo, "hi": hi}
            if v != base:
                d, dlo, dhi, nn = paired_ci(piv[k][v].to_numpy(float), piv[k][base].to_numpy(float))
                sign = M33_GUIDE.get(k, M33_MAP.get(k))
                e[k]["diff"] = {"mean": d, "lo": dlo, "hi": dhi, "n": nn}
                if sign is not None and not np.isnan(d):
                    e[k]["better"] = bool((dhi < 0) if sign > 0 else (dlo > 0))
                    e[k]["worse"] = bool((dlo > 0) if sign > 0 else (dhi < 0))
        out["variants"][v] = e
    df.to_csv(root / f"m33_{base}_{'_'.join(variants)}.csv", index=False)
    return out


def m37_pairs(root: Path, pairs: list[tuple[str, str]]) -> dict:
    """M37 지도 비교(설계 3.1절): 쌍 (변형 a, 기준 b)마다 M32 주 지표 세 개의 세션 쌍 차 a − b 평균과 95% 부트스트랩 구간.
    구간 전체가 좋은 쪽이면 나음, 0을 포함하면 비슷, 전체가 나쁜 쪽이면 나쁨(M33·M39와 같은 판정)."""
    out = {}
    for a, b in pairs:
        rows = {v: {p.name: m32_row(p / "scene_eval.json") for p in sorted((root / v).iterdir()) if (p / "scene_eval.json").is_file()}
                for v in (a, b)}
        sess = sorted(set(rows[a]) & set(rows[b]))
        e: dict = {"sessions": len(sess)}
        for k, sign in HEADLINE32.items():
            va = np.array([rows[a][s][k] for s in sess], float)
            vb = np.array([rows[b][s][k] for s in sess], float)
            d, lo, hi, n = paired_ci(va, vb)
            better = (hi < 0) if sign > 0 else (lo > 0)
            worse = (lo > 0) if sign > 0 else (hi < 0)
            e[k] = {"a": float(np.nanmean(va)), "b": float(np.nanmean(vb)), "diff": d, "lo": lo, "hi": hi, "n": n,
                    "verdict": "better" if better else "worse" if worse else "similar"}
        out[f"{a} vs {b}"] = e
    return out


def m32_synth(roots: dict[str, Path], s: float) -> dict:
    """합성(강도 [s]) 장면 지표 평균·구간과 마주침 STOP 검출(Wilson). 마주침 = 정답이 STOP 구간에 1 s 넘게 든 것(metrics stopNeeded)."""
    from metrics import compute
    out = {}
    for name, root in roots.items():
        rows, need, missed, first_stop, dropped = [], 0, 0, [], []
        for f in sorted(root.glob(f"*/s{s}/scene_eval.json")):
            run = f.parent
            mf = run / "metrics.json"
            if not mf.is_file() and (run / "session").is_dir():
                mf.write_text(json.dumps(compute(run / "session", run), ensure_ascii=False, default=float), encoding="utf-8")
            if not with_metrics([run], dropped):
                continue
            r = m32_row(f)
            rows.append(r)
            need += r["stopNeeded"] or 0
            missed += r["missedStop"] or 0
            first_stop += [v for v in r["firstStop"].values() if v is not None]
        df = pd.DataFrame(rows)
        e: dict = {k: dict(zip(("mean", "lo", "hi"), boot_ci(df[k].to_numpy(float)))) for k in [*HEADLINE32, *EXTRA32, "unknownState"]}
        e["scenes"] = len(df)
        e.update(requested(root, len(df)))
        report_dropped(dropped, e)
        e["encounters"] = need
        e["stopDetection"] = dict(zip(("p", "lo", "hi"), wilson(need - missed, need)))
        e["firstStopM"] = {"p10": float(np.percentile(first_stop, 10)), "p50": float(np.median(first_stop))} if first_stop else None
        out[name] = e
    return out


def boot_ci(v: np.ndarray, n: int = N_BOOT, seed: int = SEED) -> tuple[float, float, float]:
    """평균과 95% 부트스트랩 구간(백분위)."""
    v = v[~np.isnan(v)]
    if len(v) == 0:
        return np.nan, np.nan, np.nan
    rng = np.random.default_rng(seed)
    m = rng.choice(v, size=(n, len(v)), replace=True).mean(axis=1)
    return float(v.mean()), float(np.percentile(m, 2.5)), float(np.percentile(m, 97.5))


def requested(root: Path, n_scenes: int) -> dict:
    """합성 일괄 재생이 요청한 장면 수와 실패한 시드(`run_info.json`, M41). 모인 장면이 요청보다 적거나 실패한 시드가 있으면 stderr로
    알린다(A1 FA10: 실패한 장면이 통계에서 말없이 빠지지 않게)."""
    f = root / "run_info.json"
    if not f.is_file():
        return {}
    info = json.loads(f.read_text(encoding="utf-8"))
    failed = info.get("failedSeeds", [])
    if n_scenes < info.get("scenes", 0) or failed:
        print(f"주의: {root} 장면 {n_scenes}개 / 요청 {info.get('scenes')}개, 실패한 시드 {failed}", file=sys.stderr)
    return {"scenesRequested": info.get("scenes"), "failedSeeds": failed}


def synth_table(root: Path) -> pd.DataFrame:
    rows = []
    for f in sorted(root.glob("*/s*/scene_eval.json")):
        d = load(f)
        rows.append({"seed": int(f.parent.parent.name), "s": float(f.parent.name[1:]),
                     **{k: (np.nan if d.get(k) is None else float(d[k])) for k in [*HEADLINE, *EXTRA]}})
    return pd.DataFrame(rows)


def judge_synth(df: pd.DataFrame) -> dict:
    """강도별 평균·구간, 주 지표마다 단조성(평균이 강도 순으로 나빠짐)과 s=0·s=1 구간 분리."""
    out = {"scenes": int(df.seed.nunique()), "strengths": sorted(df.s.unique().tolist()), "metrics": {}}
    for k in [*HEADLINE, *EXTRA]:
        per = {s: boot_ci(df[df.s == s][k].to_numpy(float)) for s in out["strengths"]}
        m = {"bySeverity": {str(s): {"mean": v[0], "lo": v[1], "hi": v[2]} for s, v in per.items()}}
        if k in HEADLINE:
            sign = HEADLINE[k]
            means = [per[s][0] * sign for s in out["strengths"]]
            m["monotone"] = bool(all(a < b for a, b in zip(means, means[1:])))
            a, b = per.get(0.0), per.get(1.0)
            m["separated01"] = bool(a and b and ((sign > 0 and a[2] < b[1]) or (sign < 0 and a[1] > b[2])))
        out["metrics"][k] = m
    return out


def real_table(root: Path, variants: list[str], scene: str | None) -> pd.DataFrame:
    rows = []
    for v in variants:
        for f in sorted((root / v).glob("*/scene_eval.json")):
            d = load(f)
            m = json.loads((f.parent / "metrics.json").read_text(encoding="utf-8")) if (f.parent / "metrics.json").is_file() else {}
            sc = m.get("scene", "")
            if scene and not sc.startswith(scene):
                continue
            r = {"variant": v, "session": f.parent.name, "scene": sc, "floorY": d.get("floorY")}
            r.update({k: (np.nan if d.get(k) is None else float(d[k])) for k in [*HEADLINE, *EXTRA]})
            if "rgbFloor" in d:
                rg = d["rgbFloor"]
                r.update({f"rgb_{k}": (np.nan if rg.get(k) is None else float(rg[k])) for k in HEADLINE})
            rows.append(r)
    return pd.DataFrame(rows)


def judge_real(df: pd.DataFrame, base: str, other: str) -> dict:
    """세션 단위 평균·구간과, [other]가 [base]보다 좋고 구간이 겹치지 않는지(주 지표마다)."""
    out = {"sessions": int(df.session.nunique()), "metrics": {}}
    for k in [*HEADLINE, *EXTRA]:
        a = boot_ci(df[df.variant == base][k].to_numpy(float))
        b = boot_ci(df[df.variant == other][k].to_numpy(float))
        m = {base: {"mean": a[0], "lo": a[1], "hi": a[2]}, other: {"mean": b[0], "lo": b[1], "hi": b[2]}}
        if k in HEADLINE:
            sign = HEADLINE[k]
            m["otherBetter"] = bool(b[0] * sign < a[0] * sign)
            m["separated"] = bool((sign > 0 and b[2] < a[1]) or (sign < 0 and b[1] > a[2]))
        out["metrics"][k] = m
    return out


def bookmarks(runs: Path, sessions: list[Path]) -> list[dict]:
    """책갈피 시각(첫 프레임 수신 시각부터 s). 거리는 머리에서 정답 앞면까지 보행선 방향 거리. 규칙(M30 설계 표 6을 실제 보행에 맞춤:
    S 세션은 출발 때 이미 물체가 2.4 m 앞, E02는 머리가 끝 벽 1.2 m까지만 감): E01h 시작 3 s·가운데·끝, E03 돌기 전 끝 벽 1.0 m·돈 뒤 2 s,
    물체 장면 걷기 시작(머리 0.1 m 이동)·가장 가까이, 끝 벽·문 장면 3.0 m·가장 가까이."""
    from align import load_frames, to_truth
    from error_decomp import forward_xz, head_xz, load_cfg
    from metrics import load_truth, scene_of

    out = []
    for s in sessions:
        cfg = load_cfg(s)
        al = json.loads((runs / s.name / "align.json").read_text(encoding="utf-8"))
        f = load_frames(s)
        t_first = f.sysElapsedNs.iloc[0]  # 재생·viewer의 시작 시각 = 첫 행 수신 시각
        f = f[f.tracking == "TRACKING"].reset_index(drop=True)
        fwd = forward_xz(f)
        hxz = head_xz(f[["tx", "tz"]].to_numpy(), fwd, cfg["head"]["offsetFromCameraM"])
        _, _, hz = to_truth(al, hxz[:, 0], np.zeros(len(f)), hxz[:, 1])
        fz = to_truth({**al, "origin": [0.0, 0.0]}, fwd[:, 0], np.zeros(len(f)), fwd[:, 1])[2]
        t = (f.sysElapsedNs - t_first).to_numpy() / 1e9
        truth = load_truth(s, cfg)[0]
        sc = scene_of(s)
        objs = [o for o in truth if o["kind"] == "object"]
        ends = [o for o in truth if o["kind"] == "structure" and o["max"][2] - o["min"][2] < 0.5]
        marks = []

        def first(dist: np.ndarray, d: float, label: str):
            i = np.flatnonzero(dist <= d)
            if len(i):
                marks.append({"tS": round(float(t[i[0]]), 2), "label": label})

        def start_and_closest(front: float, name: str):
            moved = np.flatnonzero(hz - hz[0] > 0.1)
            if len(moved):
                marks.append({"tS": round(float(t[moved[0]]), 2), "label": f"걷기 시작({name} {front - hz[moved[0]]:.1f} m)"})
            i = int(np.argmin(front - hz))
            marks.append({"tS": round(float(t[i]), 2), "label": f"가장 가까이({name} {front - hz[i]:.1f} m)"})

        if sc.startswith("E01"):
            for lab, x in (("시작 3 s", 3.0), ("가운데", t[-1] / 2), ("끝", t[-1] - 1.0)):
                marks.append({"tS": round(float(x), 2), "label": lab})
        elif sc.startswith("E03"):
            front = min(o["min"][2] for o in ends)
            first(front - hz, 1.0, "돌기 전 끝 벽 1.0 m")
            back = np.flatnonzero(fz < -0.5)
            if len(back):
                marks.append({"tS": round(float(t[back[0]] + 2.0), 2), "label": "돈 뒤 2 s"})
        elif objs and objs[0]["name"] != "door":
            # 보행선에 가장 가까운 물체(가로 가운데 |x| 최소)
            o = min(objs, key=lambda o: abs(o["min"][0] + o["max"][0]))
            start_and_closest(o["min"][2], "물체")
        else:
            front = min(o["min"][2] for o in (objs or ends))
            name = "문" if objs else "끝 벽"
            first(front - hz, 3.0, f"{name} 3.0 m")
            i = int(np.argmin(front - hz))
            marks.append({"tS": round(float(t[i]), 2), "label": f"가장 가까이({name} {front - hz[i]:.1f} m)"})
        out.append({"session": s.name, "scene": sc, "truthColors": sc.startswith("E"), "marks": marks})
    return out


def fmt(m: dict) -> str:
    return f"{m['mean']:.3f} [{m['lo']:.3f}, {m['hi']:.3f}]"


def main(args: list[str]) -> None:
    if not args:
        sys.exit(__doc__)
    if args[0] == "synth":
        root = Path(args[1])
        df = synth_table(root)
        res = judge_synth(df)
        res.update(requested(root, res["scenes"]))
        (root / "summary.json").write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding="utf-8")
        df.to_csv(root / "summary.csv", index=False)
        print(f"합성 {res['scenes']}장면, 강도 {res['strengths']}")
        for k, m in res["metrics"].items():
            line = "  ".join(f"s{s} {fmt(v)}" for s, v in m["bySeverity"].items())
            tag = f"  단조 {m['monotone']}  s0·s1 분리 {m['separated01']}" if "monotone" in m else ""
            print(f"{k:24s} {line}{tag}")
    elif args[0] == "real":
        root, base, other = Path(args[1]), args[2], args[3]
        scene = args[args.index("--scene") + 1] if "--scene" in args else None
        df = real_table(root, [base, other], scene)
        res = judge_real(df, base, other)
        df.to_csv(root / f"scene_{base}_{other}{'_' + scene if scene else ''}.csv", index=False)
        (root / f"scene_{base}_{other}{'_' + scene if scene else ''}.json").write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding="utf-8")
        print(f"녹화 {res['sessions']}세션 ({scene or '전체'})")
        for k, m in res["metrics"].items():
            tag = f"  {other} 더 좋음 {m['otherBetter']}  분리 {m['separated']}" if "separated" in m else ""
            print(f"{k:24s} {base} {fmt(m[base])}  {other} {fmt(m[other])}{tag}")
    elif args[0] == "bookmarks":
        res = bookmarks(Path(args[1]), [Path(a) for a in args[3:]])
        Path(args[2]).write_text(json.dumps({"note": "M30 정성 평가 책갈피(설계 표 6). scene_eval.py bookmarks가 규칙으로 계산", "sessions": res},
                                            indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        for r in res:
            print(r["session"], r["scene"], [(m["label"], m["tS"]) for m in r["marks"]])
    elif args[0] == "m37":
        root = Path(args[1])
        pairs = [tuple(a.split(":")) for a in args[2:]]
        res = m37_pairs(root, pairs)
        (root / "m37_pairs.json").write_text(json.dumps(res, indent=1, ensure_ascii=False, default=float), encoding="utf-8")
        for name, e in res.items():
            print(f"== {name} ({e['sessions']}세션, 차 = 앞 − 뒤, 95% 구간)")
            for k in HEADLINE32:
                m = e[k]
                print(f"  {k:26s} {m['a']:.4f} vs {m['b']:.4f}  차 {m['diff']:+.4f} [{m['lo']:+.4f}, {m['hi']:+.4f}]  {m['verdict']}")
    elif args[0] == "m33real":
        scene = args[args.index("--scene") + 1] if "--scene" in args else None
        pos = [a for i, a in enumerate(args[1:], 1) if a != "--scene" and args[i - 1] != "--scene"]
        root, base, variants = Path(pos[0]), pos[1], pos[2:]
        res = m33_real(root, base, variants, scene)
        (root / f"m33_{base}_{'_'.join(variants)}.json").write_text(json.dumps(res, indent=1, ensure_ascii=False, default=float), encoding="utf-8")
        print(f"녹화 {res['sessions']}세션 ({scene or '전체'}), 차 = 변형 − {base} (세션 쌍 95% 구간)")
        for v, e in res["variants"].items():
            print(f"== {v}  STOP 누락 {e['missedStop']} (값 없는 세션 {e['missedStopUnknown']})")
            for k in [*M33_GUIDE, *M33_MAP, *M33_REPORT]:
                m = e[k]
                d = m.get("diff")
                tag = "" if not d else f"   차 {d['mean']:+.4f} [{d['lo']:+.4f}, {d['hi']:+.4f}] n{d['n']}" + (" 좋음" if m.get("better") else "") + (" 나쁨" if m.get("worse") else "")
                print(f"   {k:32s} {fmt(m)}{tag}")
    elif args[0] in ("m32real", "m32synth"):
        flag = "--scene" if args[0] == "m32real" else "--s"
        opt = args[args.index(flag) + 1] if flag in args else None
        pos = [a for i, a in enumerate(args[1:], 1) if a != flag and args[i - 1] != flag]
        if args[0] == "m32real":
            root, base, variants = Path(pos[0]), pos[1], pos[2:]
            res = m32_real(root, base, variants, opt)
            (root / f"m32_{base}_{'_'.join(variants)}.json").write_text(json.dumps(res, indent=1, ensure_ascii=False, default=float), encoding="utf-8")
            print(f"녹화 {res['sessions']}세션 ({opt or '전체'})")
            for v, e in res["variants"].items():
                print(v, "  ".join(f"{k} {fmt(e[k])}" for k in [*HEADLINE32, "falseRatioAboveFloorBand", "unknownRatio", "erasedRatio", "unknownState"]),
                      f"missedStop {e['missedStop']} infoAgeMax {e['infoAgeMaxMs']:.0f}", e.get("criterion2", ""), e.get("criterion4", ""))
        else:
            res = m32_synth({a.split("=", 1)[0]: Path(a.split("=", 1)[1]) for a in pos[1:]}, float(opt) if opt else 1.0)
            Path(pos[0]).write_text(json.dumps(res, indent=1, ensure_ascii=False, default=float), encoding="utf-8")
            for name, e in res.items():
                d = e["stopDetection"]
                print(name, f"{e['scenes']}장면", "  ".join(f"{k} {fmt(e[k])}" for k in [*HEADLINE32, "falseRatioAboveFloorBand", "unknownRatio", "erasedRatio"]),
                      f"STOP 검출 {d['p']:.3f} [{d['lo']:.3f}, {d['hi']:.3f}] / {e['encounters']}", e["firstStopM"])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")  # 빠진 재생·장면 알림(M41)
    main(sys.argv[1:])
