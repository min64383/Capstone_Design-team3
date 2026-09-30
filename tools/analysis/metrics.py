"""지표 (MVP_SPEC §10.2, M8). 기준값은 가설.

    python metrics.py <세션 폴더> <실행 로그 폴더>   → <실행 로그 폴더>/metrics.json

- 정답: <세션>/annotations/obstacles.json (정답 좌표, docs/FORMAT.md). 없으면 정답이 필요 없는 지표만.
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
ACTIVE = {"NORMAL", "DEGRADED"}


def p(v, q):
    v = np.asarray(v, dtype=float)
    return float(np.percentile(v, q)) if v.size else None


def load_truth(session: Path):
    f = session / "annotations/obstacles.json"
    if not f.is_file():
        return None, None
    j = json.loads(f.read_text(encoding="utf-8"))
    return j["obstacles"], bool(j.get("estimated", False))


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
    sp = pd.read_csv(run_dir / "slow_path.csv")
    obs = pd.read_csv(run_dir / "obstacles.csv")
    t0 = g.tBlockNs.min()
    out: dict = {"session": session.name, "run": str(run_dir), "config": {"repStrategy": cfg["repPoint"]["strategy"]}}

    # 정답 없이 되는 지표
    blocks = g.drop_duplicates("tBlockNs")
    out["stateFraction"] = blocks.state.value_counts(normalize=True).round(4).to_dict()
    dur = (sp.tCaptureNs.max() - sp.tCaptureNs.min()) / 1e9
    out["slowPathHz"] = float(len(sp) / dur) if dur > 0 else None
    age = g.infoAgeMs.dropna()
    out["infoAgeMs"] = {"p50": p(age, 50), "p95": p(age, 95), "max": float(age.max()) if len(age) else None}
    arrival = frames.set_index("tNs").sysElapsedNs
    a0 = (frames.sysElapsedNs - frames.tNs) / 1e6
    pipe = (blocks.tBlockNs - blocks.poseTNs.map(arrival)) / 1e6
    out["latencyMs"] = {"a0_arcore_p50": p(a0, 50), "a_pipeline_p50": p(pipe.dropna(), 50), "a_pipeline_p95": p(pipe.dropna(), 95)}
    dev = run_dir / "device.csv"
    if dev.is_file():
        lat = pd.read_csv(dev).audioOutputLatencyMs.dropna()
        out["latencyMs"]["b_output_p50"] = p(lat, 50)
    prev = blocks.state.shift()
    out["unknownTransitions"] = int(((blocks.state == "UNKNOWN") & (prev != "UNKNOWN") & prev.notna()).sum())

    truth, estimated = load_truth(session)
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
                if r["band"] == "STOP":
                    stop_seen.add(i)
                v = r["tn"][i]
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

    types = sorted({ob["type"] for ob in truth})
    entered = set(first_truth_warn)
    out.update({
        "directionErrorDeg": {"p50": p(dir_err, 50), "p95": p(dir_err, 95), "n": len(dir_err)},
        "overestimate1p5to2p5": {"p95": p(over, 95), "n": len(over)},
        "bandAgreement": band_ok / band_n if band_n else None,
        "detectionRateByType": {
            t: (sum(1 for i in entered if truth[i]["type"] == t and i in first_est_warn) /
                max(1, sum(1 for i in entered if truth[i]["type"] == t)))
            for t in types
        },
        "headClassAccuracy": (sum(head_cls) / len(head_cls)) if head_cls else None,
        "warnTimingErrorS": {truth[i]["name"]: round(first_est_warn[i] - first_truth_warn[i], 3) if i in first_est_warn else None
                             for i in sorted(entered)},
        "missedStop": len(stop_needed - stop_seen),
        "sourceJitterDegStd": jitter_std(),
        "falseAlarmFraction": fa / n_cmd if n_cmd else None,
        "selectedTruth": {truth[i]["name"]: sum(1 for r in rows if r["match"] == i and r["band"] in ("WARN", "STOP")) for i in range(len(truth))},
    })
    return out


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    session, run = Path(sys.argv[1]), Path(sys.argv[2])
    m = compute(session, run)
    (run / "metrics.json").write_text(json.dumps(m, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(m, indent=2, ensure_ascii=False))
