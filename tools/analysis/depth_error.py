"""깊이 오차 모델: 평평한 복도 바닥의 높이 오차를 거리별로 잰다(M13.5 준비, 단차 녹화 없이 잴 수 있는 것).
    python depth_error.py <세션 폴더> [...] [--step N]  → 원천·묶음(조정용/확인용)별 표 출력
- 바닥 점: 정답 좌표 |x| < CORRIDOR_X_M(옆 벽 ±0.52 m), 끝 벽 END_MARGIN_M 앞까지, 물체 바닥 자리 ± OBJECT_MARGIN_M 밖,
  그 프레임 바닥 ± BAND_M
- 높이 기준: 세션 전체에서 카메라 수평 REF_D_M 안 바닥 점의 월드 높이 중앙값(가장 가까운 바닥 = 가장 정확한 바닥). 높이는 월드
  값이라 자세의 높이 드리프트도 들어간다(지도가 보는 그대로). 프레임별 최빈값은 들린 먼 바닥에 끌려 가까운 바닥보다 약 0.15 m 높았다
- 거리별: 중앙값(들림), 강건 σ(IQR/1.349), 꼬리(중앙값에서 TAIL_M 넘게 벗어난 비율)
- 한 장 단차 검출기 흉내: 프레임마다 보행선 방향 CELL_M 칸의 높이 중앙값, 이웃 칸 차(먼 칸 − 가까운 칸)가 −DROP_M보다
  낮은 비율 = 평평한 바닥에서의 오경보
- 융합 칸: 칸마다 여러 프레임 값의 중앙값. 카메라에서 FUSE_MAX_M 안에서 본 값만 쓴
  경우와 전부 쓴 경우의 이웃 칸 차 최솟값
깊이 원천: SMOOTHED, RAW(신뢰도 ≥ 0, ≥ RAW_CONF; M3 F6 결정의 30과 같음)
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

from align import fit, load_frames, to_truth
from error_decomp import end_face, forward_xz, frame_floor, load_cfg, targets
from metrics import load_truth, scene_of
from worldpts import DEPTH_COLUMN, depth_k, depth_rows, load_depth_m, to_world

# 분석 도구 파라미터(앱·core 설정이 아님)
CORRIDOR_X_M = 0.30
END_MARGIN_M = 0.3
OBJECT_MARGIN_M = 0.3
BAND_M = 0.5
TAIL_M = 0.10
CELL_M = 0.25
CELL_MIN_POINTS = 15
DROP_M = (0.05, 0.10)
FUSE_MAX_M = 2.5
FUSE_MIN_OBS = 3
REF_D_M = (1.0, 2.0)
RAW_CONF = 30
D_EDGES = [0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0]
DEV_SCENES = {"E01h", "E02", "S02"}  # 조정용(IMPROVE_SPEC v0.3.8), 나머지는 확인용
VARIANTS = [("SMOOTHED", None), ("RAW", 0), ("RAW", RAW_CONF)]


def world_frames(session: Path, frames: pd.DataFrame, source: str, min_conf: int | None, step: int):
    """(frames.csv 행, 월드 점). RAW는 신뢰도 영상으로 [min_conf] 미만 픽셀을 버린다."""
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    col, k = DEPTH_COLUMN[source], None
    for _, row in depth_rows(frames, source).iterrows():
        d = load_depth_m(session / row[col])
        if min_conf and isinstance(row.get("confFile"), str):
            c = np.asarray(Image.open(session / row["confFile"]))
            if c.shape == d.shape:
                d[c < min_conf] = 0
        if k is None or (k["width"], k["height"]) != (d.shape[1], d.shape[0]):
            k = depth_k(meta, d.shape[1], d.shape[0])
        yield row, to_world(d, k, row, step)


def floor_mask(x, z, truth: list[dict], z_end: float) -> np.ndarray:
    """보행선 띠 안이고 끝 벽 앞, 물체 바닥 자리 밖."""
    m = (np.abs(x) < CORRIDOR_X_M) & (z < z_end - END_MARGIN_M)
    for o in truth:
        if o["kind"] != "object":
            continue
        mn, mx = o["min"], o["max"]
        m &= ~((x > mn[0] - OBJECT_MARGIN_M) & (x < mx[0] + OBJECT_MARGIN_M) & (z > mn[2] - OBJECT_MARGIN_M) & (z < mx[2] + OBJECT_MARGIN_M))
    return m


def analyze(session: Path, source: str, min_conf: int | None, step: int) -> dict:
    cfg = load_cfg(session)
    frames = load_frames(session)
    tr = frames[frames.tracking == "TRACKING"].reset_index(drop=True)
    data = []
    for row, p in world_frames(session, tr, source, min_conf, step):
        cam = np.array([row.tx, row.ty, row.tz])
        data.append((row, p, frame_floor(p, cam, forward_xz(pd.DataFrame([row]))[0])))
    floor_y = float(np.nanmedian([f for _, _, f in data]))
    al = fit(frames, cfg["align"], cfg["head"]["offsetFromCameraM"], floor_y)
    truth = load_truth(session, cfg)[0] or []
    z_end = end_face(targets(truth))

    pts = []  # 프레임마다 (z, d, 월드 y) 중 바닥 후보
    for row, p, _ in data:
        x, _, z = to_truth(al, p[:, 0], p[:, 1], p[:, 2])
        d = np.hypot(p[:, 0] - row.tx, p[:, 2] - row.tz)
        m = floor_mask(x, z, truth, z_end) & (np.abs(p[:, 1] - floor_y) < 2 * BAND_M) & (d >= D_EDGES[0])
        pts.append((z[m], d[m], p[m, 1]))
    near = np.concatenate([y[(d >= REF_D_M[0]) & (d < REF_D_M[1])] for _, d, y in pts])
    ref = float(np.median(near)) if len(near) else floor_y

    rs, ds, diffs, cells = [], [], [], {}
    for z, d, y in pts:
        r = y - ref
        sel = np.abs(r) < BAND_M
        rs.append(r[sel])
        ds.append(d[sel])
        zc = np.floor(z[sel] / CELL_M).astype(int)
        g = pd.DataFrame({"c": zc, "r": r[sel], "d": d[sel]}).groupby("c").agg(n=("r", "size"), r=("r", "median"), d=("d", "mean"))
        g = g[g.n >= CELL_MIN_POINTS]
        for c, v in g.iterrows():
            cells.setdefault(c, []).append((v.r, v.d))
        idx = set(g.index)
        for c in g.index:
            if c + 1 in idx:
                a, b = g.loc[c], g.loc[c + 1]
                near, far = (a, b) if a.d < b.d else (b, a)
                diffs.append((near.d, far.r - near.r))

    def fused(max_d: float) -> np.ndarray:
        h = {c: np.median([w for w, d in obs if d <= max_d]) for c, obs in cells.items()
             if sum(d <= max_d for _, d in obs) >= FUSE_MIN_OBS}
        return np.array([h[c + 1] - h[c] for c in sorted(h) if c + 1 in h]) if h else np.array([])

    return {"session": session.name, "scene": scene_of(session), "r": np.concatenate(rs), "d": np.concatenate(ds),
            "diffs": np.array(diffs).reshape(-1, 2), "fusedNear": fused(FUSE_MAX_M), "fusedAll": fused(np.inf)}


def robust(v: np.ndarray) -> tuple[float, float, float]:
    """중앙값, 강건 σ, 꼬리 비율."""
    med = float(np.median(v))
    q1, q3 = np.percentile(v, [25, 75])
    return med, float((q3 - q1) / 1.349), float((np.abs(v - med) > TAIL_M).mean())


def table(results: list[dict]) -> None:
    r = np.concatenate([o["r"] for o in results])
    d = np.concatenate([o["d"] for o in results])
    df = np.concatenate([o["diffs"] for o in results])
    print("  거리(m)    점 수   들림(m)  σ(m)  꼬리% | 이웃 칸 차 중앙값  σ   차<−0.05  차<−0.10 (칸 쌍 수)")
    for a, b in zip(D_EDGES[:-1], D_EDGES[1:]):
        m = (d >= a) & (d < b)
        k = (df[:, 0] >= a) & (df[:, 0] < b)
        if m.sum() < 100:
            continue
        med, sig, tail = robust(r[m])
        line = f"  {a:.1f}–{b:.1f}  {m.sum():8d}  {med:+.3f}  {sig:.3f}  {100 * tail:4.1f}"
        if k.sum() >= 20:
            dm, ds, _ = robust(df[k, 1])
            line += f" | {dm:+.3f}  {ds:.3f}  {100 * (df[k, 1] < -DROP_M[0]).mean():5.1f}%  {100 * (df[k, 1] < -DROP_M[1]).mean():5.1f}%  ({k.sum()})"
        print(line)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("sessions", nargs="+", type=Path)
    ap.add_argument("--step", type=int, default=2)
    a = ap.parse_args()
    for source, conf in VARIANTS:
        res = [analyze(s, source, conf, a.step) for s in a.sessions]
        name = source + ("" if conf is None else f" 신뢰도≥{conf}")
        for group in ("조정용", "확인용"):
            sub = [o for o in res if (o["scene"] in DEV_SCENES) == (group == "조정용")]
            if not sub:
                continue
            print(f"\n## {name} · {group} ({', '.join(o['scene'] + ' ' + o['session'][9:15] for o in sub)})")
            table(sub)
        print(f"\n## {name} · 융합 칸(이웃 칸 차 최솟값 m, 칸 쌍 수): {FUSE_MAX_M} m 안에서 본 값만 / 전부")
        for o in res:
            fn, fa = o["fusedNear"], o["fusedAll"]
            fmt = lambda v: f"{v.min():+.3f} ({len(v)})" if len(v) else "–"
            print(f"  {o['scene']:>18} {o['session'][9:15]}  {fmt(fn):>14}  {fmt(fa):>14}")


if __name__ == "__main__":
    main()
