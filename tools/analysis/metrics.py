"""지표 (MVP_SPEC §10.2, M8). 기준값은 가설.

    python metrics.py <세션 폴더> <실행 로그 폴더>   → <실행 로그 폴더>/metrics.json
    python metrics.py --run <실행 로그 폴더>          → 세션 없이 실행 로그만(사용자 모드 T01 등, M10)

- 정답: <세션>/annotations/obstacles.json (정답 좌표, docs/FORMAT.md). 없으면 정답이 필요 없는 지표만.
  `"scene"`이 있으면 장면 ID로 쓴다(폴더 이름의 장면 ID가 틀린 녹화, M10).
- 실행 로그: 같은 세션의 PC 오프라인 재생(`:core:replay`) 또는 그 세션을 녹화하며 돌린 실시간 로그.
  ARCore 재생 모드 로그는 월드 좌표가 녹화와 달라(F8) 정답 지표를 낼 수 없다.
- 정답 머리 = 카메라 + 파지 오프셋(보행선 방향 기준), 정답 방향 = 보행선(+z). 정답 통로 = 머리에서 보행선 방향 폭·길이.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from align import align, load_config, load_frames, to_truth

MATCH_MARGIN_M = 0.3  # 추정 대표점이 정답 상자에서 이만큼 안이면 그 장애물로 본다(분석 도구 파라미터)
MERGE_BEYOND_M = 0.5  # 정답 물체와 겹치는 추정 물체가 그 물체 뒤로 이만큼 넘게 이어지면 합쳐짐(13번 계획서 표 5, M13.1)
ACTIVE = {"NORMAL", "DEGRADED"}


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


def merged_fraction(obs: pd.DataFrame, truth: list[dict], al: dict) -> float | None:
    """물체–구조물 합쳐짐 비율: 정답 물체(`kind: object`)와 바닥 면적이 겹치는 추정 물체가 있는 느린 경로 단계 중, 그 추정
    물체가 물체 뒷면(정답 +z) 너머로 MERGE_BEYOND_M 넘게 이어진 단계의 비율(막이 캐리어를 뒤 문·벽까지 이은 경우)."""
    objs = [o for o in truth if o["kind"] == "object"]
    if not objs or obs.empty:
        return None
    tx, tz = [], []
    for cx, cz in (("aabbMinX", "aabbMinZ"), ("aabbMinX", "aabbMaxZ"), ("aabbMaxX", "aabbMinZ"), ("aabbMaxX", "aabbMaxZ")):
        x, _, z = to_truth(al, obs[cx].to_numpy(), np.zeros(len(obs)), obs[cz].to_numpy())
        tx.append(x)
        tz.append(z)
    x0, x1, z0, z1 = np.min(tx, 0), np.max(tx, 0), np.min(tz, 0), np.max(tz, 0)
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


def truth_nearest(ob, hx, hz, cfg):
    """정답 통로(머리 기준 보행선 방향) 안 최근접점 → (along, 수평거리, 방위각) 또는 None."""
    c = cfg["corridor"]
    (x0, y0, z0), (x1, _, z1) = ob["min"], ob["max"]
    if y0 >= c["heightM"]:
        return None
    lo, hi = max(x0, hx - c["widthM"] / 2), min(x1, hx + c["widthM"] / 2)
    if lo > hi:
        return None
    zn = max(z0, hz - c["behindM"])
    if zn > z1 or zn - hz > c["lengthM"]:
        return None
    xn = min(max(hx, lo), hi)
    along = zn - hz
    return along, float(np.hypot(xn - hx, along)), float(np.degrees(np.arctan2(xn - hx, along)))


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
    hx, hz = cx + ox, cz + oz
    tS = (blocks.tBlockNs.to_numpy() - t0) / 1e9

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
        tn = [truth_nearest(ob, hx[k], hz[k], cfg) for ob in truth]
        est = g[g.tBlockNs == b.tBlockNs] if len(blocks) != len(g) else g.iloc[[k]]
        e = est.iloc[0]
        mi = mh = None
        if pd.notna(e.obstacleId):
            mi, mh = match(e.snapshotTNs, e.obstacleId)
        rows.append({"t": tS[k], "state": b.state, "tn": tn, "band": e.band if pd.notna(e.band) else None,
                     "az": e.azimuthDeg, "dist": e.distanceM, "match": mi, "hclass": mh, "pose": b.poseTNs})

    dir_err, over, jitter_src, fa, n_cmd = [], [], {}, 0, 0
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
                    if 1.5 <= v[1] <= 2.5:
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
        "structureCommandFraction": n_struct / n_cmd if n_cmd else None,  # 구조물을 물체처럼 경고한 비율
        "objectMergedFraction": merged_fraction(obs, truth, al),
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
