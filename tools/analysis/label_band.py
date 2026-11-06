"""M54 0단계 측정(IMPROVE_SPEC §13 M54, 설계 3.1절): 재생 없이 평활 깊이·원시 깊이·라벨 영상을 core와 같은 짝짓기로 맞춰 잰다.

    python label_band.py <세션 폴더> ... [--out <json>]

깊이 장마다(그 장을 처음 전달한 프레임 기준) 그때까지 받은 가장 최근 원시 장과 라벨 영상을 고른다(지난 값만). 촬영 시각이 평활 장보다
`vision.maxLabelAgeMs` 넘게 오래된 원시 장·라벨 영상은 없는 것으로 본다. 자세는 각 촬영 시각에 가장 가까운, 이미 받은 프레임의 자세다.
평활 화소마다 월드 점을 원시 장과 라벨 영상에 투영한다(화소 = 내림, core `LabelImage.pixelAt`·`SlowPath.rawVisibility`와 같다).
- k: 장애물 역할 라벨 경계까지의 부호 있는 거리(깊이 화소, 장애물 역할 쪽이 음수). 경계 화소 = 4이웃과 라벨이 다르고 둘 중 하나가 장애물
  역할인 화소. 거리는 라벨 영상 화소 중심 사이 유클리드 거리(창 안)를 깊이 fx / 라벨 fx 비로 바꾼 값이다. 창 밖은 먼 곳.
- 불일치: 원시(신뢰도 ≥ `track.rawMinConfidence`)가 있고 |원시 − 점의 원시 카메라 깊이| > max(`map.sigmaMultiple`·σ원시(Z), 복셀).
잴 것: ① 라벨 번짐(사람 라벨인데 원시가 사람 앞면보다 몸 두께 + 2σ 넘게 먼 화소의 |k|) ② k별 막 몫 ③ k별 원시 비율 ④ 바닥 들뜸
(바닥류 라벨 화소의 평활 높이 − 원시 높이). 결정 규칙(설계 3.1절): b = 막 화소(|k| ≤ K_WIN)의 |k| p90 올림, 멈춤 = 먼 곳 바닥류 아닌
화소 불일치 > STOP_FAR(원시 확인) 또는 b = K_WIN(경계 띠).
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

from align import load_config, load_frames
from motion_stats import FLIP, _sem_k
from spike_check import quat_to_rot
from worldpts import depth_k, load_depth_m

# 분석 상수(설계 3.7절 표 5): 결과를 보기 전에 정했다
K_WIN = 8  # 경계 띠를 볼 창(깊이 화소) = 실측 평활 경사 2~4화소의 두 배
STOP_FAR = 0.10  # 먼 곳 불일치 멈춤 = 2σ 밖 4.6%의 두 배
PERSON_DEPTH_M = 0.30  # 몸 두께 = M52 앞면−중심 0.15 m의 두 배
DIST_EDGES = [1.0, 2.0, 3.0, 4.0, 5.0]  # 수평 거리 구간(m)
ABS_K_STEP = 0.05
LIFT_EDGES = np.round(np.arange(-1.0, 1.0001, 0.01), 2)

PERSON = 10
OBSTACLE_ROLE = np.array([0, 1, 3, 7, 8, 9, 10, 11])  # 라벨 없음·하늘·나무·구조물·물체·차량·사람·물(기본값 장애물)
BUILDING, FLOORISH = 2, (4, 5, 6)  # 건물, 도로·보도·지면
ROLES = ("obstacle", "building", "floorish")
N_KBIN = 2 * K_WIN + 2  # −K_WIN..K_WIN(반올림) + 먼 곳


def role_of(lab: np.ndarray) -> np.ndarray:
    """라벨 값 → 0 장애물, 1 건물, 2 바닥류."""
    out = np.zeros(lab.shape, dtype=np.int8)
    out[lab == BUILDING] = 1
    out[np.isin(lab, FLOORISH)] = 2
    return out


def signed_k(lab: np.ndarray, ratio: float, win: int = K_WIN) -> np.ndarray:
    """라벨 영상 화소마다 장애물 역할 경계까지의 부호 있는 거리(깊이 화소, 장애물 쪽 음수, 창 밖 inf)."""
    obs = np.isin(lab, OBSTACLE_ROLE)
    bnd = np.zeros(lab.shape, dtype=bool)
    for a, b, oa, ob in ((lab[:, 1:], lab[:, :-1], obs[:, 1:], obs[:, :-1]), (lab[1:, :], lab[:-1, :], obs[1:, :], obs[:-1, :])):
        e = (a != b) & (oa | ob)
        if a.shape[1] != lab.shape[1]:
            bnd[:, 1:] |= e
            bnd[:, :-1] |= e
        else:
            bnd[1:, :] |= e
            bnd[:-1, :] |= e
    h, w = lab.shape
    r = int(math.ceil(win / ratio)) + 1
    offs = sorted(((dy, dx) for dy in range(-r, r + 1) for dx in range(-r, r + 1) if math.hypot(dy, dx) <= r), key=lambda o: math.hypot(*o))
    dist = np.full((h, w), np.inf, dtype=np.float32)
    for dy, dx in offs:
        sh = np.zeros_like(bnd)  # sh[y, x] = bnd[y + dy, x + dx]
        sh[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)] = bnd[max(0, dy):h - max(0, -dy), max(0, dx):w - max(0, -dx)]
        m = sh & np.isinf(dist)
        dist[m] = math.hypot(dy, dx)
    k = dist * ratio
    k[obs] *= -1
    return k


def _pose(row) -> tuple[np.ndarray, np.ndarray]:
    """frames.csv 행 → (C_cv → 월드 회전, 카메라 위치)."""
    return quat_to_rot(np.array([[row.qx, row.qy, row.qz, row.qw]]))[0] @ FLIP, np.array([row.tx, row.ty, row.tz])


def _project(p: np.ndarray, rot: np.ndarray, cam: np.ndarray, k: dict):
    """월드 점 → (화소 u, v 내림, 카메라 깊이, 영상 안·앞)."""
    c = (p - cam) @ rot
    z = c[:, 2]
    ok = z > 0.1
    zs = np.where(ok, z, 1.0)
    u = np.floor(k["fx"] * c[:, 0] / zs + k["cx"]).astype(int)
    v = np.floor(k["fy"] * c[:, 1] / zs + k["cy"]).astype(int)
    ok &= (u >= 0) & (v >= 0) & (u < k["width"]) & (v < k["height"])
    return np.where(ok, u, 0), np.where(ok, v, 0), z, ok


class Acc:
    """세션(또는 합계)의 누적 수."""

    def __init__(self):
        nd = len(DIST_EDGES) - 1
        self.total = np.zeros((3, N_KBIN, nd), dtype=np.int64)
        self.has = np.zeros_like(self.total)
        self.bad = np.zeros_like(self.total)
        self.abs_k = np.zeros(int(round(K_WIN / ABS_K_STEP)) + 1, dtype=np.int64)  # 막 화소 |k| 히스토그램
        self.over_k = np.zeros((nd, len(self.abs_k)), dtype=np.int64)  # 라벨 번짐 화소 |k|(사람 앞면 거리 구간별)
        self.person = np.zeros((nd, 2), dtype=np.int64)  # (원시 있는 사람 화소, 그중 번짐)
        self.lift = np.zeros((nd, len(LIFT_EDGES) + 1), dtype=np.int64)
        self.frames = 0
        self.nolabel = 0

    def add(self, o: "Acc"):
        for f in ("total", "has", "bad", "abs_k", "over_k", "person", "lift"):
            setattr(self, f, getattr(self, f) + getattr(o, f))
        self.frames += o.frames
        self.nolabel += o.nolabel


def _dbin(d: np.ndarray) -> np.ndarray:
    return np.digitize(d, DIST_EDGES) - 1  # −1 = 1 m 안, len−1 이상 = 5 m 밖


def _kbin(k: np.ndarray) -> np.ndarray:
    far = ~np.isfinite(k) | (np.abs(k) > K_WIN)
    return np.where(far, N_KBIN - 1, np.clip(np.rint(np.where(far, 0, k)).astype(int) + K_WIN, 0, 2 * K_WIN))


def measure(session: Path, cfg: dict) -> Acc:
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = load_frames(session).reset_index(drop=True)
    sem = pd.read_csv(session / "semantic" / "index.csv").sort_values("frameTNs").reset_index(drop=True)
    max_age = cfg["vision"]["maxLabelAgeMs"] * 1_000_000
    s0, s2 = cfg["depth"]["rawSigma0M"], cfg["depth"]["rawSigmaPerM2"]
    sm, vox = cfg["map"]["sigmaMultiple"], cfg["map"]["voxelSizeM"]
    min_conf, min_n = cfg["track"]["rawMinConfidence"], cfg["cluster"]["minSamples"]
    radius = cfg["map"]["radiusM"]
    ft = fr.tNs.to_numpy()
    acc = Acc()
    raw_file = None
    seen_depth = set()
    lab_cache: dict = {}
    nd = len(DIST_EDGES) - 1

    def pose_at(t_ns: int, upto: int):
        j = int(np.argmin(np.abs(ft[:upto + 1] - t_ns)))
        return _pose(fr.iloc[j])

    for i, row in fr.iterrows():
        if isinstance(row.rawDepthFile, str):
            raw_file = (int(row.rawDepthTNs), row.rawDepthFile, row.confFile, i)
        if not isinstance(row.depthFile, str) or row.depthTNs in seen_depth or row.tracking != "TRACKING":
            continue
        seen_depth.add(row.depthTNs)
        t_d = int(row.depthTNs)
        zs = load_depth_m(session / row.depthFile)
        kd = depth_k(meta, zs.shape[1], zs.shape[0])
        rs, cs = pose_at(t_d, i)
        v, u = np.nonzero(zs > 0)
        z = zs[v, u]
        p = np.stack([(u - kd["cx"]) / kd["fx"] * z, (v - kd["cy"]) / kd["fy"] * z, z], axis=1) @ rs.T + cs
        dh = np.hypot(p[:, 0] - cs[0], p[:, 2] - cs[2])
        keep = dh <= radius
        p, dh = p[keep], dh[keep]
        db = _dbin(dh)
        acc.frames += 1
        # 라벨 영상
        li = int(np.searchsorted(sem.frameTNs.to_numpy(), int(row.tNs), side="right")) - 1
        if li < 0 or t_d - int(sem.imageTNs.iloc[li]) > max_age:
            acc.nolabel += 1
            continue
        e = sem.iloc[li]
        if int(e.imageTNs) not in lab_cache:
            lab = np.array(Image.open(session / e.file))
            kl = _sem_k(meta, lab.shape[1], lab.shape[0])
            lab_cache = {int(e.imageTNs): (lab, kl, signed_k(lab, kd["fx"] / kl["fx"]), pose_at(int(e.imageTNs), i))}
        lab, kl, kimg, (rl, cl) = lab_cache[int(e.imageTNs)]
        ul, vl, _, okl = _project(p, rl, cl, kl)
        labv = np.where(okl, lab[vl, ul].astype(int), -1)
        kk = np.where(okl, kimg[vl, ul], np.inf)
        role = role_of(labv)
        # 원시 장
        has = np.zeros(len(p), dtype=bool)
        bad = np.zeros(len(p), dtype=bool)
        zr = np.zeros(len(p), dtype=np.float32)
        pr_w = None
        if raw_file is not None and t_d - raw_file[0] <= max_age:
            raw = load_depth_m(session / raw_file[1])
            conf = np.array(Image.open(session / raw_file[2])) if isinstance(raw_file[2], str) else np.full(raw.shape, 255)
            kr = depth_k(meta, raw.shape[1], raw.shape[0])
            rr, cr = pose_at(raw_file[0], i)
            ur, vr, zc, okr = _project(p, rr, cr, kr)
            zr = np.where(okr, raw[vr, ur], 0)
            has = okr & (zr > 0) & (np.where(okr, conf[vr, ur], 0) >= min_conf)
            tol = np.maximum(sm * (s0 + s2 * zc * zc), vox)
            bad = has & (np.abs(zr - zc) > tol)
            pr_w = np.stack([(ur - kr["cx"]) / kr["fx"] * zr, (vr - kr["cy"]) / kr["fy"] * zr, zr], axis=1) @ rr.T + cr
        lab_ok = okl & (db >= 0) & (db < nd)
        kb = _kbin(kk)
        for arr, m in ((acc.total, lab_ok), (acc.has, lab_ok & has), (acc.bad, lab_ok & bad)):
            np.add.at(arr, (role[m], kb[m], db[m]), 1)
        near = lab_ok & bad & np.isfinite(kk) & (np.abs(kk) <= K_WIN)
        np.add.at(acc.abs_k, np.rint(np.abs(kk[near]) / ABS_K_STEP).astype(int), 1)
        # ① 라벨 번짐: 사람 라벨·원시 있는 화소, 앞면 = 원시 깊이 아래 사분위
        pm = lab_ok & has & (labv == PERSON)
        if pm.sum() >= min_n:
            front = float(np.quantile(zr[pm], 0.25))
            fb = int(_dbin(np.array([front]))[0])
            if 0 <= fb < nd:
                over = pm & (zr > front + PERSON_DEPTH_M + sm * (s0 + s2 * front * front))
                acc.person[fb] += (int(pm.sum()), int(over.sum()))
                ko = kk[over]
                ko = np.abs(ko[np.isfinite(ko)])
                np.add.at(acc.over_k[fb], np.clip(np.rint(ko / ABS_K_STEP).astype(int), 0, acc.over_k.shape[1] - 1), 1)
        # ④ 바닥 들뜸: 바닥류 화소의 평활 높이 − 원시 높이
        if pr_w is not None:
            fm = lab_ok & has & (role == 2)
            lift = p[fm, 1] - pr_w[fm, 1]
            np.add.at(acc.lift, (db[fm], np.digitize(lift, LIFT_EDGES)), 1)
    return acc


def _q(hist: np.ndarray, q: float, step: float) -> float | None:
    n = hist.sum()
    if n == 0:
        return None
    return float(np.searchsorted(np.cumsum(hist), q * n) * step)


LIFT_CENTERS = np.concatenate([[LIFT_EDGES[0]], (LIFT_EDGES[:-1] + LIFT_EDGES[1:]) / 2, [LIFT_EDGES[-1]]])  # np.digitize 칸 → 값(양 끝은 끝값)


def _lift_median(h: np.ndarray) -> float | None:
    n = h.sum()
    return None if n == 0 else round(float(LIFT_CENTERS[int(np.searchsorted(np.cumsum(h), 0.5 * n))]), 3)


def summarize(a: Acc) -> dict:
    nd = len(DIST_EDGES) - 1
    dist = [f"{DIST_EDGES[j]:g}-{DIST_EDGES[j + 1]:g}" for j in range(nd)]
    kl = [str(k) for k in range(-K_WIN, K_WIN + 1)] + ["far"]

    def share(num, den):
        return None if den == 0 else round(float(num) / float(den), 4)

    profile = {r: {kl[b]: {"membrane": share(a.bad[ri, b].sum(), a.has[ri, b].sum()), "raw": share(a.has[ri, b].sum(), a.total[ri, b].sum()),
                           "n": int(a.total[ri, b].sum())} for b in range(N_KBIN)} for ri, r in enumerate(ROLES)}
    by_dist = {r: {dist[j]: {"membraneNear": share(a.bad[ri, :-1, j].sum(), a.has[ri, :-1, j].sum()),
                             "membraneFar": share(a.bad[ri, -1, j].sum(), a.has[ri, -1, j].sum()),
                             "rawNear": share(a.has[ri, :-1, j].sum(), a.total[ri, :-1, j].sum()),
                             "rawFar": share(a.has[ri, -1, j].sum(), a.total[ri, -1, j].sum())} for j in range(nd)} for ri, r in enumerate(ROLES)}
    far_nonfloor = share(a.bad[:2, -1].sum(), a.has[:2, -1].sum())
    p90 = _q(a.abs_k, 0.9, ABS_K_STEP)
    b = None if p90 is None else int(math.ceil(p90 - 1e-9))
    over = {dist[j]: {"personRaw": int(a.person[j, 0]), "overhangShare": share(a.person[j, 1], a.person[j, 0]),
                      "absKp50": _q(a.over_k[j], 0.5, ABS_K_STEP), "absKp90": _q(a.over_k[j], 0.9, ABS_K_STEP)} for j in range(nd)}
    lift = {dist[j]: {"median": _lift_median(a.lift[j]), "n": int(a.lift[j].sum())} for j in range(nd)}
    return {
        "frames": a.frames, "noLabelFrames": a.nolabel,
        "decision": {"b": b, "absKp90": p90, "farMembraneNonFloor": far_nonfloor,
                     "stopRawCheck": far_nonfloor is not None and far_nonfloor > STOP_FAR, "stopBand": b is not None and b >= K_WIN},
        "overhang": over, "lift": lift, "profile": profile, "byDistance": by_dist,
    }


def main(args: list[str]) -> None:
    out = None
    if "--out" in args:
        i = args.index("--out")
        out = Path(args[i + 1])
        args = args[:i] + args[i + 2:]
    cfg = load_config(Path("."))
    total = Acc()
    res = {"sessions": {}}
    for s in args:
        a = measure(Path(s), cfg)
        total.add(a)
        res["sessions"][Path(s).name] = summarize(a)
        d = res["sessions"][Path(s).name]["decision"]
        print(f"{Path(s).name}: frames {a.frames}, b {d['b']}, far {d['farMembraneNonFloor']}", flush=True)
    res["total"] = summarize(total)
    d = res["total"]["decision"]
    print(f"TOTAL: b {d['b']} (|k| p90 {d['absKp90']}), far membrane non-floor {d['farMembraneNonFloor']}, "
          f"stop raw check {d['stopRawCheck']}, stop band {d['stopBand']}")
    for r in ROLES:
        prof = res["total"]["profile"][r]
        print(r, " ".join(f"{k}:{v['membrane']}/{v['raw']}" for k, v in prof.items() if v["n"] > 0))
    print("lift", {k: v["median"] for k, v in res["total"]["lift"].items()})
    print("overhang", {k: (v["overhangShare"], v["absKp50"], v["absKp90"]) for k, v in res["total"]["overhang"].items()})
    if out:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv[1:])
