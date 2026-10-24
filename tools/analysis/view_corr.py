"""시점 간 재현·상관(M39 설계 3.1절): 원시 깊이에서 헛 칸(정답 빈 공간)과 표면 칸이 시점이 바뀌어도 다시 '맞음'으로 보이는지.
칸마다 모든 원시 프레임에 칸 중심을 투영해 상태(맞음 |D-d|<=m, 빔 D>d+m, 가림, 못 봄, m = max(τ, 2σ(d)))를 구하고,
맞음인 프레임 i와 본 프레임 j(맞음 또는 빔) 쌍에서 P(j 맞음 | i 맞음)을 시선각 차·거리비·시간차로 묶는다. 표면 칸은 화소 오차
(측정 깊이 − 정답 광선 깊이)의 쌍 상관도 낸다. RV의 ρ·β(`map.triViewRho`·`triViewRangeLn`)의 근거이고, 새 공간 녹화(M38)에서 다시 잰다.
헛 칸·바닥 칸 구역은 정답의 wall_left·wall_right·wall_end 이름(복도 정답 규칙)에 기댄다.

    python view_corr.py <출력 json> <세션 ID> ...     (정렬은 data/sweeps/m32/align_ref/<세션>/align.json)
"""
import json, sys
from pathlib import Path
import numpy as np, pandas as pd
from align import DEFAULT_CONFIG, REPO, load_frames, to_truth
from metrics import load_truth
from spike_check import quat_to_rot
from worldpts import depth_k, depth_rows, load_depth_m
from pixel_error import raycast

STEP = 2  # 화소 간격(자체 선택, 설계 측정과 같은 값)
rng = np.random.default_rng(0)
cfg = json.loads(DEFAULT_CONFIG.read_text(encoding="utf-8"))
V = cfg["map"]["voxelSizeM"]; TAU = V * 3 ** .5 / 2
S0, S2 = cfg["depth"]["rawSigma0M"], cfg["depth"]["rawSigmaPerM2"]
ANG = np.array([0, 2, 5, 10, 20, 40, 180]); RR = np.array([0, .1, .25, .5, 1, 9]); DT = np.array([0, .5, 1, 2, 4, 8, 99])

def box_dist(p, lo, hi):
    q = np.maximum(lo - p, 0) + np.maximum(p - hi, 0)
    out = np.linalg.norm(q, axis=1)
    inside = (out == 0)
    if inside.any():
        out[inside] = -np.min(np.minimum(p[inside] - lo, hi - p[inside]), axis=1)
    return out

def session(sid):
    s = REPO / "data/sessions" / sid
    al = json.loads((REPO / "data/sweeps/m32/align_ref" / sid / "align.json").read_text(encoding="utf-8"))
    meta = json.loads((s / "meta.json").read_text(encoding="utf-8"))
    truth = [o for o in load_truth(s, cfg)[0] if o.get("kind") != "unmodeled"]
    boxes = [(np.array(o["min"]), np.array(o["max"]), o["name"]) for o in truth]
    rot_only = {**al, "origin": [0.0, 0.0], "floorY": 0.0}
    rows = depth_rows(load_frames(s), "RAW").drop_duplicates("rawDepthTNs")
    cams, Rs, deps, ts, hits = [], [], [], [], []
    k = None
    for _, row in rows.iterrows():
        d = load_depth_m(s / row["rawDepthFile"])
        if k is None:
            k = depth_k(meta, d.shape[1], d.shape[0])
            vv, uu = np.mgrid[0:d.shape[0]:STEP, 0:d.shape[1]:STEP]
        r = quat_to_rot(np.array([[row.qx, row.qy, row.qz, row.qw]]))[0]
        # 정답 좌표 회전: 월드 방향 → 정답 방향
        cols = [np.array(to_truth(rot_only, *r[:, c])) for c in range(3)]
        Rt = np.stack(cols, axis=1)  # GL 카메라 → 정답
        o = np.array(to_truth(al, row.tx, row.ty, row.tz), float)
        z = d[::STEP, ::STEP]
        ok = z > 0
        x = (uu[ok] - k["cx"]) / k["fx"] * z[ok]; y = -(vv[ok] - k["cy"]) / k["fy"] * z[ok]
        p = np.stack([x, y, -z[ok]], 1) @ Rt.T + o
        hits.append(np.unique(np.floor(p / V).astype(np.int32), axis=0))
        cams.append(o); Rs.append(Rt); deps.append(d); ts.append(row.rawDepthTNs / 1e9)
    keys = np.unique(np.concatenate(hits), axis=0)
    c = (keys + .5) * V
    dist = np.min(np.stack([box_dist(c, lo, hi) for lo, hi, _ in boxes]), axis=0)
    xl = max(lo[0] for lo, hi, n in boxes if n == "wall_right") - 0.1
    xr = min(hi[0] for lo, hi, n in boxes if n == "wall_left") + 0.1
    zend = min(lo[2] for lo, hi, n in boxes if n == "wall_end")
    phantom = (dist > 0.12) & (c[:, 1] > 0.25) & (c[:, 1] < 1.95) & (np.abs(c[:, 0]) < 0.42) & (c[:, 2] < zend - 0.12)
    surface = (np.abs(dist) <= TAU) & (c[:, 1] > 0.25)
    fl = al["floorRef"]["rawM"] - al["floorY"]
    yr = c[:, 1] - fl
    floorbump = (yr > 0.10) & (yr <= 0.30) & (dist > 0.12) & (np.abs(c[:, 0]) < 0.42) & (c[:, 2] < zend - 0.12)
    floorok = (np.abs(yr) <= TAU) & (dist > 0.12) & (np.abs(c[:, 0]) < 0.42) & (c[:, 2] < zend - 0.12)
    out = {}
    for name, m in (("phantom", phantom), ("surface", surface), ("floorbump", floorbump), ("floor", floorok)):
        idx = np.flatnonzero(m)
        if len(idx) > 1500: idx = rng.choice(idx, 1500, replace=False)
        cc = c[idx]; nF = len(cams)
        st = np.zeros((len(idx), nF), np.int8)  # 0 못봄 1 맞음 2 빔 3 가림
        dirs = np.zeros((len(idx), nF, 3)); rng_ = np.zeros((len(idx), nF)); err = np.full((len(idx), nF), np.nan)
        for f in range(nF):
            v = (cc - cams[f]) @ Rs[f]  # 정답 → GL 카메라
            dv = -v[:, 2]
            good = dv > 0.1
            u = np.where(good, np.round(k["fx"] * v[:, 0] / np.maximum(dv, 1e-6) + k["cx"]), -1).astype(int)
            w = np.where(good, np.round(k["cy"] - k["fy"] * v[:, 1] / np.maximum(dv, 1e-6)), -1).astype(int)
            good &= (u >= 0) & (u < k["width"]) & (w >= 0) & (w < k["height"])
            D = np.zeros(len(idx)); D[good] = deps[f][w[good], u[good]]
            good &= D > 0
            mrg = np.maximum(TAU, 2 * (S0 + S2 * dv ** 2))
            s_ = np.where(D > dv + mrg, 2, np.where(D < dv - mrg, 3, 1))
            st[:, f] = np.where(good, s_, 0)
            if name == "surface" and good.any():
                dcam = np.stack([(u - k["cx"]) / k["fx"], -(w - k["cy"]) / k["fy"], -np.ones(len(u))], 1)
                gi = np.flatnonzero(good)
                tt, _, _ = raycast(cams[f], dcam[gi] @ Rs[f].T, [(lo, hi, n) for lo, hi, n in boxes], -10.0)
                ee = D[gi] - tt
                okk = np.abs(ee) < 0.3
                err[gi[okk], f] = ee[okk]
            rel = cc - cams[f]; rr = np.linalg.norm(rel, axis=1)
            dirs[:, f] = rel / rr[:, None]; rng_[:, f] = rr
        T = np.array(ts)
        num = np.zeros((len(ANG) - 1, len(RR) - 1)); den = np.zeros_like(num)
        numt = np.zeros(len(DT) - 1); dent = np.zeros_like(numt)
        hitfrac = []
        cs = np.zeros((len(ANG) - 1, len(RR) - 1)); cn = np.zeros_like(cs); allv = err[np.isfinite(err)]
        mu = allv.mean() if allv.size else 0; var = allv.var() if allv.size else 1
        for a in range(len(idx)):
            hi_ = np.flatnonzero(st[a] == 1); ob = np.flatnonzero((st[a] == 1) | (st[a] == 2))
            if len(ob): hitfrac.append(len(hi_) / len(ob))
            if len(hi_) == 0: continue
            I, J = np.meshgrid(hi_, ob, indexing="ij"); I = I.ravel(); J = J.ravel(); keep = I != J; I = I[keep]; J = J[keep]
            ang = np.degrees(np.arccos(np.clip((dirs[a, I] * dirs[a, J]).sum(1), -1, 1)))
            lr = np.abs(np.log(rng_[a, J] / rng_[a, I]))
            same = st[a, J] == 1
            ai = np.clip(np.searchsorted(ANG, ang, "right") - 1, 0, len(ANG) - 2); ri = np.clip(np.searchsorted(RR, lr, "right") - 1, 0, len(RR) - 2)
            np.add.at(num, (ai, ri), same); np.add.at(den, (ai, ri), 1)
            ti = np.clip(np.searchsorted(DT, np.abs(T[J] - T[I]), "right") - 1, 0, len(DT) - 2)
            np.add.at(numt, ti, same); np.add.at(dent, ti, 1)
            if name == "surface":
                ei, ej = err[a, I], err[a, J]; g2 = np.isfinite(ei) & np.isfinite(ej)
                np.add.at(cs, (ai[g2], ri[g2]), (ei[g2] - mu) * (ej[g2] - mu) / var); np.add.at(cn, (ai[g2], ri[g2]), 1)
        out[name] = dict(n=int(len(idx)), num=num.tolist(), den=den.tolist(), numt=numt.tolist(), dent=dent.tolist(),
                         hitfrac=float(np.mean(hitfrac)) if hitfrac else None, nAll=int(m.sum()), cs=cs.tolist(), cn=cn.tolist())
    return out

if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    res = {sid: session(sid) for sid in sys.argv[2:]}
    Path(sys.argv[1]).write_text(json.dumps(res))
    for name in ("phantom", "surface", "floorbump", "floor"):
        num = sum(np.array(r[name]["num"]) for r in res.values()); den = sum(np.array(r[name]["den"]) for r in res.values())
        print(name, "n voxels", {sid[9:15]: (r[name]["nAll"], round(r[name]["hitfrac"] or 0, 2)) for sid, r in res.items()})
        print(" rows angle", ANG.tolist(), "cols |ln range ratio|", RR.tolist())
        with np.errstate(invalid="ignore"):
            print(np.round(num / den, 2)); print((den / 1000).round(0))
        numt = sum(np.array(r[name]["numt"]) for r in res.values()); dent = sum(np.array(r[name]["dent"]) for r in res.values())
        print(" dt", DT.tolist(), np.round(numt / dent, 2))
        if name == "surface":
            cs = sum(np.array(r[name]["cs"]) for r in res.values()); cn = sum(np.array(r[name]["cn"]) for r in res.values())
            with np.errstate(invalid="ignore"):
                print(" error corr", np.round(cs / cn, 2))
