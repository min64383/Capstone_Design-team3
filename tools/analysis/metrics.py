"""지표 (MVP_SPEC §10.2, M8). 기준값은 가설.

    python metrics.py <세션 폴더> <실행 로그 폴더>   → <실행 로그 폴더>/metrics.json
    python metrics.py --run <실행 로그 폴더>          → 세션 없이 실행 로그만(사용자 모드 T01 등, M10)

- 정답: <세션>/annotations/obstacles.json (정답 좌표, docs/FORMAT.md). 없으면 정답이 필요 없는 지표만.
  `"scene"`이 있으면 장면 ID로 쓴다(폴더 이름의 장면 ID가 틀린 녹화, M10).
- 실행 로그: 같은 세션의 PC 오프라인 재생(`:core:replay`) 또는 그 세션을 녹화하며 돌린 실시간 로그.
  ARCore 재생 모드 로그는 월드 좌표가 녹화와 달라(F8) 정답 지표를 낼 수 없다.
- 정답 방향 = 보행선 ±z 중 카메라의 최근 이동 쪽(`walk_sign`, 왕복 세션, M12.2), 정답 머리 = 카메라 + 파지 오프셋(그 방향 기준).
  정답 통로 = 머리에서 그 방향으로 폭·길이.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from align import DEFAULT_CONFIG, align, load_config, load_frames, to_truth

MATCH_MARGIN_M = 0.3  # 추정 대표점이 정답 상자에서 이만큼 안이면 그 장애물로 본다(분석 도구 파라미터)
MERGE_BEYOND_M = 0.5  # 정답 물체와 겹치는 추정 물체가 그 물체 뒤로 이만큼 넘게 이어지면 합쳐짐(13번 계획서 표 5, M13.1)
OVER_LO_M = 1.5  # 과대추정 지표의 정답 거리 하한(명세 §9.2 "1.5~2.5 m"), 부호 있는 거리 오차의 구간 경계로도 쓴다(M12.2)
ACTIVE = {"NORMAL", "DEGRADED"}
WALK_SIGN_WINDOW_S = 1.0  # 정답 진행 부호의 창: 앱 heading.windowS(M18에서 2.0으로)와 떼어 M12.2 정답을 그대로 둔다


def p(v, q):
    v = np.asarray(v, dtype=float)
    return float(np.percentile(v, q)) if v.size else None


def load_truth(session: Path, cfg: dict):
    """정답 v1·v2 (IMPROVE_SPEC §9.1). `distanceFrom: camera`면 머리 원점으로 옮긴다(Kotlin `GroundTruth`와 같은 계산):
    머리 = 카메라 + 오프셋(진행 방향 기준)이라 정답 좌표에서 카메라는 (−ox, ·, −oz). `kind` 없으면 object."""
    f = session / "annotations/obstacles.json"
    if not f.is_file():
        return None, None
    j = json.loads(f.read_text(encoding="utf-8"))
    frm = j.get("distanceFrom", "start")
    if frm not in ("start", "camera"):
        raise ValueError(f"distanceFrom must be start or camera, got {frm}")
    ox, _, oz = cfg["head"]["offsetFromCameraM"]
    sx, sz = (-ox, -oz) if frm == "camera" else (0.0, 0.0)
    obs = []
    for o in j["obstacles"]:
        o = dict(o)
        o["min"] = [o["min"][0] + sx, o["min"][1], o["min"][2] + sz]
        o["max"] = [o["max"][0] + sx, o["max"][1], o["max"][2] + sz]
        o.setdefault("kind", "object")
        obs.append(o)
    return obs, bool(j.get("estimated", False))


def scene_of(session: Path) -> str:
    """정답 파일의 `scene`, 없으면 폴더 이름 끝(`..._S02` → S02)."""
    f = session / "annotations/obstacles.json"
    if f.is_file():
        s = json.loads(f.read_text(encoding="utf-8")).get("scene")
        if s:
            return s
    name = session.parent.name if session.name == "session" else session.name
    return name.rsplit("_", 1)[-1]


def truth_boxes(obs: pd.DataFrame, al: dict) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """추정 물체 상자(월드)의 네 모서리를 정답 좌표로 옮긴 바닥 사각형 (x0, x1, z0, z1)."""
    tx, tz = [], []
    for cx, cz in (("aabbMinX", "aabbMinZ"), ("aabbMinX", "aabbMaxZ"), ("aabbMaxX", "aabbMinZ"), ("aabbMaxX", "aabbMaxZ")):
        x, _, z = to_truth(al, obs[cx].to_numpy(), np.zeros(len(obs)), obs[cz].to_numpy())
        tx.append(x)
        tz.append(z)
    return np.min(tx, 0), np.max(tx, 0), np.min(tz, 0), np.max(tz, 0)


def object_shape(obs: pd.DataFrame, truth: list[dict], al: dict) -> dict | None:
    """형상 지표(M13 형상 정제): 정답 물체마다 바닥 면적이 겹치는 추정 물체 상자(느린 경로 단계마다 겹침이 가장 큰 것)의
    앞면 위치 오차(추정 앞면 − 정답 앞면, + = 멀리), 앞뒤 길이, 폭의 단계 중앙값. 정답 +z가 걷는 방향이라 앞면 = 작은 z."""
    objs = [o for o in truth if o["kind"] == "object"]
    if not objs or obs.empty:
        return None
    x0, x1, z0, z1 = truth_boxes(obs, al)
    out = {}
    for o in objs:
        (ox0, _, oz0), (ox1, _, oz1) = o["min"], o["max"]
        area = np.clip(np.minimum(x1, ox1) - np.maximum(x0, ox0), 0, None) * np.clip(np.minimum(z1, oz1) - np.maximum(z0, oz0), 0, None)
        df = pd.DataFrame({"t": obs.tCaptureNs.to_numpy(), "a": area, "front": z0 - oz0, "depth": z1 - z0, "width": x1 - x0})
        df = df[df.a > 0]
        if df.empty:
            continue
        best = df.loc[df.groupby("t").a.idxmax()]
        out[o["name"]] = {"frontErrorM": float(best.front.median()), "depthM": float(best.depth.median()),
                          "widthM": float(best.width.median()), "truthDepthM": float(oz1 - oz0), "truthWidthM": float(ox1 - ox0),
                          "n": int(len(best))}
    return out or None


def id_switches(rows: list[dict], objects: set[int]) -> int | None:
    """정답 물체마다 그 물체로 짝지어진 경고(WARN·STOP)의 서로 다른 추적 id 수 − 1을 더한다(id 전환, §9.2). 짝지어진 경고가 없으면 None."""
    ids: dict[int, set[int]] = {}
    for r in rows:
        if r["band"] in ("WARN", "STOP") and r["match"] in objects and r.get("oid") is not None:
            ids.setdefault(r["match"], set()).add(r["oid"])
    return sum(len(s) - 1 for s in ids.values()) if ids else None


def merged_fraction(obs: pd.DataFrame, truth: list[dict], al: dict) -> float | None:
    """물체–구조물 합쳐짐 비율: 정답 물체(`kind: object`)와 바닥 면적이 겹치는 추정 물체가 있는 느린 경로 단계 중, 그 추정
    물체가 물체 뒷면(정답 +z) 너머로 MERGE_BEYOND_M 넘게 이어진 단계의 비율(막이 캐리어를 뒤 문·벽까지 이은 경우)."""
    objs = [o for o in truth if o["kind"] == "object"]
    if not objs or obs.empty:
        return None
    x0, x1, z0, z1 = truth_boxes(obs, al)
    seen = merged = 0
    for o in objs:
        (ox0, _, oz0), (ox1, _, oz1) = o["min"], o["max"]
        over = (x1 > ox0) & (x0 < ox1) & (z1 > oz0) & (z0 < oz1)
        seen += obs.tCaptureNs[over].nunique()
        merged += obs.tCaptureNs[over & (z1 > oz1 + MERGE_BEYOND_M)].nunique()
    return merged / seen if seen else None


def per_minute(blocks: pd.DataFrame, sp: pd.DataFrame, dev: pd.DataFrame | None) -> list[dict]:
    """지속 동작(§10.2): 1분마다 UNKNOWN 비율·느린 경로 주기·스냅샷 나이·계산 시간·발열."""
    t0 = blocks.tBlockNs.min()
    bm = ((blocks.tBlockNs - t0) // 60_000_000_000).astype(int)
    sm = ((sp.tStartNs - t0) // 60_000_000_000).astype(int)
    out = []
    for m in range(int(bm.max()) + 1):
        b, s = blocks[bm == m], sp[sm == m]
        span = (b.tBlockNs.max() - b.tBlockNs.min()) / 1e9 if len(b) > 1 else 0
        row = {
            "minute": m,
            "unknownFraction": round(float((b.state == "UNKNOWN").mean()), 4) if len(b) else None,
            "slowPathHz": round(len(s) / span, 2) if span > 0 else None,
            "snapshotAgeP50Ms": p((b.tBlockNs - b.snapshotTNs).dropna() / 1e6, 50),
            "slowComputeP50Ms": p((s.tDoneNs - s.tStartNs) / 1e6, 50),
            "captureToDoneP50Ms": p((s.tDoneNs - s.tCaptureNs) / 1e6, 50),
        }
        if dev is not None:
            d = dev[((dev.tNs - t0) // 60_000_000_000).astype(int) == m]
            row["thermalMax"] = int(d.thermalStatus.max()) if len(d) else None
        out.append(row)
    return out


def run_metrics(run_dir: Path, cfg: dict) -> dict:
    """정답·세션 없이 실행 로그만으로 되는 지표."""
    g = pd.read_csv(run_dir / "guidance.csv")
    sp = pd.read_csv(run_dir / "slow_path.csv")
    blocks = g.drop_duplicates("tBlockNs")
    out: dict = {"run": str(run_dir), "config": {"repStrategy": cfg["repPoint"]["strategy"]}}
    out["durationS"] = float((blocks.tBlockNs.max() - blocks.tBlockNs.min()) / 1e9)
    out["stateFraction"] = blocks.state.value_counts(normalize=True).round(4).to_dict()
    dur = (sp.tCaptureNs.max() - sp.tCaptureNs.min()) / 1e9
    out["slowPathHz"] = float(len(sp) / dur) if dur > 0 else None
    age = g.infoAgeMs.dropna()  # 음원이 있는 블록만 기록된다
    out["infoAgeMs"] = {"p50": p(age, 50), "p95": p(age, 95), "max": float(age.max()) if len(age) else None}
    snap = ((blocks.tBlockNs - blocks.snapshotTNs) / 1e6).dropna()  # 음원 유무와 무관한 스냅샷 나이
    out["snapshotAgeMs"] = {"p50": p(snap, 50), "p95": p(snap, 95)}
    prev = blocks.state.shift()
    out["unknownTransitions"] = int(((blocks.state == "UNKNOWN") & (prev != "UNKNOWN") & prev.notna()).sum())
    active = blocks[blocks.state.isin(ACTIVE)]
    out["warnFraction"] = float(active.band.isin(["WARN", "STOP"]).mean()) if len(active) else None
    dev = None
    if (run_dir / "device.csv").is_file():
        dev = pd.read_csv(run_dir / "device.csv")
        out["latencyMs"] = {"b_output_p50": p(dev.audioOutputLatencyMs.dropna(), 50)}
        out["thermalMax"] = int(dev.thermalStatus.max()) if len(dev) else None
    if out["durationS"] >= 60:
        out["perMinute"] = per_minute(blocks, sp, dev)
    if (run_dir / "stage_timing.csv").is_file():  # 실제 시계로 잰 단계별 처리 시간(M11). 기기 값이 아니면 PC 값
        st = pd.read_csv(run_dir / "stage_timing.csv")
        out["stageMs"] = {k: {"p50": p(st[f"{k}Ns"] / 1e6, 50), "p95": p(st[f"{k}Ns"] / 1e6, 95)} for k in ("map", "cluster", "track")}
    return out


def walk_sign(t, hz, window_s: float, min_travel_m: float) -> np.ndarray:
    """정답 진행 방향(M12.2): 블록마다 보행선 ±z 중 최근 이동 쪽(+1 / −1). 앱 `Heading`과 같은 창 — 창 시작 이전의 가장
    최근 블록부터의 머리 z 이동이 `min_travel_m` 이상이면 그 부호, 아니면 직전 부호(처음 +1). 과거 블록만 쓴다.
    정답 장면이 곧은 복도라 ±z로만 둔다(앱 진행 방향의 흔들림은 방향 오차에 남는다)."""
    t, hz = np.asarray(t, dtype=float), np.asarray(hz, dtype=float)
    ref = np.maximum(np.searchsorted(t, t - window_s, side="right") - 1, 0)
    out, s = np.empty(len(t)), 1.0
    for k in range(len(t)):
        d = hz[k] - hz[ref[k]]
        if abs(d) >= min_travel_m:
            s = float(np.sign(d))
        out[k] = s
    return out


WALKING_MPS = 0.25  # 진행 방향 오차를 재는 블록: 최근 1 s 보행선 방향 속도가 이보다 큼(M18, 서 있거나 돌아서는 구간 제외)


def heading_error(heading_deg, t, cz, sgn, al) -> dict | None:
    """앱 진행 방향 − 정답 보행 방향(정렬의 ±z, `sgn`)의 절댓값(°), 걷는 블록만(M18 진단). 방향 규약은 core `Heading.toDeg`."""
    d = np.asarray(al["dir"])
    truth = np.degrees(np.arctan2(d[0], -d[1])) + np.where(np.asarray(sgn) > 0, 0.0, 180.0)
    ref = np.maximum(np.searchsorted(t, t - 1.0, side="right") - 1, 0)
    speed = np.abs(cz - cz[ref]) / np.maximum(t - t[ref], 1e-3)
    ok = (speed > WALKING_MPS) & ~np.isnan(heading_deg)
    if not ok.any():
        return None
    e = np.abs((heading_deg[ok] - truth[ok] + 180.0) % 360.0 - 180.0)
    return {"p50": p(e, 50), "p95": p(e, 95), "n": int(ok.sum())}


def floor_error(run_dir: Path, al: dict) -> dict | None:
    """느린 경로 바닥 추정 − 정렬 바닥(m)의 p05·p95와 첫 추정 뒤 바닥 없음 비율(M18 진단: 벽을 타고 오름·잃음)."""
    sp = pd.read_csv(run_dir / "slow_path.csv")
    f = sp.floorY.to_numpy(dtype=float)
    known = ~np.isnan(f)
    if not known.any():
        return None
    after = f[np.argmax(known):]
    e = after[~np.isnan(after)] - al["floorY"]
    return {"p05": p(e, 5), "p95": p(e, 95), "max": float(e.max()), "lostFraction": float(np.isnan(after).mean())}


def truth_nearest(ob, hx, hz, cfg, sgn=1.0):
    """정답 통로(머리 기준 진행 방향 `sgn`·z) 안 최근접점 → (along, 수평거리, 방위각) 또는 None. 방위는 오른쪽 +.
    −z로 걸으면 앞 = −z, 오른쪽 = −x라 상자를 사용자 기준(앞·오른쪽)으로 옮겨 같은 계산을 한다."""
    c = cfg["corridor"]
    (x0, y0, z0), (x1, _, z1) = ob["min"], ob["max"]
    if y0 >= c["heightM"]:
        return None
    l0, l1 = sorted((sgn * (x0 - hx), sgn * (x1 - hx)))
    a0, a1 = sorted((sgn * (z0 - hz), sgn * (z1 - hz)))
    lo, hi = max(l0, -c["widthM"] / 2), min(l1, c["widthM"] / 2)
    if lo > hi:
        return None
    along = max(a0, -c["behindM"])
    if along > a1 or along > c["lengthM"]:
        return None
    ln = min(max(0.0, lo), hi)
    return along, float(np.hypot(ln, along)), float(np.degrees(np.arctan2(ln, along)))


def miss_reason(run_dir: Path):
    """경고 놓침 블록의 사유(M12.3, 기존 실행 로그만 씀): 블록이 쓴 스냅샷 시각으로 느린 경로 로그를 찾아, 위에서부터 처음 맞는 것.
    silent(음원은 있으나 SILENT로 안내) → noSnapshot → outOfBand(장애물은 있으나 명령 없음: 3.0 m 밖·머리 뒤) → noFloor(바닥 모름 →
    빈 스냅샷) → noCluster(통로 안 군집 없음) → allFiltered(오경보 거르개) → unconfirmed(추적 확인 대기)."""
    sp = pd.read_csv(run_dir / "slow_path.csv")
    no_floor = set(sp.tCaptureNs[sp.floorY.isna()])
    obs_t = set(pd.read_csv(run_dir / "obstacles.csv").tCaptureNs)
    cdf = run_dir / "cluster_debug.csv"
    cd = pd.read_csv(cdf) if cdf.is_file() else pd.DataFrame(columns=["tCaptureNs", "filtered"])
    clusters = {int(t): (len(g), int(g.filtered.astype(bool).sum())) for t, g in cd.groupby("tCaptureNs")}

    def why(r: dict) -> str:
        s = r.get("snap")
        if r["band"] == "SILENT":
            return "silent"
        if s is None:
            return "noSnapshot"
        if s in obs_t:
            return "outOfBand"
        if s in no_floor:
            return "noFloor"
        if s not in clusters:
            return "noCluster"
        n, f = clusters[s]
        return "allFiltered" if f == n else "unconfirmed"
    return why


def zone_stats(rows: list[dict], truth: list[dict], pol: dict, why=None) -> dict:
    """통로 안팎 판정과 거리 오차(M12.2 안내 성적표).
    - distanceErrorM: 짝지은 경고의 추정 − 정답 거리(m), 정답 거리 구간 [0, stopM), [stopM, 1.5), [1.5, ∞)마다 p05·p50·p95
    - missedWarnFraction: 정상 상태에서 정답 구간이 WARN·STOP인 블록 중 추정이 WARN·STOP이 아닌 비율
    - outsideCorridorFraction: 경고(WARN·STOP) 중 짝지은 정답이 그 블록에서 정답 통로 밖인 비율(옆 벽 등)
    - startDistanceM: 정상 상태에서 처음 정답 통로 안에 든 순간의 정답 거리(첫 경고 거리 판정의 분모)
    - warnZoneS·stopZoneS: 정상 상태에서 정답이 WARN·STOP 구간 안에 있던 시간(s, 명세 탐지율의 "1 s 이상" 판정용)
    - missedWarnReasons: 경고 놓침 시간(s)을 사유별로(`why` = `miss_reason(실행 로그)`가 있을 때, M12.3)"""
    t = np.array([r["t"] for r in rows], dtype=float)
    dt = np.diff(t, append=t[-1] + (np.median(np.diff(t)) if len(t) > 1 else 0.0)) if len(t) else t
    edges = [0.0, pol["stopM"], OVER_LO_M, np.inf]
    err: list[list[float]] = [[] for _ in edges[:-1]]
    zone_n = miss = n_cmd = outside = 0
    reasons: dict[str, float] = {}
    start: dict[int, float] = {}
    warn_s: dict[int, float] = {}
    stop_s: dict[int, float] = {}
    for r, d in zip(rows, dt):
        warned = r["band"] in ("WARN", "STOP")
        if r["state"] in ACTIVE:
            cands = [(v[0], i) for i, v in enumerate(r["tn"]) if v is not None]
            for i, v in enumerate(r["tn"]):
                if v is None:
                    continue
                start.setdefault(i, v[1])
                if v[0] < pol["warnMaxM"]:
                    warn_s[i] = warn_s.get(i, 0.0) + d
                if v[0] < pol["stopM"]:
                    stop_s[i] = stop_s.get(i, 0.0) + d
            if cands and band_of(min(cands)[0], pol) in ("WARN", "STOP"):
                zone_n += 1
                if not warned:
                    miss += 1
                    if why is not None:
                        k = why(r)
                        reasons[k] = reasons.get(k, 0.0) + d
        if warned:
            n_cmd += 1
            if r["match"] is not None:
                v = r["tn"][r["match"]]
                if v is None:
                    outside += 1
                else:
                    err[int(np.searchsorted(edges, v[1], side="right")) - 1].append(r["dist"] - v[1])

    def label(lo, hi):
        return f"{lo:g}-{hi:g}" if np.isfinite(hi) else f"{lo:g}-"

    name = lambda d: {truth[i]["name"]: round(float(v), 3) for i, v in sorted(d.items())}  # noqa: E731
    return {
        "distanceErrorM": {label(lo, hi): {"p05": p(e, 5), "p50": p(e, 50), "p95": p(e, 95), "n": len(e)}
                           for lo, hi, e in zip(edges[:-1], edges[1:], err)},
        "missedWarnFraction": miss / zone_n if zone_n else None,
        "outsideCorridorFraction": outside / n_cmd if n_cmd else None,
        "startDistanceM": name(start),
        "warnZoneS": name(warn_s),
        "stopZoneS": name(stop_s),
        "missedWarnReasons": {k: round(float(v), 3) for k, v in sorted(reasons.items())},
    }


def band_of(along, pol):
    if along < pol["stopM"]:
        return "STOP"
    if along < pol["warnMaxM"]:
        return "WARN"
    if along < pol["silentMaxM"]:
        return "SILENT"
    return None


def compute(session: Path, run_dir: Path) -> dict:
    cfg = load_config(run_dir)
    pol = cfg["policy"]
    frames = load_frames(session)
    g = pd.read_csv(run_dir / "guidance.csv")
    obs = pd.read_csv(run_dir / "obstacles.csv")
    t0 = g.tBlockNs.min()
    blocks = g.drop_duplicates("tBlockNs")

    # 정답 없이 되는 지표
    out: dict = {"session": session.name if session.name != "session" else session.parent.name, "scene": scene_of(session)}
    # 지도 평가(core `MapEvalWriter`, M13): 정답 구조물(벽)마다 마지막 지도의 두께·앞 치우침
    me = run_dir / "map_eval.json"
    if me.is_file():
        walls = json.loads(me.read_text(encoding="utf-8"))["final"]
        out["mapWalls"] = {w["name"]: {k: w[k] for k in ("thicknessP50M", "thicknessP90M", "frontOffsetP50M")} for w in walls}
    out.update(run_metrics(run_dir, cfg))
    arrival = frames.set_index("tNs").sysElapsedNs
    a0 = (frames.sysElapsedNs - frames.tNs) / 1e6
    pipe = (blocks.tBlockNs - blocks.poseTNs.map(arrival)) / 1e6
    out["latencyMs"] = {"a0_arcore_p50": p(a0, 50), "a_pipeline_p50": p(pipe.dropna(), 50), "a_pipeline_p95": p(pipe.dropna(), 95),
                        **out.get("latencyMs", {})}

    truth, estimated = load_truth(session, cfg)
    if truth is None:
        out["truth"] = None
        return out
    al = align(session, run_dir)
    out["truth"] = {"estimated": estimated, "n": len(truth), "align": {k: al[k] for k in ("residualRmsM", "angleUncertaintyDeg")}}

    # 블록마다 정답 머리(정답 좌표)
    pose = frames.set_index("tNs")[["tx", "ty", "tz"]]
    cam = pose.loc[blocks.poseTNs]
    cx, _, cz = to_truth(al, cam.tx.to_numpy(), cam.ty.to_numpy(), cam.tz.to_numpy())
    ox, _, oz = cfg["head"]["offsetFromCameraM"]
    tS = (blocks.tBlockNs.to_numpy() - t0) / 1e9
    # 진행 방향은 카메라 이동으로 정하고, 파지 오프셋(진행 방향 기준)도 그 방향으로 돌린다: 돌아선 뒤 머리는 카메라의 −z 쪽이 아니라 +z 쪽.
    # 창은 앱 설정이 아니라 고정 값(M18: 진행 방향 창을 바꾼 변형·기본값에서 정답이 같이 바뀌지 않게)
    h0 = json.loads(DEFAULT_CONFIG.read_text(encoding="utf-8"))["heading"]
    sgn = walk_sign(tS, cz, WALK_SIGN_WINDOW_S, h0["minTravelM"])
    hx, hz = cx + sgn * ox, cz + sgn * oz
    out["walkSignFlips"] = int((np.diff(sgn) != 0).sum())
    out["headingErrorDeg"] = heading_error(blocks.headingDeg.to_numpy(dtype=float), tS, cz, sgn, al)
    out["floorErrorM"] = floor_error(run_dir, al)
    out["config"].update({"stopM": pol["stopM"], "warnMaxM": pol["warnMaxM"]})

    # 추정 음원 → 대표점(정답 좌표) → 정답 장애물
    strat = cfg["repPoint"]["strategy"]
    obs_idx = obs.set_index(["tCaptureNs", "id"])
    rep_cols = [f"rep{strat}_x", f"rep{strat}_y", f"rep{strat}_z"]

    def match(snap_t, oid):
        try:
            r = obs_idx.loc[(int(snap_t), int(oid))]
        except KeyError:
            return None, None
        x, y, z = to_truth(al, r[rep_cols[0]], r[rep_cols[1]], r[rep_cols[2]])
        best, bd = None, MATCH_MARGIN_M
        for i, ob in enumerate(truth):
            (x0, _, z0), (x1, _, z1) = ob["min"], ob["max"]
            d = np.hypot(max(x0 - x, 0, x - x1), max(z0 - z, 0, z - z1))
            if d <= bd:
                best, bd = i, d
        return best, r["heightClass"]

    rows = []
    for k, (_, b) in enumerate(blocks.iterrows()):
        tn = [truth_nearest(ob, hx[k], hz[k], cfg, sgn[k]) for ob in truth]
        est = g[g.tBlockNs == b.tBlockNs] if len(blocks) != len(g) else g.iloc[[k]]
        e = est.iloc[0]
        mi = mh = None
        if pd.notna(e.obstacleId):
            mi, mh = match(e.snapshotTNs, e.obstacleId)
        rows.append({"t": tS[k], "state": b.state, "tn": tn, "band": e.band if pd.notna(e.band) else None,
                     "oid": int(e.obstacleId) if pd.notna(e.obstacleId) else None,
                     "az": e.azimuthDeg, "dist": e.distanceM, "match": mi, "hclass": mh, "pose": b.poseTNs,
                     "snap": int(e.snapshotTNs) if pd.notna(e.snapshotTNs) else None})

    dir_err, dir_ahead, n_beside, over, jitter_src, fa, n_cmd = [], [], 0, [], {}, 0, 0
    band_ok = band_n = 0
    first_truth_warn, first_est_warn = {}, {}
    first_warn_dist, first_stop_dist = {}, {}  # 처음 WARN·STOP을 낸 순간의 정답 거리(출발부터 경고 구간 안인 장면용, M10)
    stop_needed, stop_seen = set(), set()
    head_cls = []
    for r in rows:
        active = r["state"] in ACTIVE
        cands = [(v[0], i) for i, v in enumerate(r["tn"]) if v is not None]
        nearest = min(cands)[1] if cands else None
        truth_band = band_of(r["tn"][nearest][0], pol) if nearest is not None else None
        for i, v in enumerate(r["tn"]):
            if v is None:
                continue
            if v[0] < pol["warnMaxM"]:
                first_truth_warn.setdefault(i, r["t"])
            if v[0] < pol["stopM"] and active:
                stop_needed.add(i)
        if active:
            band_n += 1
            band_ok += (r["band"] or None) == truth_band
        if r["band"] in ("WARN", "STOP"):
            n_cmd += 1
            if r["match"] is None:
                fa += 1
            else:
                i = r["match"]
                first_est_warn.setdefault(i, r["t"])
                v = r["tn"][i]
                if v is not None:
                    first_warn_dist.setdefault(i, round(v[1], 3))
                if r["band"] == "STOP":
                    stop_seen.add(i)
                    if v is not None:
                        first_stop_dist.setdefault(i, round(v[1], 3))
                if v is not None:
                    dir_err.append(abs(r["az"] - v[2]))
                    if v[0] > 0:  # 정답 최근접점이 머리 앞(M12.2): 옆·뒤(통로 뒤 여유 안)는 카메라가 못 보는 곳이라 방향 판정에서 뺀다
                        dir_ahead.append(dir_err[-1])
                    else:
                        n_beside += 1
                    if OVER_LO_M <= v[1] <= pol["warnMaxM"]:
                        over.append(max(0.0, (r["dist"] - v[1]) / v[1]))
                jitter_src.setdefault(i, {})[r["pose"]] = r["az"]
                if truth[i]["type"] == "HEAD":
                    head_cls.append(r["hclass"] == "HEAD")

    def jitter_std():
        diffs = []
        for d in jitter_src.values():
            a = np.array(list(d.values()))
            if len(a) > 2:
                diffs.extend(np.diff(a))
        return float(np.std(diffs)) if diffs else None

    objects = {i for i, ob in enumerate(truth) if ob["kind"] == "object"}  # 구조물은 탐지율 분모에서 뺀다(IMPROVE_SPEC §9.1)
    types = sorted({truth[i]["type"] for i in objects})
    entered = set(first_truth_warn)
    n_struct = sum(1 for r in rows if r["match"] is not None and r["match"] not in objects and r["band"] in ("WARN", "STOP"))
    out.update({
        "directionErrorDeg": {"p50": p(dir_err, 50), "p95": p(dir_err, 95), "n": len(dir_err)},
        "directionErrorAheadDeg": {"p50": p(dir_ahead, 50), "p95": p(dir_ahead, 95), "n": len(dir_ahead)},
        "besideFraction": n_beside / len(dir_err) if dir_err else None,  # 방향을 잰 경고 중 정답 최근접점이 머리 옆·뒤인 비율
        "overestimate1p5to2p5": {"p95": p(over, 95), "n": len(over)},
        "bandAgreement": band_ok / band_n if band_n else None,
        "detectionRateByType": {
            t: (sum(1 for i in entered & objects if truth[i]["type"] == t and i in first_est_warn) /
                max(1, sum(1 for i in entered & objects if truth[i]["type"] == t)))
            for t in types
        },
        "headClassAccuracy": (sum(head_cls) / len(head_cls)) if head_cls else None,
        "warnTimingErrorS": {truth[i]["name"]: round(first_est_warn[i] - first_truth_warn[i], 3) if i in first_est_warn else None
                             for i in sorted(entered)},
        "firstWarnDistanceM": {truth[i]["name"]: first_warn_dist.get(i) for i in sorted(entered)},
        "firstStopDistanceM": {truth[i]["name"]: first_stop_dist.get(i) for i in sorted(entered)},
        "missedStop": len(stop_needed - stop_seen),
        "sourceJitterDegStd": jitter_std(),
        "falseAlarmFraction": fa / n_cmd if n_cmd else None,
        # id 전환(명세 §9.2, C3b·T1 평가): 정답 물체마다 그 물체로 짝지어진 경고(WARN·STOP)의 서로 다른 추적 id 수 − 1의 합
        "idSwitches": id_switches(rows, objects),
        "structureCommandFraction": n_struct / n_cmd if n_cmd else None,  # 구조물을 물체처럼 경고한 비율
        "objectMergedFraction": merged_fraction(obs, truth, al),
        "objectShape": object_shape(obs, truth, al),
        **zone_stats(rows, truth, pol, miss_reason(run_dir)),
        "selectedTruth": {truth[i]["name"]: sum(1 for r in rows if r["match"] == i and r["band"] in ("WARN", "STOP")) for i in range(len(truth))},
    })
    return out


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    if sys.argv[1] == "--run":
        run = Path(sys.argv[2])
        m = run_metrics(run, load_config(run))
    else:
        session, run = Path(sys.argv[1]), Path(sys.argv[2])
        m = compute(session, run)
    (run / "metrics.json").write_text(json.dumps(m, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(m, indent=2, ensure_ascii=False))
