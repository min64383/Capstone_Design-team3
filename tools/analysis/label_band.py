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

import event_eval as E
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


def measure(session: Path, cfg: dict, ref_conf: int | None = None, static_only: bool = False) -> Acc:
    """[ref_conf]: 기준 원시의 신뢰도 하한(없으면 `track.rawMinConfidence`). [static_only]: 움직일 수 있는 라벨 화소를 뺀다(설계 재승인 3.1절의 b 측정)."""
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = load_frames(session).reset_index(drop=True)
    sem = pd.read_csv(session / "semantic" / "index.csv").sort_values("frameTNs").reset_index(drop=True)
    max_age = cfg["vision"]["maxLabelAgeMs"] * 1_000_000
    s0, s2 = cfg["depth"]["rawSigma0M"], cfg["depth"]["rawSigmaPerM2"]
    sm, vox = cfg["map"]["sigmaMultiple"], cfg["map"]["voxelSizeM"]
    min_conf, min_n = cfg["track"]["rawMinConfidence"], cfg["cluster"]["minSamples"]
    ref = min_conf if ref_conf is None else ref_conf
    movable = [l for l in (9, 10)]  # 차량·사람(vision.movingLabels)
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
            has = okr & (zr > 0) & (np.where(okr, conf[vr, ur], 0) >= ref)
            tol = np.maximum(sm * (s0 + s2 * zc * zc), vox)
            bad = has & (np.abs(zr - zc) > tol)
            pr_w = np.stack([(ur - kr["cx"]) / kr["fx"] * zr, (vr - kr["cy"]) / kr["fy"] * zr, zr], axis=1) @ rr.T + cr
        lab_ok = okl & (db >= 0) & (db < nd)
        if static_only:
            lab_ok &= ~np.isin(labv, movable)
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


# --- 정답 대비 원시·평활 짝 비교(0단계 멈춤 뒤 사용자 결정 2026-11-06): 두 깊이가 다를 때 어느 쪽이 맞는가 ---
CONF_EDGES = [1, 30, 128, 256]  # 원시 신뢰도 구간(0 = 원시 없음)
WALL_HALF_HEIGHT_M = 0.8  # 벽 정답 화소: 광선이 벽과 만나는 높이가 카메라 ± 이 값(바닥·천장·벽 위 끝 밖)
WALL_SIDE_MARGIN_M = 0.05  # K01 옆 벽 모서리에서 뗄 폭


def _static_frame(fr: pd.DataFrame, window_s: float):
    """정지 세션의 정답 틀: 처음 [window_s]초 카메라 위치 중앙값과 수평 정면(C_cv +Z) 평균."""
    w = fr[(fr.tNs - fr.tNs.iloc[0]) / 1e9 <= window_s]
    fwd = []
    for row in w.itertuples():
        r, _ = _pose(row)
        f = r @ np.array([0.0, 0.0, 1.0])
        fwd.append([f[0], 0.0, f[2]])
    h = np.mean(fwd, axis=0)
    return np.median(w[["tx", "ty", "tz"]].to_numpy(), axis=0), h / np.linalg.norm(h)


def _face_target(session: Path):
    """받침대 앞면(T 클릭 정답, 검증 안 한 가정: 앞면은 바닥 접점 선을 지나는 수직 평면): `events.json`의 정지 선 물체(검증용 빼고)의
    바닥 접점 클릭 점들을 위에서 본 직선으로 맞춘다. 화소는 그 점의 라벨이 구조물이고 선분 안·바닥 위인 것만."""
    ev = session / "annotations" / "events.json"
    if not ev.is_file():
        return None
    objs = [o for o in json.loads(ev.read_text(encoding="utf-8"))["objects"] if o["kind"] == "static" and o.get("shape") == "line" and o.get("added") != "check"]
    if not objs:
        return None
    pts = np.array([c["pointW"] for c in objs[0]["clicks"]])
    xz = pts[:, [0, 2]]
    c = xz.mean(axis=0)
    a = np.linalg.svd(xz - c)[2][0]  # 선 방향
    s = (xz - c) @ a
    return {"kind": "face", "p0": np.array([c[0], 0.0, c[1]]), "n": np.array([-a[1], 0.0, a[0]]), "c": c, "a": a,
            "lo": float(s.min()) + WALL_SIDE_MARGIN_M, "hi": float(s.max()) - WALL_SIDE_MARGIN_M, "floorY": float(np.median(pts[:, 1]))}


def _truth_targets(session: Path, fr: pd.DataFrame, cfg: dict, face: bool = False):
    """정답 면: 정면 벽(K01·O01: `annotations/obstacles.json`의 wall_end, 카메라에서 잰 거리) 또는 바닥(`floor_rgb.json`의 RGB 바닥).
    [face]면 받침대 앞면([_face_target])."""
    if face:
        return _face_target(session)
    ann = session / "annotations" / "obstacles.json"
    if not ann.is_file():
        alt = Path(str(session).replace("data/sessions", "testdata/sessions").replace("data\\sessions", "testdata\\sessions")) / "annotations" / "obstacles.json"
        ann = alt if alt.is_file() else ann
    if ann.is_file():
        a = json.loads(ann.read_text(encoding="utf-8"))
        ends = [o for o in a.get("obstacles", []) if o["name"] == "wall_end"]
        if ends and a.get("distanceFrom") == "camera":
            cam0, h = _static_frame(fr, cfg["align"]["headingWindowS"])
            o = ends[0]
            side = [s for s in a["obstacles"] if s["name"] in ("wall_left", "wall_right")]
            half = (min(abs(s["max"][0]) if s["max"][0] < 0 else abs(s["min"][0]) for s in side) - WALL_SIDE_MARGIN_M) if side else max(abs(o["min"][0]), abs(o["max"][0]))
            return {"kind": "wall", "p0": cam0 + o["min"][2] * h, "n": h, "cam0": cam0, "half": half}
    f = session / "floor_rgb.json"
    fy = json.loads(f.read_text(encoding="utf-8")).get("floorY") if f.is_file() else None
    ev = session / "annotations" / "events.json"
    if fy is None and ev.is_file():  # 서 있는 세션: 클릭에 쓴 바닥(깊이 바닥 중앙값, 허리·발 클릭 짝 검증 p95 0.03~0.17 m, M47)
        fy = json.loads(ev.read_text(encoding="utf-8")).get("floorYFallback")
    if fy is not None:
        return {"kind": "floor", "p0": np.array([0.0, fy, 0.0]), "n": np.array([0.0, 1.0, 0.0])}
    return None


def _rays(k: dict, rot: np.ndarray, shape):
    """화소마다 월드 광선 방향(카메라 깊이 1 m에 해당하는 길이)과 화소 좌표."""
    v, u = np.mgrid[0:shape[0], 0:shape[1]]
    d = np.stack([(u - k["cx"]) / k["fx"], (v - k["cy"]) / k["fy"], np.ones(shape)], axis=-1).reshape(-1, 3) @ rot.T
    return d, u.ravel(), v.ravel()


def truth_pairs(session: Path, cfg: dict, face: bool = False, lower: bool = False) -> pd.DataFrame | None:
    """원시 장마다, 광선이 정답 면에 닿는 화소(깊이 값과 무관하게 고름)의 정답 깊이와 원시·평활 깊이를 짝짓는다. 평활은 촬영 시각이
    가장 가까운 장에서 같은 정답 점을 투영해 읽는다(정지 면이라 시각 차는 자세로 맞춰짐). 바닥은 그 점의 라벨이 바닥류, 받침대 앞면은 구조물인 화소만."""
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = load_frames(session).reset_index(drop=True)
    tg = _truth_targets(session, fr, cfg, face)
    if tg is None:
        return None
    ft = fr.tNs.to_numpy()
    sm = fr[fr.depthFile.notna()].drop_duplicates("depthTNs")
    sm_t = sm.depthTNs.to_numpy()
    sem = pd.read_csv(session / "semantic" / "index.csv").sort_values("frameTNs").reset_index(drop=True) if (session / "semantic" / "index.csv").is_file() else None
    rows = []

    def pose_at(t):
        return _pose(fr.iloc[int(np.argmin(np.abs(ft - t)))])

    for r in fr[fr.rawDepthFile.notna()].drop_duplicates("rawDepthTNs").itertuples():
        if r.tracking != "TRACKING" or not isinstance(r.confFile, str):
            continue
        zr = load_depth_m(session / r.rawDepthFile)
        conf = np.array(Image.open(session / r.confFile))
        kr = depth_k(meta, zr.shape[1], zr.shape[0])
        rr, cr = pose_at(r.rawDepthTNs)
        d, u, v = _rays(kr, rr, zr.shape)
        if lower:  # 아래 절반: 광선의 내려다보는 각이 그 장 중앙값 이상인 화소(세로로 든 폰이라 영상 행이 아니라 월드 각으로)
            elev = d[:, 1] / np.hypot(d[:, 0], d[:, 2])
            low = elev <= np.median(elev)
        den = d @ tg["n"]
        t = np.where(np.abs(den) > 1e-6, ((tg["p0"] - cr) @ tg["n"]) / np.where(np.abs(den) > 1e-6, den, 1.0), -1.0)
        x = cr + t[:, None] * d
        dh = np.hypot(x[:, 0] - cr[0], x[:, 2] - cr[2])
        if tg["kind"] == "wall":
            lat = (x - tg["cam0"]) @ np.cross(tg["n"], [0.0, 1.0, 0.0])
            sel = (t > 0.1) & (np.abs(lat) <= tg["half"]) & (np.abs(x[:, 1] - cr[1]) <= WALL_HALF_HEIGHT_M)
        else:
            if tg["kind"] == "face":
                s_ = (x[:, [0, 2]] - tg["c"]) @ tg["a"]
                sel = (t > 0.1) & (s_ >= tg["lo"]) & (s_ <= tg["hi"]) & (x[:, 1] > tg["floorY"] + WALL_SIDE_MARGIN_M)
                want = (7,)  # 구조물
            else:
                sel = (t > 0.1) & (dh >= DIST_EDGES[0]) & (dh < DIST_EDGES[-1])
                want = FLOORISH
            if sem is None:
                continue
            li = int(np.searchsorted(sem.frameTNs.to_numpy(), int(r.tNs), side="right")) - 1
            if li < 0:
                continue
            lab = np.array(Image.open(session / sem.file.iloc[li]))
            kl = _sem_k(meta, lab.shape[1], lab.shape[0])
            rl, cl = pose_at(int(sem.imageTNs.iloc[li]))
            ul, vl, _, okl = _project(x, rl, cl, kl)
            sel &= okl & np.isin(np.where(okl, lab[vl, ul], 0), want)
        if lower:
            sel &= low
        if not sel.any():
            continue
        j = int(np.argmin(np.abs(sm_t - r.rawDepthTNs)))
        srow = sm.iloc[j]
        zs = load_depth_m(session / srow.depthFile)
        ks = depth_k(meta, zs.shape[1], zs.shape[0])
        rs, cs = pose_at(srow.depthTNs)
        xs = x[sel]
        us, vs, zexp_s, oks = _project(xs, rs, cs, ks)
        zsv = np.where(oks, zs[vs, us], 0)
        zrv = zr[v[sel], u[sel]]
        cfv = np.where(zrv > 0, conf[v[sel], u[sel]], 0)
        rows.append(pd.DataFrame({"t": r.rawDepthTNs, "dist": dh[sel], "zexp": t[sel], "conf": cfv, "eRaw": np.where(zrv > 0, zrv - t[sel], np.nan),
                                  "eSm": np.where(oks & (zsv > 0), zsv - zexp_s, np.nan)}))
    return pd.concat(rows, ignore_index=True) if rows else None


def truth_summary(df: pd.DataFrame, cfg: dict) -> list[dict]:
    """거리·신뢰도 구간마다 원시·평활 오차(같은 점), 둘이 어긋날 때 원시가 정답에 더 가까운 몫, 원시 없는 점의 평활 오차."""
    s0, s2, smul, vox = cfg["depth"]["rawSigma0M"], cfg["depth"]["rawSigmaPerM2"], cfg["map"]["sigmaMultiple"], cfg["map"]["voxelSizeM"]
    out = []
    df = df.assign(tol=np.maximum(smul * (s0 + s2 * df.zexp ** 2), vox), db=np.digitize(df.dist, DIST_EDGES) - 1,
                   cb=np.where(df.conf > 0, np.digitize(df.conf, CONF_EDGES) - 1, -1))
    for j in range(len(DIST_EDGES) - 1):
        for c in range(-1, len(CONF_EDGES) - 1):
            g = df[(df.db == j) & (df.cb == c) & df.eSm.notna()]
            if len(g) < 50:
                continue
            row = {"dist": f"{DIST_EDGES[j]:g}-{DIST_EDGES[j + 1]:g}", "conf": "none" if c < 0 else f"{CONF_EDGES[c]}-{CONF_EDGES[c + 1] - 1}", "n": len(g),
                   "smBias": round(float(g.eSm.median()), 3), "smSigma": round(float(1.4826 * (g.eSm - g.eSm.median()).abs().median()), 3),
                   "smWithinTol": round(float((g.eSm.abs() <= g.tol).mean()), 3)}
            if c >= 0:
                dis = (g.eRaw - g.eSm).abs() > g.tol
                row |= {"rawBias": round(float(g.eRaw.median()), 3), "rawSigma": round(float(1.4826 * (g.eRaw - g.eRaw.median()).abs().median()), 3),
                        "rawWithinTol": round(float((g.eRaw.abs() <= g.tol).mean()), 3), "disagree": round(float(dis.mean()), 3),
                        "rawCloserWhenDisagree": round(float((g.eRaw.abs() < g.eSm.abs())[dis].mean()), 3) if dis.any() else None}
            out.append(row)
    return out


PERSON_HALF_WIDTH_M = 0.30  # 사람 정답 화소: 몸 중심 수직면에서 좌우 이 폭 안(라벨이 사람인 화소만이라 넉넉히)


def person_truth(session: Path, source: str) -> pd.DataFrame:
    """다가오는 사람의 아래 절반(바닥 + 5 cm ~ 허리 클릭 높이) 정답 비교(사용자 요청 2026-11-06). 깊이 장마다 그 장 촬영 시각의 클릭 보간 위치를
    지나고 카메라를 마주 보는 수직면을 몸 중심면으로 둔다(앞면 거리는 원시로 잰 M52 값이라 쓰지 않음). 광선이 그 면과 만나는 점이 좌우
    `PERSON_HALF_WIDTH_M`·높이 범위 안이고 촬영 시각에 가장 가까운 라벨 영상에서 사람인 화소만(깊이 값과 무관하게 고름). e = 측정 깊이 − 중심면 깊이."""
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = load_frames(session).reset_index(drop=True)
    ev = E.load_events(session)
    objs = [o for o in (ev or {}).get("objects", []) if o["kind"] == "moving" and o.get("added") != "check"]
    if not objs:
        return pd.DataFrame()
    o = objs[0]
    tr = E.moving_track(o)
    floor_y = float(np.median([c["floorY"] for c in o["clicks"]]))
    waist_y = floor_y + float(o["heightM"])
    sem = pd.read_csv(session / "semantic" / "index.csv").sort_values("imageTNs").reset_index(drop=True)
    col_t, col_f = ("depthTNs", "depthFile") if source == "SMOOTHED" else ("rawDepthTNs", "rawDepthFile")
    ft = fr.tNs.to_numpy()
    rows, last = [], -1
    for i, row in fr.iterrows():
        if not isinstance(row[col_f], str) or row.tracking != "TRACKING" or row[col_t] <= last:
            continue
        last = row[col_t]
        tn = int(row[col_t])
        got = tr(tn / 1e9)
        if got is None:
            continue
        pos, _ = got
        rot, cam = _pose(fr.iloc[int(np.argmin(np.abs(ft[:i + 1] - tn)))])
        p0 = np.array([pos[0], floor_y, pos[1]])
        hd = float(np.hypot(p0[0] - cam[0], p0[2] - cam[2]))
        if hd < 0.3:
            continue
        n = np.array([(p0[0] - cam[0]) / hd, 0.0, (p0[2] - cam[2]) / hd])
        z = load_depth_m(session / row[col_f])
        k = depth_k(meta, z.shape[1], z.shape[0])
        d, u, v = _rays(k, rot, z.shape)
        den = d @ n
        t = np.where(den > 1e-6, ((p0 - cam) @ n) / np.where(den > 1e-6, den, 1.0), -1.0)
        x = cam + t[:, None] * d
        lat = (x - p0) @ np.cross(n, [0.0, 1.0, 0.0])
        sel = (t > 0.1) & (np.abs(lat) <= PERSON_HALF_WIDTH_M) & (x[:, 1] >= floor_y + WALL_SIDE_MARGIN_M) & (x[:, 1] <= waist_y)
        li = int(np.argmin(np.abs(sem.imageTNs.to_numpy() - tn)))
        lab = np.array(Image.open(session / sem.file.iloc[li]))
        kl = _sem_k(meta, lab.shape[1], lab.shape[0])
        rl, cl = _pose(fr.iloc[int(np.argmin(np.abs(ft - int(sem.imageTNs.iloc[li]))))])
        ul, vl, _, okl = _project(x, rl, cl, kl)
        sel &= okl & (np.where(okl, lab[vl, ul], 0) == PERSON)
        if not sel.any():
            continue
        zm = z[v[sel], u[sel]]
        conf = np.array(Image.open(session / row.confFile))[v[sel], u[sel]] if source == "RAW" and isinstance(row.confFile, str) else np.full(zm.shape, 255)
        rows.append(pd.DataFrame({"t": tn / 1e9, "hd": hd, "conf": np.where(zm > 0, conf, 0), "e": np.where(zm > 0, zm - t[sel], np.nan)}))
    return pd.concat(rows, ignore_index=True) if rows else pd.DataFrame()


def main_person(args: list[str]) -> None:
    out = {}
    for src in ("SMOOTHED", "RAW"):
        df = pd.concat([person_truth(Path(s), src).assign(session=Path(s).name[9:15]) for s in args], ignore_index=True)
        df["db"] = np.digitize(df.hd, DIST_EDGES) - 1
        groups = [("all", df)] if src == "SMOOTHED" else [("conf 1-29", df[(df.conf >= 1) & (df.conf < 30)]), ("conf 30-127", df[(df.conf >= 30) & (df.conf < 128)]),
                                                          ("conf 128-255", df[df.conf >= 128]), ("none", df[df.conf == 0])]
        for gname, g in groups:
            for j in range(len(DIST_EDGES) - 1):
                h = g[g.db == j]
                if len(h) < 30:
                    continue
                e = h.e.dropna()
                key = f"{src} {gname} {DIST_EDGES[j]:g}-{DIST_EDGES[j + 1]:g}m"
                out[key] = {"n": len(h), "valid": round(float(h.e.notna().mean()), 3),
                            "eP10": round(float(e.quantile(0.1)), 3) if len(e) else None, "eMedian": round(float(e.median()), 3) if len(e) else None,
                            "eP90": round(float(e.quantile(0.9)), 3) if len(e) else None,
                            "within0to-0.3": round(float(((e <= 0.05) & (e >= -0.3)).mean()), 3) if len(e) else None}
    print(pd.DataFrame(out).T.to_string())
    Path("../../data/sweeps/m54").mkdir(parents=True, exist_ok=True)
    Path("../../data/sweeps/m54/truth_person_lower.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")


def main_truth(args: list[str]) -> None:
    out = None
    if "--out" in args:
        i = args.index("--out")
        out = Path(args[i + 1])
        args = args[:i] + args[i + 2:]
    cfg = load_config(Path("."))
    groups: dict[str, list] = {}
    for s in args:
        name, s = s.split("=", 1) if "=" in s else ("all", s)
        face = name.endswith("face")  # 이름이 face로 끝나면 받침대 앞면, lower로 끝나면 아래 절반 화소만
        df = truth_pairs(Path(s), cfg, face, name.endswith("lower"))
        print(f"{Path(s).name}: {0 if df is None else len(df)} points", flush=True)
        if df is not None:
            groups.setdefault(name, []).append(df)
    res = {g: truth_summary(pd.concat(v, ignore_index=True), cfg) for g, v in groups.items()}
    for g, rows in res.items():
        print(f"== {g}")
        print(pd.DataFrame(rows).to_string(index=False))
    if out:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


def _offset(df: pd.DataFrame) -> float:
    """정답의 상수 오차(줄자 기준점·바닥 높이) 추정: 그 세션에서 원시 신뢰도 ≥ 128인 자리 평활 오차의 중앙값(없으면 0)."""
    hi = df[(df.conf >= 128) & df.eSm.notna()].eSm
    return float(hi.median()) if len(hi) >= 50 else 0.0


def sigma_table(vertical: list[tuple[Path, bool]], floor: list[Path], person: list[Path], cfg: dict) -> dict:
    """신뢰도 무게의 σ 표(설계 재승인 3.1절): 면 종류(바닥 / 그 밖의 정지 면) × 그 자리 원시 신뢰도 구간 × 1 m 거리마다 평활 오차의
    강건 크기 1.4826 × 중앙값 |e − 세션 상수 오차|(치우침과 흩어짐을 함께 담는다). 움직이는 라벨은 사람 다리 원시(≥ 30) 오차의 강건 σ
    (몸 앞면 차는 오차가 아니므로 거리별 중앙값을 뺀다). σ기준 = 1~2 m 정답 비교 표본 전체(두 면 종류)의 같은 값."""
    def collect(items):
        out = []
        for s, face in items:
            df = truth_pairs(s, cfg, face)
            if df is not None and len(df):
                out.append(df.assign(r=(df.eSm - _offset(df)).abs()))
        return pd.concat(out, ignore_index=True)
    kinds = {"vertical": collect(vertical), "floor": collect([(s, False) for s in floor])}
    nd = len(DIST_EDGES) - 1
    classes = [("none", lambda c: c == 0), ("1-29", lambda c: (c >= 1) & (c < 30)), ("30-127", lambda c: (c >= 30) & (c < 128)), ("128-255", lambda c: c >= 128)]
    table = {}
    for kname, df in kinds.items():
        db = np.digitize(df.dist, DIST_EDGES) - 1
        table[kname] = {}
        for cname, f in classes:
            row = []
            for j in range(nd):
                r = df.r[(db == j) & f(df.conf) & df.r.notna()]
                row.append(round(float(1.4826 * r.median()), 3) if len(r) >= 200 else None)
            table[kname][cname] = row
    pooled = pd.concat([d.r[(np.digitize(d.dist, DIST_EDGES) - 1 == 0) & d.r.notna()] for d in kinds.values()])
    sigma_ref = round(float(1.4826 * pooled.median()), 3)
    pdf = pd.concat([person_truth(s, "RAW") for s in person], ignore_index=True)
    pdf = pdf[(pdf.conf >= 30) & pdf.e.notna()]
    pdb = np.digitize(pdf.hd, DIST_EDGES) - 1
    mov = []
    for j in range(nd):
        e = pdf.e[pdb == j]
        mov.append(round(float(1.4826 * (e - e.median()).abs().median()), 3) if len(e) >= 200 else None)
    # 사용자 결정(2026-11-06, 재생 전): 거리를 나누지 않고 그 자리 원시 신뢰도 구간만으로 — 면 종류마다 1~5 m 합친 σ, 무게 =
    # (가장 정확한 구간(≥ 128)의 σ / 그 구간 σ)²를 [지금 거리 무게의 반경 끝 값, 1]로 자름. 지도(바닥 위 칸)는 그 밖의 정지 면 표, 바닥 히스토그램은 바닥 표
    w_min = (cfg["map"]["weightRefM"] / cfg["map"]["radiusM"]) ** 2
    pooled_w = {}
    for kname, df in kinds.items():
        d = df[(df.dist >= DIST_EDGES[0]) & (df.dist < DIST_EDGES[-1]) & df.r.notna()]
        sig = {cname: float(1.4826 * d.r[f(d.conf)].median()) for cname, f in classes}
        pooled_w[kname] = {"sigmaM": {c: round(s, 3) for c, s in sig.items()},
                           "weight": {c: round(min(1.0, max(w_min, (sig["128-255"] / s) ** 2)), 2) for c, s in sig.items()},
                           "pixelShare": {cname: round(float(f(d.conf).mean()), 3) for cname, f in classes}}
    return {"distEdgesM": DIST_EDGES, "confClasses": [c for c, _ in classes], "sigmaRefM": sigma_ref, "table": table, "movableRawMinConf30": mov,
            "confidenceOnly": pooled_w, "weightMin": round(w_min, 3)}


SPREAD_IN_M, SPREAD_OUT_M, SPREAD_MAX_M = 0.3, 1.0, 3.5  # 설계 5장: 사람 반폭(약 0.23 m) 밖 ~ 사용자가 본 거리, 정답 중심이 3.5 m 안인 장
BLOCK_S, BOOT = 0.5, 2000


def _spread_series(session: Path, run: Path) -> pd.DataFrame:
    """사람 둘레 퍼짐 몫(설계 5장): 장마다 지도에 넣은 칸(`map_inserts.csv`) 중 그 장 시각의 클릭 정답 중심에서 수평 1.0 m 안이고 바닥 위 0~2 m인 칸에서
    0.3~1.0 m 고리에 든 몫. 정답 중심이 카메라에서 3.5 m 안인 장만, 1 m 안 칸이 5개 이상인 장만."""
    ins = pd.read_csv(run / "map_inserts.csv")
    cfg = load_config(run)
    vs = cfg["map"]["voxelSizeM"]
    ev = E.load_events(session)
    objs = [o for o in (ev or {}).get("objects", []) if o["kind"] == "moving" and o.get("added") != "check"]
    if not objs:
        return pd.DataFrame()
    tr = E.moving_track(objs[0])
    floor_y = float(np.median([c["floorY"] for c in objs[0]["clicks"]]))
    fr = load_frames(session)
    ft = fr.tNs.to_numpy()
    rows = []
    for tn, g in ins.groupby("tCaptureNs"):
        got = tr(tn / 1e9)
        if got is None:
            continue
        pos, _ = got
        cam = fr.iloc[int(np.argmin(np.abs(ft - tn)))]
        if np.hypot(pos[0] - cam.tx, pos[1] - cam.tz) > SPREAD_MAX_M:
            continue
        x = (g.ix.to_numpy() + 0.5) * vs
        y = (g.iy.to_numpy() + 0.5) * vs
        z = (g.iz.to_numpy() + 0.5) * vs
        r = np.hypot(x - pos[0], z - pos[1])
        m = (r <= SPREAD_OUT_M) & (y >= floor_y) & (y <= floor_y + 2.0)
        if m.sum() < 5:
            continue
        rows.append({"t": tn / 1e9, "share": float((r[m] >= SPREAD_IN_M).mean()), "n": int(m.sum())})
    return pd.DataFrame(rows)


def main_spread(args: list[str]) -> None:
    """--spread <기준 재생 묶음> <변형 재생 묶음> <세션> ...: 세션마다 퍼짐 몫 중앙값(기준·변형)과 차의 0.5 s 묶음 부트스트랩 95% 구간(같은 시각 장끼리)."""
    base, var, sessions = Path(args[0]), Path(args[1]), [Path(s) for s in args[2:]]
    rng = np.random.default_rng(0)
    out = {}
    for s in sessions:
        a = _spread_series(s, base / s.name)
        b = _spread_series(s, var / s.name)
        if a.empty or b.empty:
            continue
        j = a.merge(b, on="t", suffixes=("0", "1"))
        if j.empty:
            continue
        blk = np.floor((j.t - j.t.min()) / BLOCK_S).astype(int).to_numpy()
        groups = [np.where(blk == k)[0] for k in np.unique(blk)]
        boots = []
        for _ in range(BOOT):
            idx = np.concatenate([groups[i] for i in rng.integers(0, len(groups), len(groups))])
            boots.append(float(np.median(j.share1.to_numpy()[idx]) - np.median(j.share0.to_numpy()[idx])))
        out[s.name] = {"frames": len(j), "base": round(float(j.share0.median()), 3), "variant": round(float(j.share1.median()), 3),
                       "diff": round(float(j.share1.median() - j.share0.median()), 3),
                       "ci95": [round(float(np.percentile(boots, 2.5)), 3), round(float(np.percentile(boots, 97.5)), 3)]}
        print(s.name, out[s.name], flush=True)
    Path(var / "spread.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")


def main_floorerr(args: list[str]) -> None:
    """--floorerr <재생 묶음> <세션> ...: 장마다 앱 바닥(`slow_path.csv` floorY) − 정답 바닥. 정답은 걷는 세션이면 RGB 바닥(`floor_rgb.json`, 깊이와 독립),
    서 있는 세션이면 클릭 바닥(깊이 바닥 중앙값이라 앱 기준선에 유리, 따로 보고). 세션마다 |오차| 중앙값·p95."""
    root, sessions = Path(args[0]), [Path(s) for s in args[1:]]
    out = {}
    for s in sessions:
        fr = json.loads((s / "floor_rgb.json").read_text(encoding="utf-8")) if (s / "floor_rgb.json").is_file() else {}
        truth, kind = fr.get("floorY"), "rgb"
        if truth is None:
            ev = E.load_events(s)
            truth, kind = (ev or {}).get("floorYFallback"), "click(depth median)"
        if truth is None:
            continue
        sp = pd.read_csv(root / s.name / "slow_path.csv")
        e = (sp.floorY.dropna() - truth).abs()
        out[s.name] = {"truth": kind, "absMedian": round(float(e.median()), 3), "absP95": round(float(e.quantile(0.95)), 3), "n": int(len(e))}
        print(s.name, out[s.name], flush=True)
    Path(root / "floorerr.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")


def main(args: list[str]) -> None:
    if args and args[0] == "--spread":
        return main_spread(args[1:])
    if args and args[0] == "--floorerr":
        return main_floorerr(args[1:])
    if args and args[0] == "--sigma":  # --sigma vertical=<s>,... face=<s>,... floor=<s>,... person=<s>,...
        kv = dict(a.split("=", 1) for a in args[1:])
        sp = lambda k: [Path(x) for x in kv.get(k, "").split(",") if x]
        res = sigma_table([(s, False) for s in sp("vertical")] + [(s, True) for s in sp("face")], sp("floor"), sp("person"), load_config(Path(".")))
        print(json.dumps(res, ensure_ascii=False, indent=1))
        Path("../../data/sweeps/m54/sigma_table.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
        return
    if args and args[0] == "--truth":
        return main_truth(args[1:])
    if args and args[0] == "--person":
        return main_person(args[1:])
    out = None
    if "--out" in args:
        i = args.index("--out")
        out = Path(args[i + 1])
        args = args[:i] + args[i + 2:]
    ref_conf, static_only = None, "--static" in args
    if "--ref-conf" in args:
        i = args.index("--ref-conf")
        ref_conf = int(args[i + 1])
        args = args[:i] + args[i + 2:]
    args = [a for a in args if a != "--static"]
    cfg = load_config(Path("."))
    total = Acc()
    res = {"sessions": {}, "refConf": ref_conf, "staticOnly": static_only}
    for s in args:
        a = measure(Path(s), cfg, ref_conf, static_only)
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
