"""M48 움직이는 장애물의 값 재기와 늦음 분해(IMPROVE_SPEC §13 M48, 설계 5.2절). 값은 ① 결과를 보기 전에 재서 DECISIONS.md에 적는다.

    python motion_stats.py accel <세션>...            → a_M: 움직이는 물체 클릭의 지난 1 s 맞춤 속도, 0.5 s 간격 변화율 p95(m/s²)
    python motion_stats.py sigma <재생 폴더>...        → σ_m(Z): 이번 장 대표점(motion.csv measX·measZ)의 지난 1 s 중앙값 둘레 흩어짐
                                                       (1.4826 × MAD)을 카메라 거리 0.5 m 구간별로, σ0 + k·Z² 맞춤
    python motion_stats.py vmin <재생 폴더>...         → v_min: χ² 검정(설정 movingChi2)을 통과한 갱신의 속도 크기 p99
    python motion_stats.py lag <세션> <재생 폴더>      → 다가오는 사람에게 안내한 거리의 늦음 분해(평활·지도·정보 나이, 설계 표 1)
    python motion_stats.py ray <재생 폴더>...          → M52 같은 광선 분해(설계 3.1절): P1 D_a, P2 r_front, P3 측정점 − 몸통 깊이, P4 τ
                                                       (재생 폴더는 움직임 정답 판정 재생, 세션은 replay_info.json에서. 여럿이면 합친 값도)

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
from spike_check import quat_to_rot
from worldpts import depth_k, load_depth_m

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
    나이 = 정답(촬영 시각) − 정답(블록 시각). 진행 방향은 정답 진행 방향. 정답 앞면 = 클릭 점 − event_eval.FRONT_OFFSET_M(M52)."""
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
        a_b, a_c = along(rb[0]) - E.FRONT_OFFSET_M, along(rc[0]) - E.FRONT_OFFSET_M  # 앞면(M52), 옆 방향은 반지름
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


RAY_BAND_M = 0.3  # P1: |e(몸통 평활)| 한도(설계 그림 1의 두 무리 사이, 자체 선택)
RAY_FRAC = 0.8  # P1: 그 거리부터 이 비율 이상의 장이 한도 안
RAY_DROP_M = 0.5  # D_a 안에서 이보다 크게 어긋난 장은 몸통 화소가 팔·가장자리·배경을 읽은 것으로 보고 뺀다
RAY_BLOCK_S = 0.5  # 부트스트랩 묶음(깊이 장은 시간에 따라 서로 닮음)
RAY_BOOT = 2000
FLIP = np.diag([1.0, -1.0, -1.0])  # GL 카메라 ↔ C_cv


def _ray_samples(session: Path, source: str) -> pd.DataFrame:
    """깊이 장마다 정답 몸통 화소(클릭 위치 보간을 허리 면 높이에서 투영, 3×3 유효 깊이 중앙값)의 깊이와 클릭 점을 같은 광선 위에서 비교.
    e = 깊이 점 수평 거리 − 클릭 점 수평 거리(같은 광선이라 e = hd·(z_d/z_t − 1)). 자세는 깊이 시각에 가장 가까운 도착 이전 행(core
    `SessionReader.nearestPastPose`와 같음). 정답은 알고리즘이 아니라 앞뒤 클릭을 모두 쓴다(명세 §9.5)."""
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = load_frames(session).reset_index(drop=True)
    ev = E.load_events(session)
    objs = [o for o in (ev or {}).get("objects", []) if o["kind"] == "moving" and o.get("added") != "check"]
    if not objs:
        return pd.DataFrame()
    o = objs[0]
    tr = E.moving_track(o)
    waist_y = float(np.median([c["floorY"] for c in o["clicks"]])) + float(o["heightM"])
    col_t, col_f = ("depthTNs", "depthFile") if source == "SMOOTHED" else ("rawDepthTNs", "rawDepthFile")
    ft = fr.tNs.to_numpy()
    rows, last = [], -1
    for i, row in fr.iterrows():
        if not isinstance(row[col_f], str) or row.tracking != "TRACKING" or row[col_t] <= last:
            continue
        last = row[col_t]
        tn = int(row[col_t])
        j = int(np.argmin(np.abs(ft[:i + 1] - tn)))  # 도착 이전 행 중 깊이 시각에 가장 가까운 자세
        pr = fr.iloc[j]
        r = tr(tn / 1e9)
        if r is None:
            continue
        pos, vel = r
        cam = np.array([pr.tx, pr.ty, pr.tz])
        rot = quat_to_rot(np.array([[pr.qx, pr.qy, pr.qz, pr.qw]]))[0] @ FLIP
        p_w = np.array([pos[0], waist_y, pos[1]])
        pc = rot.T @ (p_w - cam)
        if pc[2] <= 0.1:
            continue
        d = load_depth_m(session / row[col_f])
        k = depth_k(meta, d.shape[1], d.shape[0])
        iu, iv = int(round(k["fx"] * pc[0] / pc[2] + k["cx"])), int(round(k["fy"] * pc[1] / pc[2] + k["cy"]))
        if not (0 <= iu < d.shape[1] and 0 <= iv < d.shape[0]):
            continue
        w = d[max(iv - 1, 0):iv + 2, max(iu - 1, 0):iu + 2]
        w = w[w > 0]
        if w.size == 0:
            continue
        hd = float(np.hypot(p_w[0] - cam[0], p_w[2] - cam[2]))
        dxz = np.array([p_w[0] - cam[0], p_w[2] - cam[2]]) / hd
        vc = float(-(vel @ dxz)) if np.all(np.isfinite(vel)) else np.nan
        rows.append(dict(tNs=tn, t=tn / 1e9, hd=hd, e=hd * (float(np.median(w)) / pc[2] - 1), vc=vc, camX=cam[0], camZ=cam[2],
                         posX=pos[0], posZ=pos[1]))
    return pd.DataFrame(rows)


def _appear(sm: pd.DataFrame) -> tuple[float, float] | None:
    """P1: 그 장부터 끝까지 RAY_FRAC 이상의 장이 |e| ≤ RAY_BAND_M인 장 중 클릭 점이 가장 먼 장의 (거리, 시각)."""
    if sm.empty:
        return None
    sm = sm.sort_values("t")
    ok = (sm.e.abs() <= RAY_BAND_M).to_numpy()
    tail = np.cumsum(ok[::-1])[::-1] / np.arange(len(ok), 0, -1)  # 그 장부터 끝까지 비율
    cand = sm[tail >= RAY_FRAC]
    if cand.empty:
        return None
    best = cand.loc[cand.hd.idxmax()]
    return float(best.hd), float(best.t)


def _boot(groups: list[list[np.ndarray]], stat, n: int = RAY_BOOT, seed: int = 0):
    """세션별 묶음 목록 [[묶음 표본, ...], ...]을 세션 안에서 다시 뽑아 stat(합친 표본 목록)의 95% 구간."""
    rng = np.random.default_rng(seed)
    out = []
    for _ in range(n):
        pick = [g[i] for g in groups if g for i in rng.integers(0, len(g), len(g))]
        v = stat(pick)
        if v is not None and np.isfinite(v):
            out.append(v)
    return [float(np.percentile(out, 2.5)), float(np.percentile(out, 97.5))] if out else None


def _blocks(df: pd.DataFrame, t0: float, cols: list[str]) -> list[np.ndarray]:
    b = np.floor((df.t - t0) / RAY_BLOCK_S).astype(int)
    return [df.loc[b == k, cols].to_numpy() for k in sorted(b.unique())]


def _slope(pick: list[np.ndarray]):
    """묶음마다 [vc 평균, e(평활) − e(원시) 중앙값 차] → 기울기(s)."""
    a = np.array([p[0] for p in pick if p is not None and np.all(np.isfinite(p))])
    if len(a) < 3 or np.ptp(a[:, 0]) < 0.05:
        return None
    return float(np.polyfit(a[:, 0], a[:, 1], 1)[0])


def ray(runs: list[Path]) -> dict:
    per, g_front, g_p3, g_lag, keep = {}, [], [], [], {}
    for run in runs:
        info = json.loads((run / "replay_info.json").read_text(encoding="utf-8"))
        session = Path(info["session"])
        sm, rw = _ray_samples(session, "SMOOTHED"), _ray_samples(session, "RAW")
        a, a_raw = _appear(sm), _appear(rw)
        res = {"run": str(run), "nSmoothed": int(len(sm)), "nRaw": int(len(rw)), "DaM": a[0] if a else None,
               "DaRawM": a_raw[0] if a_raw else None}
        if a is None:
            per[session.name] = res
            continue
        t_a = a[1]
        sm_in, rw_in = sm[sm.t >= t_a], rw[rw.t >= t_a]
        drop_s, drop_r = sm_in.e.abs() > RAY_DROP_M, rw_in.e.abs() > RAY_DROP_M
        sm_in, rw_in = sm_in[~drop_s].copy(), rw_in[~drop_r].copy()
        res.update(nIn=int(len(sm_in)), nInRaw=int(len(rw_in)), dropped=int(drop_s.sum()), droppedRaw=int(drop_r.sum()),
                   vcRange=[float(sm_in.vc.min()), float(sm_in.vc.max())] if sm_in.vc.notna().any() else None)
        # P2: −중앙값 e(원시)
        fb = _blocks(rw_in, t_a, ["e"])
        g_front.append(fb)
        res["rFrontM"] = -float(rw_in.e.median()) if len(rw_in) else None
        res["rFrontCI"] = (lambda c: [-c[1], -c[0]] if c else None)(_boot([fb], lambda p: float(np.median(np.concatenate(p)))))
        # P3: 측정점 − 몸통 깊이(같은 시각, 움직이는 track 중 정답에 가장 가까운 것). 몸통 깊이와 짝짓는 장은 재생의 depth.source 장
        # (평활 재생 = 평활 장, 원시 재생 = 원시 장). 창은 둘 다 평활 D_a 시각부터(같은 구간 비교)
        m = _motion(run)
        if m is not None:
            src = load_config(run)["depth"]["source"]
            base = rw_in.copy() if src == "RAW" else sm_in
            res["measSource"] = src
            mv = m[(m.moving == True) & m.measX.notna()]  # noqa: E712 (csv의 bool 열)
            mm, kk = [], []
            for _, s in base.iterrows():
                c = mv[mv.tCaptureNs == s.tNs]
                if c.empty:
                    mm.append(np.nan)
                    kk.append(np.nan)
                    continue
                dpos = np.hypot(c.measX - s.posX, c.measZ - s.posZ)
                q = c.loc[dpos.idxmin()]
                mm.append(float(np.hypot(q.measX - s.camX, q.measZ - s.camZ)))
                # 칼만 위치(촬영 시각, 안내가 쓰는 값): 평활 뒤처짐 보정(track.depthLagMs)은 측정점이 아니라 이 값에 나타난다
                kk.append(float(np.hypot(q.posX - s.camX, q.posZ - s.camZ)) if np.isfinite(q.posX) else np.nan)
            base["meas"] = mm
            base["kal"] = kk
            base["p3"] = base.meas - (base.hd + base.e)
            base["mt"] = base.meas - base.hd  # 측정점 − 클릭 점(앞면 오프셋을 빼기 전)
            base["kt"] = base.kal - base.hd  # 칼만 위치 − 클릭 점
            p3 = base.dropna(subset=["p3"])
            pb = _blocks(p3, t_a, ["p3"])
            g_p3.append(pb)
            res.update(nMeas=int(len(p3)), p3M=float(p3.p3.median()) if len(p3) else None,
                       p3CI=_boot([pb], lambda p: float(np.median(np.concatenate(p)))) if pb else None)
            keep[session.name] = p3
            # 완료 기준 3: 사람의 움직이는 track이 처음 출력될 때(측정 표 ≥ map.minHits, 점수 ≥ minScore) |칼만 속도| 대 정답 속도
            mc = load_config(run)["map"]
            tr = E.moving_track([o for o in E.load_events(session)["objects"] if o["kind"] == "moving" and o.get("added") != "check"][0])
            for _, q in m[(m.moving == True) & (m.votes >= mc["minHits"]) & (m.score >= mc["minScore"]) & m.velX.notna()].iterrows():  # noqa: E712
                r = tr(q.tCaptureNs / 1e9)
                if r is None or not np.all(np.isfinite(r[1])) or np.hypot(q.posX - r[0][0], q.posZ - r[0][1]) > 0.6:
                    continue
                # D_a 앞(깊이에 사람이 아직 없을 때) 시작한 출력은 판정하지 않는다(설계 표 8: 기준 1~3은 D_a 안의 장으로)
                res["firstOutput"] = {"tS": float(q.tCaptureNs / 1e9 - sm.t.iloc[0]), "afterDa": bool(q.tCaptureNs / 1e9 >= t_a),
                                      "speedMps": float(np.hypot(q.velX, q.velZ)), "truthMps": float(np.linalg.norm(r[1])), "votes": float(q.votes)}
                break
            res["tDaS"] = float(t_a - sm.t.iloc[0])
        # P4: 묶음마다 e(평활) − e(원시) 중앙값 차 대 다가오는 속도
        lb = []
        bs = np.floor((sm_in.t - t_a) / RAY_BLOCK_S).astype(int)
        br = np.floor((rw_in.t - t_a) / RAY_BLOCK_S).astype(int)
        for k in sorted(set(bs) & set(br)):
            vs = sm_in.vc[bs == k]
            if vs.notna().any():
                lb.append(np.array([[float(vs.mean()), float(sm_in.e[bs == k].median() - rw_in.e[br == k].median())]]))
        g_lag.append(lb)
        res["lagBlocks"] = len(lb)
        res["lagS"] = _slope(lb)
        res["lagCI"] = _boot([lb], _slope)
        res["smMinusRawM"] = float(np.median([b[0, 1] for b in lb])) if lb else None
        per[session.name] = res
    pooled = {
        "rFrontM": -float(np.median(np.concatenate([np.concatenate(g) for g in g_front if g]))) if any(g_front) else None,
        "rFrontCI": (lambda c: [-c[1], -c[0]] if c else None)(_boot(g_front, lambda p: float(np.median(np.concatenate(p))))),
        "lagS": _slope([b for g in g_lag for b in g]), "lagCI": _boot(g_lag, _slope),
    }
    r_front = pooled["rFrontM"]
    for name, p3 in keep.items():
        if r_front is not None and len(p3):
            c2 = p3.mt + r_front  # 측정점 − (클릭 점 − r_front)
            per[name]["crit2M"] = float(c2.median())
            k2 = (p3.kt + r_front).dropna()  # 칼만 위치(촬영 시각) − 새 정답 앞면: 완료 기준 2
            per[name]["crit2KalmanM"] = float(k2.median()) if len(k2) else None
    return {"sessions": per, "pooled": pooled, "rule": {"bandM": RAY_BAND_M, "frac": RAY_FRAC, "dropM": RAY_DROP_M, "blockS": RAY_BLOCK_S}}


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
    elif cmd == "ray":
        res = ray(rest)
    else:
        sys.exit(f"모르는 명령 {cmd}")
    print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main(sys.argv[1:])
