"""M57 2단계 v_min 유도(설계 표 4, 재생 없음): 복셀 지도가 스스로 점유로 잡는 가장 빠른 시선 방향 속도.

    python vmin_weight.py <재생 폴더>...      (T 사람 세션 재생, 바닥은 slow_path.csv)

v_min = 칸(0.05 m) × 깊이 장 빈도(30 Hz) × w̄ ÷ 점유 문턱(map.weightedMinHits 5). 칸 하나를 지나는 면이 그 칸에 머무는 시간 동안 받는 표가
w̄ × 장 수이고, 문턱에 닿으려면 그 시간이 문턱 ÷ w̄ 장보다 길어야 한다. w̄ = 걷는 사람(정답 속도 ≥ 0.3 m/s, 카메라 3 m 안) 평활 점의 지도 무게
(core LocalMap: 그 점을 가장 최근 원시 장(나이 ≤ vision.maxLabelAgeMs)에 투영한 화소의 신뢰도 구간 → depth.mapConfidenceWeights)의 중앙값.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

import stage0 as S


def run(runs: list[Path]) -> None:
    ws = []
    for run in runs:
        session, cfg = S.session_of(run), S.load_config(run)
        meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
        fr = S.load_frames(session).reset_index(drop=True)
        sp = pd.read_csv(run / "slow_path.csv")
        mv = S.movers(session)
        edges = cfg["depth"]["confidenceEdges"]
        wts = cfg["depth"]["mapConfidenceWeights"]
        max_age = cfg["vision"]["maxLabelAgeMs"] * 1e6
        raws = S.raw_frames(session, meta, fr)
        rt = np.array([f.t_capture_ns for f, _ in raws])
        ft = fr.tNs.to_numpy()
        n0 = len(ws)
        for i, row in enumerate(fr.itertuples(index=False)):
            if not isinstance(row.depthFile, str) or row.tracking != "TRACKING":
                continue
            t = int(row.depthTNs)
            fy = S.floor_at(sp, t)
            if fy is None:
                continue
            j = int(np.searchsorted(rt, t, side="right")) - 1
            if j < 0 or t - rt[j] > max_age:
                continue
            pi = int(np.argmin(np.abs(ft[:i + 1] - t)))
            pr = fr.iloc[pi]
            rot = S.quat_to_rot(np.array([[pr.qx, pr.qy, pr.qz, pr.qw]]))[0] @ S.FLIP
            d = S.load_depth_m(session / row.depthFile)
            k = S.depth_k(meta, d.shape[1], d.shape[0])
            vv, uu = np.nonzero(d > 0)
            vv, uu = vv[::3], uu[::3]
            z = d[vv, uu]
            pc = np.stack([(uu - k["cx"]) / k["fx"] * z, (vv - k["cy"]) / k["fy"] * z, z], axis=1)
            p = pc @ rot.T + np.array([pr.tx, pr.ty, pr.tz])
            sel = np.zeros(len(p), bool)
            for _, tr, rad, pfy in mv:
                r = tr(t / 1e9)
                if r is None or not np.all(np.isfinite(r[1])) or np.hypot(*r[1]) < 0.3:
                    continue
                if np.hypot(r[0][0] - pr.tx, r[0][1] - pr.tz) > 3.0:
                    continue
                sel |= (np.hypot(p[:, 0] - r[0][0], p[:, 2] - r[0][1]) <= rad + 0.05) & (p[:, 1] > pfy + 0.1) & (p[:, 1] < pfy + 2.0)
            if not sel.any():
                continue
            raw = raws[j][0]
            c = (p[sel] - raw.cam_w) @ raw.rot_w_from_c
            ok = c[:, 2] > 0.1
            u = np.where(ok, (raw.fx * c[:, 0] / np.where(ok, c[:, 2], 1) + raw.cx), -1).astype(int)
            v = np.where(ok, (raw.fy * c[:, 1] / np.where(ok, c[:, 2], 1) + raw.cy), -1).astype(int)
            h, w = raw.depth_m.shape
            inb = ok & (u >= 0) & (v >= 0) & (u < w) & (v < h)
            conf = np.zeros(len(c), int)
            uu2, vv2 = np.where(inb, u, 0), np.where(inb, v, 0)
            conf = np.where(inb & (raw.depth_m[vv2, uu2] > 0), raw.conf[vv2, uu2].astype(int), 0)
            cls = np.sum(conf[:, None] >= np.array(edges)[None, :], axis=1)
            ws.extend(np.array(wts)[cls].tolist())
        print(f"{run.name}: 사람 점 {len(ws) - n0}", file=sys.stderr)
    w = np.array(ws)
    wbar = float(np.median(w))
    print(f"걷는 사람 평활 점 {len(w)}, 무게 구간 몫 {dict(zip(*np.unique(w, return_counts=True)))}, w̄(중앙값) {wbar:.3f}, 평균 {w.mean():.3f}")
    print(f"v_min = 0.05 × 30 × w̄ / 5 = {0.05 * 30 * wbar / 5:.3f} m/s")


if __name__ == "__main__":
    run([Path(a) for a in sys.argv[1:]])
