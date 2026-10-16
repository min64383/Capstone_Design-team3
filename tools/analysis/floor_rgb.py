"""RGB·포즈로 잰 바닥 (M26, IMPROVE_SPEC §6 M26): 깊이와 독립된 바닥 높이를 평면 스윕으로 재서 깊이 바닥과의 어긋남을 본다.

    python floor_rgb.py <세션 폴더> ... [--align <align.json>] [--force]  → <세션>/floor_rgb.json
    (정답 없이 돈다. --align이면 정답 상자 둘레 0.3 m를 더 뺀다 — 사전 측정과 같은 조건의 비교용)

걸으며 [PAIR_MIN_M]~[PAIR_MAX_M] 떨어진 MP4 프레임 A·B(`mp4_rgb.py`의 `rgb_mp4/`)를 고른다. 바닥 높이를 A 장의 깊이 바닥 기준
[SWEEP_M] 범위에서 가정하고, 가정마다 A의 바닥 화소 광선이 그 수평면과 만나는 점을 B로 투영해 밝기를 정규화 상관(NCC)으로 비교한다.
가장 잘 맞는 높이가 실제 바닥이다(평면 스윕, Collins 1996). ARCore 포즈는 미터 단위라 이 값은 깊이와 독립이다.
바닥 화소는 A 장의 깊이 점 중 core 바닥 추정과 같은 규칙([DepthFloor], 깊이 장 순서대로)으로 구한 깊이 바닥 ± [BAND_M] 안, 카메라에서 [DIST_M], 카메라 진행 방향 좌우 [LATERAL_M] 안의 점이다.
상수는 분석용이고 세션에 맞추지 않는다(M26 사전 측정과 같은 값).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

from align import DEFAULT_CONFIG

SWEEP_M = np.round(np.arange(-0.40, 0.2001, 0.01), 2)  # 깊이 바닥 기준 가설 높이
PAIR_MIN_M, PAIR_MAX_M = 0.25, 0.6  # A·B 카메라 수평 이동(시차가 작으면 높이 분해능이 없다, 크면 화면이 안 겹친다)
A_EVERY = 3  # A는 이 장마다
BAND_M = 0.08  # 깊이 바닥 ± 이 안의 점만 바닥 화소(눌린 누운 캐리어 윗면 +0.13 m는 빠진다)
DIST_M = (0.8, 2.5)
LATERAL_M = 0.45
MIN_NCC, MIN_SHARP, SHARP_STEP = 0.5, 0.02, 5  # 받아들임: NCC 최대 > 0.5, 최대가 ±5칸(0.05 m) 옆보다 0.02 넘게 높음
MIN_PAIRS = 5  # 세션 값을 내는 최소 짝 수
MIN_PIXELS = 500
TRUTH_MARGIN_M = 0.3
OUT = "floor_rgb.json"


def world_from_cv(row) -> tuple[np.ndarray, np.ndarray]:
    """frames.csv 행(ARCore GL 자세) → (R, t): 월드 ← 카메라 C_cv(+Y 아래, +Z 앞)."""
    x, y, z, w = row.qx, row.qy, row.qz, row.qw
    r = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    return r @ np.diag([1.0, -1.0, -1.0]), np.array([row.tx, row.ty, row.tz])


class DepthFloor:
    """core `Floor.update`(히스토그램 바닥, `floor.holdWhenLost`)와 같은 규칙을 깊이 장 순서대로 돌린 깊이 바닥. 장마다 최빈값만 쓰면
    물체 윗면·끝 벽을 바닥으로 잡는 장이 섞여(M26 구현: 벗어난 짝의 깊이 바닥 편차 중앙 0.16 m) 탐색 폭·평활까지 따른다."""

    def __init__(self, fc: dict, radius_m: float):
        self.fc, self.radius = fc, radius_m
        self.y: float | None = None
        self.lost, self.searching = 0, False

    def update(self, W: np.ndarray, cam: np.ndarray) -> float | None:
        fc = self.fc
        y = W[:, 1]
        m = (y < cam[1] - fc["minBelowCameraM"]) & (np.hypot(W[:, 0] - cam[0], W[:, 2] - cam[2]) <= self.radius)
        if self.y is not None and not self.searching:
            m &= np.abs(y - self.y) <= fc["searchBandM"]
        c = y[m]
        if len(c) < fc["minPoints"]:
            if self.y is not None:
                self.lost += 1
                if self.lost >= fc["lostFrames"]:
                    if fc["holdWhenLost"]:
                        self.searching = True
                    else:
                        self.y = None
            return self.y
        self.lost = 0
        was, self.searching = self.searching, False
        b = np.floor(c / fc["binM"]).astype(int)
        vals, counts = np.unique(b, return_counts=True)
        center = (vals[counts == counts.max()].min() + 0.5) * fc["binM"]  # 같은 개수면 낮은 칸(core와 같음)
        est = float(c[np.abs(c - center) < fc["toleranceM"]].mean())
        self.y = est if self.y is None or was else self.y + fc["emaAlpha"] * (est - self.y)
        return self.y


def bilinear(img: np.ndarray, u: np.ndarray, v: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    h, w = img.shape
    ok = (u >= 0) & (v >= 0) & (u < w - 1) & (v < h - 1)
    u0 = np.floor(u[ok]).astype(int)
    v0 = np.floor(v[ok]).astype(int)
    du, dv = u[ok] - u0, v[ok] - v0
    val = (img[v0, u0] * (1 - du) * (1 - dv) + img[v0, u0 + 1] * du * (1 - dv)
           + img[v0 + 1, u0] * (1 - du) * dv + img[v0 + 1, u0 + 1] * du * dv)
    return ok, val


def ncc(a: np.ndarray, b: np.ndarray) -> float:
    a = a - a.mean()
    b = b - b.mean()
    return float((a * b).sum() / np.sqrt((a * a).sum() * (b * b).sum() + 1e-9))


def truth_mask(session: Path, align_file: Path | None):
    """정답 상자 둘레 [TRUTH_MARGIN_M] 안의 월드 점을 가리는 함수(--align일 때만). 없으면 None."""
    f = session / "annotations" / "obstacles.json"
    if align_file is None or not f.is_file():
        return None
    al = json.loads(align_file.read_text(encoding="utf-8"))
    o, d = np.array(al["origin"]), np.array(al["dir"])
    r = np.array([-d[1], d[0]])
    boxes = json.loads(f.read_text(encoding="utf-8"))["obstacles"]

    def inside(w: np.ndarray) -> np.ndarray:
        px, pz = w[:, 0] - o[0], w[:, 2] - o[1]
        tx, tz = px * r[0] + pz * r[1], px * d[0] + pz * d[1]
        m = np.zeros(len(w), bool)
        for b in boxes:
            m |= ((tx > b["min"][0] - TRUTH_MARGIN_M) & (tx < b["max"][0] + TRUTH_MARGIN_M)
                  & (tz > b["min"][2] - TRUTH_MARGIN_M) & (tz < b["max"][2] + TRUTH_MARGIN_M))
        return m
    return inside


def measure(session: Path, align_file: Path | None = None) -> dict:
    """세션 하나의 짝별 바닥과 요약. 바닥 높이는 월드 y(m), offsetM은 실제 − 그 장의 깊이 바닥."""
    cfg = json.loads(DEFAULT_CONFIG.read_text(encoding="utf-8"))
    fc = cfg["floor"]
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    K, Kd = meta["camera"]["imageIntrinsics"], meta["depth"]["intrinsics"]
    fr = pd.read_csv(session / "frames.csv").drop_duplicates("frameIndex").set_index("frameIndex")
    idx = pd.read_csv(session / "rgb_mp4" / "index.csv")
    rows = [(int(i), fr.loc[int(i)]) for i in idx.frameIndex if int(i) in fr.index]
    rows = [(i, r) for i, r in rows if r.tracking == "TRACKING"]
    excl = truth_mask(session, align_file)
    # 깊이 바닥: 새 깊이(같은 깊이 파일 반복은 한 번) 장 순서대로 core 규칙. 화소 간격은 core `depth.subsample`
    floor_at, df, last, sub = {}, DepthFloor(fc, cfg["map"]["radiusM"]), None, cfg["depth"]["subsample"]
    for i, r in fr.iterrows():
        if r.tracking != "TRACKING" or not isinstance(r.depthFile, str) or r.depthFile == last:
            continue
        last = r.depthFile
        D = np.asarray(Image.open(session / r.depthFile), dtype=np.float32)[::sub, ::sub] / 1000
        v, u = np.nonzero(D > 0)
        z = D[v, u]
        R, t = world_from_cv(r)
        Wd = (R @ np.stack([(u * sub - Kd["cx"]) / Kd["fx"] * z, (v * sub - Kd["cy"]) / Kd["fy"] * z, z])).T + t
        floor_at[int(i)] = df.update(Wd, t)
    gray = {}

    def img(i):
        if i not in gray:
            gray[i] = np.asarray(Image.open(session / "rgb_mp4" / f"{i:06d}.jpg").convert("L"), dtype=np.float32)
        return gray[i]

    pairs = []
    for k in range(0, len(rows), A_EVERY):
        ia, a = rows[k]
        if not isinstance(a.depthFile, str):
            continue
        b = next(((ib, rb) for ib, rb in rows[k + 1:] if np.hypot(rb.tx - a.tx, rb.tz - a.tz) >= PAIR_MIN_M), None)
        if b is None or np.hypot(b[1].tx - a.tx, b[1].tz - a.tz) > PAIR_MAX_M:
            continue
        ib, b = b
        Ra, ta = world_from_cv(a)
        Rb, tb = world_from_cv(b)
        D = np.asarray(Image.open(session / a.depthFile), dtype=np.float32) / 1000
        v, u = np.nonzero(D > 0)
        z = D[v, u]
        W = (Ra @ np.stack([(u - Kd["cx"]) / Kd["fx"] * z, (v - Kd["cy"]) / Kd["fy"] * z, z])).T + ta
        fy = floor_at.get(ia)
        if fy is None:
            continue
        fwd = Ra[:, 2].copy()
        fwd[1] = 0
        fwd /= np.linalg.norm(fwd) + 1e-9
        lat = (W[:, 0] - ta[0]) * -fwd[2] + (W[:, 2] - ta[2]) * fwd[0]
        m = (np.abs(W[:, 1] - fy) < BAND_M) & (z > DIST_M[0]) & (z < DIST_M[1]) & (np.abs(lat) < LATERAL_M)
        if excl is not None:
            m &= ~excl(W)
        if m.sum() * 4 < MIN_PIXELS:
            continue
        # 깊이 화소 → RGB 화소(두 내부 파라미터로), 깊이 화소마다 2×2 점
        su, sv = K["fx"] / Kd["fx"], K["fy"] / Kd["fy"]
        off = np.array([[-0.25, -0.25], [0.25, -0.25], [-0.25, 0.25], [0.25, 0.25]])
        uu = ((np.repeat(u[m], 4) + np.tile(off[:, 0], m.sum()) - Kd["cx"]) * su + K["cx"]).astype(np.float32)
        vv = ((np.repeat(v[m], 4) + np.tile(off[:, 1], m.sum()) - Kd["cy"]) * sv + K["cy"]).astype(np.float32)
        A, B = img(ia), img(ib)
        oka, ia_val = bilinear(A, uu, vv)
        uu, vv = uu[oka], vv[oka]
        ray = Ra @ np.stack([(uu - K["cx"]) / K["fx"], (vv - K["cy"]) / K["fy"], np.ones_like(uu)])
        score = np.full(len(SWEEP_M), np.nan)
        for j, h in enumerate(SWEEP_M):
            s = (fy + h - ta[1]) / ray[1]
            P = ta[:, None] + ray * s
            cb = Rb.T @ (P - tb[:, None])
            good = (s > 0) & (cb[2] > 0.1)
            ub = np.where(good, K["fx"] * cb[0] / np.where(good, cb[2], 1) + K["cx"], -1)
            vb = np.where(good, K["fy"] * cb[1] / np.where(good, cb[2], 1) + K["cy"], -1)
            ok, val = bilinear(B, ub, vb)
            if ok.sum() >= MIN_PIXELS:
                score[j] = ncc(ia_val[ok], val)
        if np.all(np.isnan(score)):
            continue
        j = int(np.nanargmax(score))
        side = np.nanmax([score[max(j - SHARP_STEP, 0)], score[min(j + SHARP_STEP, len(SWEEP_M) - 1)]])
        accepted = bool(score[j] > MIN_NCC and score[j] - side > MIN_SHARP and 0 < j < len(SWEEP_M) - 1)
        pairs.append({"frameA": ia, "frameB": ib, "tNs": int(a.tNs), "moveM": round(float(np.hypot(b.tx - a.tx, b.tz - a.tz)), 3),
                      "depthFloorY": round(fy, 4), "floorY": round(fy + float(SWEEP_M[j]), 4), "offsetM": float(SWEEP_M[j]),
                      "ncc": round(float(score[j]), 4), "sharpness": round(float(score[j] - side), 4), "nPixels": int(len(uu)),
                      "accepted": accepted})
    acc = [p for p in pairs if p["accepted"]]
    out = {"session": session.name, "excludeTruth": excl is not None, "pairs": pairs, "nPairs": len(pairs), "nAccepted": len(acc)}
    if len(acc) >= MIN_PAIRS:
        off = np.array([p["offsetM"] for p in acc])
        d = np.diff(off)
        out.update({"floorY": float(np.median([p["floorY"] for p in acc])), "offsetP50M": float(np.median(off)),
                    "offsetIqrM": float(np.percentile(off, 75) - np.percentile(off, 25)),
                    # 측정 잡음: 이웃한 짝 차이의 MAD/√2(어긋남이 세션 안에서 천천히 변하는 몫은 빠진다)
                    "noiseM": float(1.4826 * np.median(np.abs(d - np.median(d))) / np.sqrt(2))})
    else:
        out.update({"floorY": None, "offsetP50M": None, "offsetIqrM": None, "noiseM": None})
    return out


def main(args: list[str]) -> None:
    force = "--force" in args
    align_file = Path(args[args.index("--align") + 1]) if "--align" in args else None
    sessions = [Path(a) for i, a in enumerate(args) if not a.startswith("--") and (i == 0 or args[i - 1] != "--align")]
    if not sessions:
        sys.exit(__doc__)
    for s in sessions:
        f = s / OUT
        if f.is_file() and not force:
            continue
        m = measure(s, align_file)
        f.write_text(json.dumps(m, indent=1, ensure_ascii=False), encoding="utf-8")
        p50 = "–" if m["offsetP50M"] is None else f"{m['offsetP50M']:+.3f} m (IQR {m['offsetIqrM']:.3f})"
        print(f"{s.name}: 짝 {m['nAccepted']}/{m['nPairs']}, 실제 − 깊이 바닥 {p50}", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
