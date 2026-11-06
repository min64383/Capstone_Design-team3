"""M48 움직이는 장애물의 값 재기와 늦음 분해(IMPROVE_SPEC §13 M48, 설계 5.2절). 값은 ① 결과를 보기 전에 재서 DECISIONS.md에 적는다.

    python motion_stats.py accel <세션>...            → a_M: 움직이는 물체 클릭의 지난 1 s 맞춤 속도, 0.5 s 간격 변화율 p95(m/s²)
    python motion_stats.py sigma <재생 폴더>...        → σ_m(Z): 이번 장 대표점(motion.csv measX·measZ)의 지난 1 s 중앙값 둘레 흩어짐
                                                       (1.4826 × MAD)을 카메라 거리 0.5 m 구간별로, σ0 + k·Z² 맞춤
    python motion_stats.py vmin <재생 폴더>...         → v_min: χ² 검정(설정 movingChi2)을 통과한 갱신의 속도 크기 p99
    python motion_stats.py lag <세션> <재생 폴더>      → 다가오는 사람에게 안내한 거리의 늦음 분해(평활·지도·정보 나이, 설계 표 1)

sigma·vmin의 재생 폴더는 정지 장면(실내 물체 세션, T 받침대 세션)이어야 한다: 그 안의 모든 track을 정지 물체로 본다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

import event_eval as E
from metrics import load_config, load_frames

VEL_STEP_S = 0.25  # 속도를 읽는 시각 간격
ACCEL_DT_S = 0.5  # 변화율 간격(설계 5.2절)
MEDIAN_WIN_S = 1.0  # 대표점 흐름을 빼는 지난 창
Z_BIN_M = 0.5


def accel(sessions: list[Path]) -> dict:
    acc = []
    for s in sessions:
        ev = E.load_events(s)
        if ev is None:
            continue
        for o in ev["objects"]:
            if o["kind"] != "moving" or o.get("added") == "check":
                continue
            tr = E.moving_track(o)
            ts = sorted({c["tNs"] for c in E.clicks_of(o)})
            if len(ts) < 4:
                continue
            t0, t1 = ts[0] / 1e9 + E.VEL_PAST_S, ts[-1] / 1e9
            grid = np.arange(t0, t1, VEL_STEP_S)
            v = {}
            for t in grid:
                r = tr(t)
                if r is not None and np.all(np.isfinite(r[1])):
                    v[round(t, 3)] = r[1]
            for t, vt in v.items():
                tp = round(t - ACCEL_DT_S, 3)
                if tp in v:
                    acc.append(float(np.linalg.norm(vt - v[tp]) / ACCEL_DT_S))
    return {"n": len(acc), "p50": float(np.percentile(acc, 50)) if acc else None,
            "p95": float(np.percentile(acc, 95)) if acc else None, "aM": float(np.percentile(acc, 95)) if acc else None}


def _motion(run: Path) -> pd.DataFrame | None:
    f = run / "motion.csv"
    return pd.read_csv(f) if f.is_file() else None


def _cam_at(run: Path):
    info = json.loads((run / "replay_info.json").read_text(encoding="utf-8"))
    fr = load_frames(Path(info["session"]))
    ft, fx, fy, fz = fr.tNs.to_numpy(), fr.tx.to_numpy(), fr.ty.to_numpy(), fr.tz.to_numpy()
    return lambda t: np.stack([np.interp(t, ft, fx), np.interp(t, ft, fy), np.interp(t, ft, fz)], axis=-1)


def sigma(runs: list[Path]) -> dict:
    z_all, r_all = [], []
    for run in runs:
        m = _motion(run)
        if m is None:
            continue
        cam = _cam_at(run)
        m = m.dropna(subset=["measX", "measZ"])
        for _, g in m.groupby("id"):
            if len(g) < 10:
                continue
            t = g.tCaptureNs.to_numpy() / 1e9
            xz = g[["measX", "measZ"]].to_numpy()
            c = cam(g.tCaptureNs.to_numpy())
            for k in range(len(t)):
                w = (t >= t[k] - MEDIAN_WIN_S) & (t <= t[k])
                if w.sum() < 5:
                    continue
                med = np.median(xz[w], axis=0)
                zk = float(np.hypot(xz[k, 0] - c[k, 0], xz[k, 1] - c[k, 2]))
                for ax in (0, 1):  # 축마다 잔차(칼만은 축마다 같은 σ_m을 쓴다)
                    r_all.append(abs(xz[k, ax] - med[ax]))
                    z_all.append(zk)
    z_all, r_all = np.array(z_all), np.array(r_all)
    bins = []
    for lo in np.arange(0.5, 4.0, Z_BIN_M):
        sel = (z_all >= lo) & (z_all < lo + Z_BIN_M)
        if sel.sum() < 60:
            continue
        # 축마다 중앙값 둘레 잔차의 MAD: |r| 자체가 중앙값에서 잰 편차라 σ = 1.4826 × median(|r|)
        bins.append((lo + Z_BIN_M / 2, 1.4826 * float(np.median(r_all[sel])), int(sel.sum())))
    if len(bins) >= 2:
        zc = np.array([b[0] for b in bins])
        sc = np.array([b[1] for b in bins])
        wts = np.sqrt([b[2] for b in bins])
        A = np.stack([np.ones_like(zc), zc * zc], axis=1)
        coef, *_ = np.linalg.lstsq(A * wts[:, None], sc * wts, rcond=None)
        s0, k = float(coef[0]), float(coef[1])
    else:
        s0 = k = None
    return {"bins": [{"zM": round(b[0], 2), "sigmaM": round(b[1], 4), "n": b[2]} for b in bins], "sigma0M": s0, "sigmaPerM2": k, "n": int(len(r_all) // 2)}


def vmin(runs: list[Path]) -> dict:
    sp = []
    chi2_thr = None
    for run in runs:
        m = _motion(run)
        if m is None:
            continue
        chi2_thr = load_config(run)["track"]["movingChi2"]
        g = m.dropna(subset=["velX", "velZ"])
        g = g[g.chi2 > chi2_thr]
        sp += np.hypot(g.velX, g.velZ).tolist()
    total = sum(len(_motion(r).dropna(subset=["velX"])) for r in runs if _motion(r) is not None)
    return {"nPassChi2": len(sp), "nUpdates": total, "chi2": chi2_thr,
            "p50": float(np.percentile(sp, 50)) if sp else None, "p99": float(np.percentile(sp, 99)) if sp else None,
            "vMin": float(np.percentile(sp, 99)) if sp else 0.0}


def lag(session: Path, run: Path) -> dict:
    """설계 표 1과 같은 분해: 경고 블록 중 사람이 통로 안 2.95 m 안에서 0.3 m/s 넘게 다가오는 블록의 중앙값.
    전체 = 앱 대표점(블록 시각 머리) − 정답(블록 시각), 평활 = 추적 대표점 − 그 장 원시 군집 대표점, 지도 = 원시 − 정답(촬영 시각),
    나이 = 정답(촬영 시각) − 정답(블록 시각). 진행 방향은 정답 진행 방향."""
    cfg = load_config(run)
    ox, _, oz = cfg["head"]["offsetFromCameraM"]
    cor = cfg["corridor"]
    fr = load_frames(session)
    ft, fx, fz = fr.tNs.to_numpy() / 1e9, fr.tx.to_numpy(), fr.tz.to_numpy()

    def cam(ts):
        return np.array([np.interp(ts, ft, fx), np.interp(ts, ft, fz)])
    qx, qy, qz, qw = (fr[k].to_numpy() for k in ("qx", "qy", "qz", "qw"))
    fwd = np.stack([-2 * (qx * qz + qw * qy), -(1 - 2 * (qx * qx + qy * qy))], axis=1)
    fwd /= np.linalg.norm(fwd, axis=1, keepdims=True)
    ev = E.load_events(session)
    objs = [o for o in ev["objects"] if o["kind"] == "moving" and o.get("added") != "check"]
    if not objs:
        return {}
    o = objs[0]
    tr, rad = E.moving_track(o), o["radiusM"]
    g = pd.read_csv(run / "guidance.csv").drop_duplicates("tBlockNs")
    g = g[g.band.isin(["WARN", "STOP"])]
    obs = pd.read_csv(run / "obstacles.csv")
    cd = pd.read_csv(run / "cluster_debug.csv")
    # 움직이는 물체만 있는 세션은 정지 지도 군집이 없어 머리줄만 있다(M48 분해: 그 물체의 점은 정지 지도로 가지 않음).
    # 빈 열은 object 형이라 ~가 열 선택이 되므로 bool로 바꾼다
    cd = cd[~cd.filtered.astype(bool)]
    rows = []
    for _, b in g.iterrows():
        tb, tc = b.tBlockNs / 1e9, b.snapshotTNs / 1e9
        d = cam(tb + .5) - cam(tb - .5)
        f = d / np.linalg.norm(d) if np.linalg.norm(d) > .25 else fwd[min(np.searchsorted(ft, tb), len(ft) - 1)]
        head = cam(tb) + np.array([-f[1], f[0]]) * ox + f * oz
        rb, rc = tr(tb), tr(tc)
        if rb is None or rc is None or not np.all(np.isfinite(rb[1])):
            continue
        along = lambda p: float((p - head) @ f)
        lat = lambda p: float((p - head) @ np.array([-f[1], f[0]]))
        a_b, a_c = along(rb[0]) - rad, along(rc[0]) - rad
        if abs(lat(rb[0])) - rad > cor["widthM"] / 2 or a_b > 2.95:
            continue
        ob = obs[(obs.tCaptureNs == b.snapshotTNs) & (obs.id == b.obstacleId)]
        if ob.empty:
            continue
        app = np.array([ob.repCORRIDOR_BAND_x.iloc[0], ob.repCORRIDOR_BAND_z.iloc[0]])
        closing = float(-((rb[1] - (cam(tb + .5) - cam(tb - .5))) @ f))
        # heard = 안내가 실제로 쓴 거리(움직이는 물체는 블록 시각 외삽·kσ를 뺀 값, 정지 물체는 대표점 거리) − 실제
        row = dict(total=along(app) - a_b, heard=float(b.distanceM) - a_b, age=a_c - a_b, closing=closing)
        c = cd[cd.tCaptureNs == b.snapshotTNs]
        if not c.empty:
            raws = c[["corridorBandX", "corridorBandZ"]].to_numpy()
            raw = raws[np.argmin(np.linalg.norm(raws - app, axis=1))]
            row.update(ema=along(app) - along(raw), map=along(raw) - a_c)
        rows.append(row)
    r = pd.DataFrame(rows)
    if r.empty:
        return {}
    mv = r[r.closing > 0.3]
    return {"blocks": int(len(mv)), "closingMps": float(mv.closing.median()) if len(mv) else None,
            **{k: (float(mv[k].median()) if k in mv and mv[k].notna().any() else None) for k in ("total", "heard", "map", "age", "ema")}}


def main(args: list[str]) -> None:
    if len(args) < 2:
        print(__doc__)
        sys.exit(2)
    cmd, rest = args[0], [Path(a) for a in args[1:]]
    if cmd == "accel":
        res = accel(rest)
    elif cmd == "sigma":
        res = sigma(rest)
    elif cmd == "vmin":
        res = vmin(rest)
    elif cmd == "lag":
        res = lag(rest[0], rest[1])
    else:
        sys.exit(f"모르는 명령 {cmd}")
    print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main(sys.argv[1:])
