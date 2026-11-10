"""사람이 장마다 어디까지 표현되는지(진단, 재생 없음): 깊이 점 → 지도 칸 → track → 출력.

    python diag_person.py <재생 폴더(-PlabelDump=true)> [--every 3]

장마다(정답 사람이 있는 깊이 장): 카메라에서 사람까지 거리·옆 거리(카메라 진행 방향 기준), 평활 깊이 점 수와 원시 점 수(신뢰도 ≥ 30)
(정답 원기둥 반지름 + 0.05 m 안, 바닥 위 0.1~2.0 m), 그 안 점유 칸 수(voxel_labels: 사용자 3.5 m 안 점유 칸, 움직이는 칸은 점유가 아니라 빠짐),
정답 0.8 m 안 track(C 후보, M 움직이는 track, S 지도 track), 정답 상자 0.8 m 안 출력 수.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

import stage0 as S


def rows(run: Path, every: int = 1):
    """장마다 (t, dist, lat, 평활 점, 원시 점, 칸, track 목록, 출력 수). t는 세션 첫 장부터(s)."""
    session = S.session_of(run)
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = S.load_frames(session).reset_index(drop=True)
    sp = pd.read_csv(run / "slow_path.csv")
    mv = S.movers(session)
    mo = pd.read_csv(run / "motion.csv") if (run / "motion.csv").is_file() else pd.DataFrame(columns=["tCaptureNs"])
    ob = pd.read_csv(run / "obstacles.csv")
    vl = pd.read_csv(run / "voxel_labels.csv.gz") if (run / "voxel_labels.csv.gz").is_file() else None
    raws = S.raw_frames(session, meta, fr)
    rt = np.array([f.t_capture_ns for f, _ in raws])
    t0 = fr.tNs.iloc[0]
    for i, row in enumerate(fr.itertuples(index=False)):
        if i % every or not isinstance(row.depthFile, str) or row.tracking != "TRACKING":
            continue
        t = int(row.depthTNs)
        q = next(((tr(t / 1e9), rad, fy) for _, tr, rad, fy in mv if tr(t / 1e9) is not None), None)
        if q is None:
            continue
        (pos, vel), rad, pfy = q
        rot = S.quat_to_rot(np.array([[row.qx, row.qy, row.qz, row.qw]]))[0] @ S.FLIP
        cam = np.array([row.tx, row.ty, row.tz])
        fwd = rot[:, 2]
        fwd_h = np.array([fwd[0], fwd[2]]) / max(np.hypot(fwd[0], fwd[2]), 1e-6)
        rel = np.array([pos[0] - cam[0], pos[1] - cam[2]])
        dist, lat = float(rel @ fwd_h), float(rel @ np.array([-fwd_h[1], fwd_h[0]]))
        d = S.load_depth_m(session / row.depthFile)
        k = S.depth_k(meta, d.shape[1], d.shape[0])
        vv, uu = np.nonzero(d > 0)
        z = d[vv, uu]
        p = np.stack([(uu - k["cx"]) / k["fx"] * z, (vv - k["cy"]) / k["fy"] * z, z], axis=1) @ rot.T + cam
        inside = lambda pts: (np.hypot(pts[:, 0] - pos[0], pts[:, 2] - pos[1]) <= rad + 0.05) & (pts[:, 1] > pfy + 0.1) & (pts[:, 1] < pfy + 2.0)
        n_s = int(inside(p).sum())
        j = int(np.searchsorted(rt, t, side="right")) - 1
        n_r = 0
        if j >= 0 and t - rt[j] < 2e8:
            pr, _ = S.points_of(raws[j][0], 30)
            n_r = int(inside(pr).sum())
        n_c = 0
        if vl is not None:
            c = vl[vl.tCaptureNs == t]
            if len(c):
                cx, cy, cz = (c.ix + 0.5) * 0.05, (c.iy + 0.5) * 0.05, (c.iz + 0.5) * 0.05
                n_c = int(((np.hypot(cx - pos[0], cz - pos[1]) <= rad + 0.05) & (cy > pfy + 0.1) & (cy < pfy + 2.0)).sum())
        m = mo[mo.tCaptureNs == t] if len(mo) else mo
        trk = []
        for r in m.itertuples():
            px = r.posX if np.isfinite(r.posX) else r.measX
            pz = r.posZ if np.isfinite(r.posZ) else r.measZ
            if np.isfinite(px) and np.hypot(px - pos[0], pz - pos[1]) <= 0.8:
                trk.append(f"{r.id}{'C' if r.candidate else ('M' if r.moving else 'S')}")
        o = ob[ob.tCaptureNs == t]
        n_o = sum(1 for b in o.itertuples() if np.hypot(max(b.aabbMinX - pos[0], 0, pos[0] - b.aabbMaxX), max(b.aabbMinZ - pos[1], 0, pos[1] - b.aabbMaxZ)) <= 0.8)
        yield (t - t0) / 1e9, dist, lat, n_s, n_r, n_c, trk, n_o


def main(run: Path, every: int) -> None:
    print("  t     dist  lat   평활  원시  칸  track          출력")
    for t, dist, lat, n_s, n_r, n_c, trk, n_o in rows(run, every):
        print(f"{t:6.2f} {dist:5.2f} {lat:5.2f} {n_s:5d} {n_r:5d} {n_c:3d}  {','.join(trk):14s} {n_o}")


if __name__ == "__main__":
    ev = int(sys.argv[sys.argv.index("--every") + 1]) if "--every" in sys.argv else 3
    main(Path(sys.argv[1]), ev)
