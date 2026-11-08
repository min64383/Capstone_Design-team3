"""M56 ③ 띠 자체 지표(재생 없음): 띠 규칙마다 막을 얼마나 지우고 물체 화소를 얼마나 지키는가.

    python band_own.py <재생 폴더>...

평활 장마다(band_split.measure와 같은 짝짓기) 화소의 라벨 경계 거리 |k|(깊이 화소)와 장애물 쪽 가장 가까운 깊이 Z_near(둘레 6화소 창의 최소
평활 깊이, core LabelBand.nearestDepthMm과 같음)로 규칙을 적용한다(근삿값: 영역 안쪽 반지름 한도는 두 규칙 모두 뺌).
- 고정: |k| ≤ 6. 계단: |k| ≤ (Z_near < 1.5 m이면 6, 아니면 3).
- 막 화소 = 원시(신뢰도 ≥ 128)와 어긋남(band_split와 같은 정의), |k| ≤ 8 안. 지운 몫 = 막 지우기.
- 막 아닌 화소(원시와 맞음) 중 |k| ≤ 8 안에서 지운 몫 = 곁 손실.
- 실내 정답 상자 안 평활 점 중 지운 몫 = 물체 손실. 모두 Z_near 구간(< 1.5 m, ≥ 1.5 m)별.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

import stage0 as S
from align import to_truth
from label_band import K_WIN, _project, signed_k
from motion_stats import _sem_k

R = 6
NEAR_M, FAR_PX, MAX_PX = 1.5, 3, 6
MOVABLE = (9, 10)


def nearest(z: np.ndarray, r: int) -> np.ndarray:
    big = np.where(z > 0, z, np.inf)
    h, w = z.shape
    pad = np.pad(big, r, constant_values=np.inf)
    out = np.full((h, w), np.inf)
    for dy in range(-r, r + 1):  # Kotlin: 분리 최소 필터와 같은 값
        for dx in range(-r, r + 1):
            out = np.minimum(out, pad[r + dy:r + dy + h, r + dx:r + dx + w])
    return out


def run(run_dir: Path) -> dict:
    session, cfg = S.session_of(run_dir), S.load_config(run_dir)
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = S.load_frames(session).reset_index(drop=True)
    sem = pd.read_csv(session / "semantic" / "index.csv").sort_values("frameTNs").reset_index(drop=True)
    max_age = cfg["vision"]["maxLabelAgeMs"] * 1_000_000
    rs0, rs2 = cfg["depth"]["rawSigma0M"], cfg["depth"]["rawSigmaPerM2"]
    mult, vox, radius = cfg["map"]["sigmaMultiple"], cfg["map"]["voxelSizeM"], cfg["map"]["radiusM"]
    al = json.loads((run_dir / "align.json").read_text(encoding="utf-8")) if (run_dir / "align.json").is_file() else None
    obs = (json.loads((session / "annotations" / "obstacles.json").read_text(encoding="utf-8")).get("obstacles", [])
           if (session / "annotations" / "obstacles.json").is_file() and al else [])
    ft = fr.tNs.to_numpy()
    # [규칙 0 고정 1 계단][Z_near 구간 0 가까움 1 멂][지표: 막 수, 막 지움, 막아님 수, 막아님 지움, 물체 수, 물체 지움]
    acc = np.zeros((2, 2, 6), np.int64)
    raw_file, seen, cache = None, set(), {}

    def pose_at(t_ns, upto):
        j = int(np.argmin(np.abs(ft[: upto + 1] - t_ns)))
        r = fr.iloc[j]
        return S.quat_to_rot(np.array([[r.qx, r.qy, r.qz, r.qw]]))[0] @ S.FLIP, np.array([r.tx, r.ty, r.tz])

    for i, row in fr.iterrows():
        if isinstance(row.rawDepthFile, str):
            raw_file = (int(row.rawDepthTNs), row.rawDepthFile, row.confFile)
        if not isinstance(row.depthFile, str) or row.depthTNs in seen or row.tracking != "TRACKING":
            continue
        seen.add(row.depthTNs)
        t_d = int(row.depthTNs)
        li = int(np.searchsorted(sem.frameTNs.to_numpy(), int(row.tNs), side="right")) - 1
        if li < 0 or t_d - int(sem.imageTNs.iloc[li]) > max_age or raw_file is None or t_d - raw_file[0] > max_age:
            continue
        zs = S.load_depth_m(session / row.depthFile)
        kd = S.depth_k(meta, zs.shape[1], zs.shape[0])
        rs, cs = pose_at(t_d, i)
        zn_img = nearest(zs, R)
        v, u = np.nonzero(zs > 0)
        z = zs[v, u]
        p = np.stack([(u - kd["cx"]) / kd["fx"] * z, (v - kd["cy"]) / kd["fy"] * z, z], axis=1) @ rs.T + cs
        keep = np.hypot(p[:, 0] - cs[0], p[:, 2] - cs[2]) <= radius
        p, zn = p[keep], zn_img[v[keep], u[keep]]
        e = sem.iloc[li]
        key = int(e.imageTNs)
        if key not in cache:
            lab = np.array(Image.open(session / e.file))
            kl = _sem_k(meta, lab.shape[1], lab.shape[0])
            cache = {key: (lab, kl, signed_k(lab, kd["fx"] / kl["fx"]), pose_at(key, i))}
        lab, kl, kimg, (rl, cl) = cache[key]
        ul, vl, _, okl = _project(p, rl, cl, kl)
        labv = np.where(okl, lab[vl, ul].astype(int), -1)
        kk = np.abs(np.where(okl, kimg[vl, ul], np.inf))
        raw = S.load_depth_m(session / raw_file[1])
        conf = np.array(Image.open(session / raw_file[2])) if isinstance(raw_file[2], str) else np.full(raw.shape, 255)
        kr = S.depth_k(meta, raw.shape[1], raw.shape[0])
        rr, cr = pose_at(raw_file[0], i)
        ur, vr, zc, okr = _project(p, rr, cr, kr)
        zr = np.where(okr, raw[vr, ur], 0)
        has = okr & (zr > 0) & (np.where(okr, conf[vr, ur], 0) >= 128) & okl & ~np.isin(labv, MOVABLE)
        bad = has & (np.abs(zr - zc) > np.maximum(mult * (rs0 + rs2 * zc * zc), vox))
        zone = np.isfinite(kk) & (kk <= K_WIN)
        far = zn >= NEAR_M
        rem = [kk <= MAX_PX, kk <= np.where(far, FAR_PX, MAX_PX)]
        inbox = np.zeros(len(p), dtype=bool)
        for o in obs:
            x, y, zt = to_truth(al, p[:, 0], p[:, 1], p[:, 2])
            lo, hi = np.array(o["min"]), np.array(o["max"])
            inbox |= (x >= lo[0]) & (x <= hi[0]) & (y >= max(lo[1], 0.05)) & (y <= hi[1]) & (zt >= lo[2]) & (zt <= hi[2]) & okl
        for ri in range(2):
            for zi, zm in ((0, ~far), (1, far)):
                mb = zone & bad & zm
                mg = zone & has & ~bad & zm
                mo = inbox & zm
                acc[ri, zi] += (mb.sum(), (mb & rem[ri]).sum(), mg.sum(), (mg & rem[ri]).sum(), mo.sum(), (mo & rem[ri]).sum())
    return dict(session=session.name, group=S.group_of(run_dir), acc=acc)


def main(runs: list[Path]) -> None:
    res = [run(r) for r in runs]
    for grp in ("t", "indoor", "all"):
        a = sum(r["acc"] for r in res if grp == "all" or r["group"] == grp)
        print(f"== {grp}")
        for zi, zname in ((0, "Z_near < 1.5 m"), (1, "Z_near ≥ 1.5 m")):
            line = []
            for ri, rname in ((0, "고정 6"), (1, "계단")):
                c = a[ri, zi]
                f = lambda n, d: f"{n / d:.1%}" if d else "-"
                line.append(f"{rname}: 막 지움 {f(c[1], c[0])}(막 {c[0]}), 곁 손실 {f(c[3], c[2])}, 물체 손실 {f(c[5], c[4])}(물체 {c[4]})")
            print(f"  {zname}: " + " | ".join(line))


if __name__ == "__main__":
    if "--runtime-roles" in sys.argv:
        import label_band
        label_band.OBSTACLE_ROLE = np.array([0, 3, 7, 8, 9, 10])  # B+W5 frontend.bandLabels: 하늘(1)·물(11) 뺌
    main([Path(a) for a in sys.argv[1:] if not a.startswith("--")])
