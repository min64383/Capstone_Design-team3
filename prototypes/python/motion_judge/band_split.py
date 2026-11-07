"""M56 0-5 경계 띠: 라벨 경계 거리별 원시(≥ 128)–평활 어긋남을 깊이 경계 근처·아님으로 나눔(설계 3.6절, 재생 없음).

    python band_split.py <재생 폴더>...

평활 장마다(label_band.measure와 같은 짝짓기: 그때까지 받은 가장 최근 원시 장·라벨 영상, 나이 ≤ vision.maxLabelAgeMs)
- k: 장애물 역할 라벨 경계까지 부호 있는 거리(깊이 화소, label_band.signed_k).
- 어긋남: 원시(신뢰도 ≥ 128)가 있고 |원시 − 점의 원시 카메라 깊이| > max(2σ_원시(Z), 복셀)(label_band와 같은 정의). 사람·차량 라벨 화소는 뺌.
- 깊이 경계 화소: 평활 깊이 영상에서 4이웃과의 깊이 차 > 2σ_평활(Z)(map.sigmaMultiple). 근처 = 그 화소에서 띠 폭(6) 화소 안.
- 바탕: 라벨 경계에서 K_WIN 넘게 멀고 깊이 경계 근처도 아닌 화소. 구간은 세션 단위 부트스트랩 95%.
규칙(설계 3.6절): b = 깊이 경계 근처 쪽 초과 어긋남이 바탕 구간 안으로 드는 첫 |k|. 깊이 경계 아닌 쪽이 모든 k에서 바탕 구간 안이면 띠를
깊이 경계에서만 둔다. 실내 정답 물체: 상자 안 평활 점 중 지금 띠(|k| ≤ 6)가 지우는 몫과, 깊이 경계 조건을 쓰면 지우는 몫.
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

BAND_PX = 6
MOVABLE = (9, 10)
ROT_EDGES = [0.0, 5.0, 15.0, 30.0, 1e9]  # 깊이 장 회전 속도(°/s) 구간
AGE_EDGES = [0.0, 50.0, 100.0, 1e9]  # 라벨 나이(깊이 촬영 − 라벨 촬영, ms) 구간
Z_BANDS = [0.5, 1.5, 2.5, 3.5, 5.0]  # 막 폭을 잴 깊이 구간(m)
Z_BIN_BY = "self"  # "self" = 화소 자신의 평활 깊이, "near" = 둘레 6화소 안 가장 가까운 깊이(-—near)
OBSTACLE_ROLE_RUNTIME = None  # B+W5 띠의 장애물 역할(하늘·물 뺌)으로 k를 잴 때(—runtime-roles)
NK = 2 * K_WIN + 2  # −K_WIN..K_WIN + 먼 곳


def depth_edge_near(z: np.ndarray, s0: float, k2: float, mult: float) -> np.ndarray:
    """평활 깊이 영상(H×W, 0 = 없음)에서 깊이 경계 화소(4이웃 차 > mult·σ(Z))로부터 띠 폭(BAND_PX) 화소 안인지."""
    h, w = z.shape
    sig = mult * np.maximum(s0 + k2 * z * z, 1e-3)
    edge = np.zeros((h, w), dtype=bool)
    for dy, dx in ((0, 1), (1, 0)):
        a = z[: h - dy, : w - dx]
        b = z[dy:, dx:]
        e = (a > 0) & (b > 0) & (np.abs(a - b) > np.minimum(sig[: h - dy, : w - dx], sig[dy:, dx:]))
        edge[: h - dy, : w - dx] |= e
        edge[dy:, dx:] |= e
    near = edge.copy()
    for _ in range(BAND_PX):  # 4이웃 팽창 BAND_PX번(맨해튼 거리 띠 폭 안: 그 띠 화소 옆에 깊이 경계가 있는가)
        n2 = near.copy()
        n2[1:, :] |= near[:-1, :]
        n2[:-1, :] |= near[1:, :]
        n2[:, 1:] |= near[:, :-1]
        n2[:, :-1] |= near[:, 1:]
        near = n2
    return near


def measure(run: Path):
    session, cfg = S.session_of(run), S.load_config(run)
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = S.load_frames(session).reset_index(drop=True)
    sem = pd.read_csv(session / "semantic" / "index.csv").sort_values("frameTNs").reset_index(drop=True)
    max_age = cfg["vision"]["maxLabelAgeMs"] * 1_000_000
    rs0, rs2 = cfg["depth"]["rawSigma0M"], cfg["depth"]["rawSigmaPerM2"]
    ss0, ss2 = cfg["depth"]["smoothedSigma0M"], cfg["depth"]["smoothedSigmaPerM2"]
    mult, vox, radius = cfg["map"]["sigmaMultiple"], cfg["map"]["voxelSizeM"], cfg["map"]["radiusM"]
    al = json.loads((run / "align.json").read_text(encoding="utf-8")) if (run / "align.json").is_file() else None
    obs = (json.loads((session / "annotations" / "obstacles.json").read_text(encoding="utf-8")).get("obstacles", [])
           if (session / "annotations" / "obstacles.json").is_file() and al else [])
    ft = fr.tNs.to_numpy()
    tot = np.zeros((2, NK), np.int64)  # [깊이 경계 근처?, k 구간] 원시 있는 화소
    bad = np.zeros((2, NK), np.int64)
    box = np.zeros(3, np.int64)  # 정답 상자 안 평활 점: 전체, 지금 띠가 지움, 깊이 경계 조건이면 지움
    nr, na = len(ROT_EDGES) - 1, len(AGE_EDGES) - 1
    tot_ra = np.zeros((2, nr, na, NK), np.int64)  # [깊이 경계 근처?, 회전 구간, 라벨 나이 구간, k]
    nz = len(Z_BANDS) - 1
    tot_z = np.zeros((nz, NK), np.int64)  # [화소 평활 깊이 구간, k] (깊이 경계 구분 없이)
    bad_z = np.zeros_like(tot_z)
    bad_ra = np.zeros_like(tot_ra)
    raw_file, seen, cache = None, set(), {}
    prev_pose = None

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
        rot = np.nan
        if prev_pose is not None:
            rr_ = prev_pose[0].T @ rs
            dt_ = (t_d - prev_pose[1]) / 1e9
            rot = float(np.degrees(np.arccos(np.clip((np.trace(rr_) - 1) / 2, -1, 1))) / dt_) if dt_ > 0 else np.nan
        prev_pose = (rs, t_d)
        near_img = depth_edge_near(zs, ss0, ss2, mult)
        v, u = np.nonzero(zs > 0)
        z = zs[v, u]
        p = np.stack([(u - kd["cx"]) / kd["fx"] * z, (v - kd["cy"]) / kd["fy"] * z, z], axis=1) @ rs.T + cs
        keep = np.hypot(p[:, 0] - cs[0], p[:, 2] - cs[2]) <= radius
        p, nearp = p[keep], near_img[v[keep], u[keep]]
        e = sem.iloc[li]
        key = int(e.imageTNs)
        if key not in cache:
            lab = np.array(Image.open(session / e.file))
            kl = _sem_k(meta, lab.shape[1], lab.shape[0])
            cache = {key: (lab, kl, signed_k(lab, kd["fx"] / kl["fx"]), pose_at(key, i))}
        lab, kl, kimg, (rl, cl) = cache[key]
        ul, vl, _, okl = _project(p, rl, cl, kl)
        labv = np.where(okl, lab[vl, ul].astype(int), -1)
        kk = np.where(okl, kimg[vl, ul], np.inf)
        raw = S.load_depth_m(session / raw_file[1])
        conf = np.array(Image.open(session / raw_file[2])) if isinstance(raw_file[2], str) else np.full(raw.shape, 255)
        kr = S.depth_k(meta, raw.shape[1], raw.shape[0])
        rr, cr = pose_at(raw_file[0], i)
        ur, vr, zc, okr = _project(p, rr, cr, kr)
        zr = np.where(okr, raw[vr, ur], 0)
        has = okr & (zr > 0) & (np.where(okr, conf[vr, ur], 0) >= 128) & okl & ~np.isin(labv, MOVABLE)
        isbad = has & (np.abs(zr - zc) > np.maximum(mult * (rs0 + rs2 * zc * zc), vox))
        far = ~np.isfinite(kk) | (np.abs(kk) > K_WIN)
        kb = np.where(far, NK - 1, np.clip(np.rint(np.where(far, 0, kk)).astype(int) + K_WIN, 0, 2 * K_WIN))
        g = nearp.astype(int)
        np.add.at(tot, (g[has], kb[has]), 1)
        np.add.at(bad, (g[isbad], kb[isbad]), 1)
        # 구간 깊이: Z_BIN_BY = "near"면 둘레 6화소 창의 가장 가까운 평활 깊이(core LabelBand.nearestDepthMm, 계단 규칙과 같은 정의), 아니면 화소 자신
        if Z_BIN_BY == "near":
            from band_own import nearest
            zsm = nearest(zs, BAND_PX)[v[keep], u[keep]]
        else:
            zsm = ((p - cs) @ rs)[:, 2]  # 화소의 평활 카메라 깊이(C_cv z)
        zb_ = np.digitize(zsm, Z_BANDS) - 1
        okz = (zb_ >= 0) & (zb_ < nz)
        np.add.at(tot_z, (zb_[has & okz], kb[has & okz]), 1)
        np.add.at(bad_z, (zb_[isbad & okz], kb[isbad & okz]), 1)
        if np.isfinite(rot):
            rb = int(np.digitize(rot, ROT_EDGES) - 1)
            ab = int(np.digitize((t_d - key) / 1e6, AGE_EDGES) - 1)
            if 0 <= ab < na:
                np.add.at(tot_ra[:, rb, ab], (g[has], kb[has]), 1)
                np.add.at(bad_ra[:, rb, ab], (g[isbad], kb[isbad]), 1)
        for o in obs:
            x, y, zt = to_truth(al, p[:, 0], p[:, 1], p[:, 2])
            lo, hi = np.array(o["min"]), np.array(o["max"])
            inb = (x >= lo[0]) & (x <= hi[0]) & (y >= max(lo[1], 0.05)) & (y <= hi[1]) & (zt >= lo[2]) & (zt <= hi[2]) & okl
            band = inb & np.isfinite(kk) & (np.abs(kk) <= BAND_PX)
            box += (int(inb.sum()), int(band.sum()), int((band & nearp).sum()))
    return dict(session=session.name, group=S.group_of(run), tot=tot, bad=bad, box=box, tot_ra=tot_ra, bad_ra=bad_ra, tot_z=tot_z, bad_z=bad_z, fx=float(kd["fx"]))


def half_width(tot: np.ndarray, bad: np.ndarray):
    """깊이 경계 근처 쪽(장애물 쪽 k ≤ 0)에서 초과 어긋남(그 묶음의 라벨 경계 먼 곳 대비)이 k = 0 값의 절반 아래로 처음 드는 |k|, 그리고 k = 0 초과."""
    m = bad[1] / np.maximum(tot[1], 1)
    base = m[-1]
    ex0 = m[K_WIN] - base
    if tot[1, K_WIN] < 200 or ex0 <= 0:
        return None, None
    for d in range(0, K_WIN + 1):
        if m[K_WIN - d] - base < ex0 / 2:
            return d, ex0
    return K_WIN + 1, ex0


def by_condition(res: list) -> None:
    print("깊이 경계 근처의 라벨 경계 몫 반폭(화소, 장애물 쪽) — 세션별:")
    for r in res:
        w, ex = half_width(r["tot"], r["bad"])
        print(f"  {r['session']} ({r['group']}): 반폭 {w}, k=0 초과 {ex if ex is None else round(float(ex), 2)}, k=0 표본 {int(r['tot'][1, K_WIN])}")
    print("깊이 경계 아닌 라벨 경계(|k| ≤ 6)의 어긋남 대 그 세션 바탕(라벨 경계 먼 곳·깊이 경계 아님) — 세션별:")
    for r in res:
        t0, b0 = r["tot"][0], r["bad"][0]
        sel = slice(K_WIN - BAND_PX, K_WIN + BAND_PX + 1)
        if t0[sel].sum() < 200 or t0[-1] < 200:
            print(f"  {r['session']} ({r['group']}): 표본 부족({int(t0[sel].sum())})")
            continue
        print(f"  {r['session']} ({r['group']}): 경계 띠 안 {b0[sel].sum() / t0[sel].sum():.3f} (n{int(t0[sel].sum())}) 대 바탕 {b0[-1] / t0[-1]:.3f}")
    tr = sum(r["tot_ra"] for r in res)
    br = sum(r["bad_ra"] for r in res)
    print("회전 속도(°/s) × 라벨 나이(ms) 구간별 반폭(표본):")
    for ri in range(len(ROT_EDGES) - 1):
        cells = []
        for ai in range(len(AGE_EDGES) - 1):
            w, ex = half_width(tr[:, ri, ai], br[:, ri, ai])
            cells.append(f"나이 {AGE_EDGES[ai]:g}~{AGE_EDGES[ai + 1]:g}: {w}(n{int(tr[1, ri, ai, K_WIN])})")
        print(f"  회전 {ROT_EDGES[ri]:g}~{ROT_EDGES[ri + 1]:g}: " + ", ".join(cells))


def main(runs: list[Path]) -> None:
    res = [measure(r) for r in runs]
    for r in res:
        print(r["session"], file=sys.stderr)
    rng = np.random.default_rng(0)
    for grp in ("t", "indoor", "all"):
        rr = [r for r in res if grp == "all" or r["group"] == grp]
        if not rr:
            continue
        tot = sum(r["tot"] for r in rr)
        bad = sum(r["bad"] for r in rr)
        bg = bad[0, -1] / max(tot[0, -1], 1)
        boots = []
        for _ in range(2000):
            pick = [rr[j] for j in rng.integers(0, len(rr), len(rr))]
            t_ = sum(x["tot"][0, -1] for x in pick)
            boots.append(sum(x["bad"][0, -1] for x in pick) / max(t_, 1))
        lo, hi = np.percentile(boots, [2.5, 97.5])
        print(f"== {grp}: 바탕(라벨 경계 먼 곳, 깊이 경계 아님) 어긋남 {bg:.3f} [{lo:.3f}, {hi:.3f}], n {tot[0, -1]}")
        for gi, gname in ((1, "깊이 경계 근처"), (0, "깊이 경계 아님")):
            row = []
            for kb in range(NK - 1):
                k = kb - K_WIN
                if tot[gi, kb] == 0:
                    continue
                m = bad[gi, kb] / tot[gi, kb]
                row.append(f"{k:+d}:{m:.2f}{'*' if m > hi else ''}")
            print(f"  {gname}: " + " ".join(row) + f" | 먼 곳 {bad[gi, -1] / max(tot[gi, -1], 1):.2f}")
    print("(* = 바탕 구간 위)")
    print("실내 정답 상자 안 평활 점: 세션, 전체, 지금 띠가 지움, 깊이 경계 조건이면 지움")
    for r in res:
        if r["box"][0]:
            b = r["box"]
            print(f"  {r['session']}: {b[0]} 점, 지금 띠 {b[1] / b[0]:.1%}, 깊이 경계 조건 {b[2] / b[0]:.1%}")


def by_distance(res: list) -> None:
    """화소 평활 깊이 구간별: 장애물 쪽 라벨 경계 거리 k(0~−6)의 초과 어긋남(그 구간 라벨 경계 먼 곳 대비)과 반폭(화소·cm)."""
    fx = float(np.median([r["fx"] for r in res]))
    for grp in ("t", "indoor", "all"):
        rr = [r for r in res if grp == "all" or r["group"] == grp]
        tz = sum(r["tot_z"] for r in rr)
        bz = sum(r["bad_z"] for r in rr)
        print(f"== {grp} (깊이 fx {fx:.1f} 화소)")
        for zi in range(len(Z_BANDS) - 1):
            m = bz[zi] / np.maximum(tz[zi], 1)
            base = m[-1]
            ex = [m[K_WIN - d] - base for d in range(0, 7)]
            hw = next((d for d in range(0, K_WIN + 1) if m[K_WIN - d] - base < ex[0] / 2), None) if ex[0] > 0 and tz[zi, K_WIN] >= 200 else None
            zc = (Z_BANDS[zi] + Z_BANDS[zi + 1]) / 2
            cm = None if hw is None else round(hw * zc / fx * 100, 1)
            print(f"  {Z_BANDS[zi]:g}~{Z_BANDS[zi + 1]:g} m: 바탕 {base:.3f}, 초과 k=0..−6 " + " ".join(f"{e:+.2f}" for e in ex)
                  + f" | 반폭 {hw} 화소 ≈ {cm} cm (k=0 표본 {int(tz[zi, K_WIN])})")


def full_extent(res: list) -> None:
    """거리 구간마다 막 전체 폭: 장애물 쪽 |k| = d의 어긋남이 그 구간 바탕(라벨 경계 먼 곳)의 세션 부트스트랩 95% 구간 위끝 이하로 처음 드는 d.
    세션을 단위로 다시 뽑는다(장면마다 달라서). 그 d 화소를 구간 가운데 깊이에서 cm로도 낸다."""
    fx = float(np.median([r["fx"] for r in res]))
    rng = np.random.default_rng(0)
    for grp in ("t", "indoor", "all"):
        rr = [r for r in res if grp == "all" or r["group"] == grp]
        print(f"== {grp}: 막 전체 폭(장애물 쪽)")
        for zi in range(len(Z_BANDS) - 1):
            tz = sum(r["tot_z"][zi] for r in rr)
            bz = sum(r["bad_z"][zi] for r in rr)
            if tz[-1] < 200 or tz[K_WIN] < 200:
                print(f"  {Z_BANDS[zi]:g}~{Z_BANDS[zi + 1]:g} m: 표본 부족")
                continue
            m = bz / np.maximum(tz, 1)
            boots = []
            for _ in range(2000):
                pick = [rr[j] for j in rng.integers(0, len(rr), len(rr))]
                t_ = sum(x["tot_z"][zi][-1] for x in pick)
                boots.append(sum(x["bad_z"][zi][-1] for x in pick) / max(t_, 1))
            lo, hi = np.percentile(boots, [2.5, 97.5])
            ext = next((d for d in range(0, K_WIN + 1) if m[K_WIN - d] <= hi), None)
            zc = (Z_BANDS[zi] + Z_BANDS[zi + 1]) / 2
            prof = " ".join(f"{m[K_WIN - d]:.3f}" for d in range(0, K_WIN + 1))
            print(f"  {Z_BANDS[zi]:g}~{Z_BANDS[zi + 1]:g} m: 바탕 {m[-1]:.3f} [{lo:.3f}, {hi:.3f}], 어긋남 |k|=0..8 {prof} | 전체 폭 {ext} 화소"
                  + (f" ≈ {ext * zc / fx * 100:.1f} cm" if ext is not None else " (8화소 안에서 안 듦)"))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--near" in sys.argv:
        Z_BIN_BY = "near"
    if "--runtime-roles" in sys.argv:
        import label_band
        label_band.OBSTACLE_ROLE = np.array([0, 3, 7, 8, 9, 10])  # B+W5 frontend.bandLabels: 하늘(1)·물(11) 뺌
    runs = [Path(a) for a in args]
    res = [measure(r) for r in runs]
    by_distance(res)
    full_extent(res)
