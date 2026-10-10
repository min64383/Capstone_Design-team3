"""평활 깊이의 옆 번짐 폭 실측 (M19, RGB 번짐 띠 `frontend.rgbGuideBandPx`의 근거).

    python spill.py <세션 폴더> ...        → 세션별·전체 번짐 거리 분포(깊이 화소), 전체 p90 = 띠 폭 b
    python spill.py --selftest

평활 깊이는 앞 물체의 깊이를 옆 배경 화소로 퍼뜨린다(M19 설계 표 4: 물체 옆 점이 원시 깊이의 3~10배). 원시 깊이와 평활 깊이의 시각이
같은 장(frames.csv의 rawDepthTNs = 다른 행의 depthTNs)에서 번짐 화소 = 원시가 유효하고, 평활이 원시보다 `frontend.edgeMinStepRatio`(10%)
넘게 가까우며, 평활이 가장 가까운 경사의 앞쪽 깊이(물체 깊이)와 같은 비율 안인 화소다. 마지막 조건이 없으면 물체 뒤 배경이 중간 깊이로
당겨진 화소(M13 배경 끌림: 경계에서 약 25화소 안 0.15~0.5 m)까지 세어 p90이 19화소로 나왔다 — 끌림은 번짐과 다른 오차다.
경사 화소 = 3×3 안 깊이 차가 깊이의 같은 비율을 넘는 곳(core 경계 판정이 막을 찾는 곳의 근사), 그 앞쪽 깊이 = 3×3 최소.
번짐 화소에서 가장 가까운 경사 화소까지의 체비쇼프 거리를 잰다. RGB 번짐 띠는 경계 마스크에서 이 거리만큼 후보를 넓힌다.
점수로 고르지 않는 실측 값이다(과적합 방지 규칙, IMPROVE_SPEC §6). 조정용 세션으로만 정한다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

from align import REPO

MAX_DIST_PX = 40  # 이보다 먼 번짐 화소는 경사와 무관한 것으로 보고 세지 않는다(160×90 영상 폭의 1/4)


def step_ratio() -> float:
    cfg = json.loads((REPO / "app/src/main/assets/config/default.json").read_text(encoding="utf-8"))
    return cfg["frontend"]["edgeMinStepRatio"]


def ramp_mask(d: np.ndarray, ratio: float) -> tuple[np.ndarray, np.ndarray]:
    """(3×3 안 유효 깊이의 (최대 − 최소) > 최소 × [ratio]인 화소, 그 3×3 최소 = 경사의 앞쪽 깊이)."""
    h, w = d.shape
    big = np.where(d > 0, d, np.inf)
    pad_lo = np.pad(big, 1, constant_values=np.inf)
    pad_hi = np.pad(np.where(d > 0, d, 0), 1, constant_values=0)
    lo = np.min([pad_lo[i:i + h, j:j + w] for i in range(3) for j in range(3)], axis=0)
    hi = np.max([pad_hi[i:i + h, j:j + w] for i in range(3) for j in range(3)], axis=0)
    return (d > 0) & np.isfinite(lo) & (hi - lo > lo * ratio), lo


def chebyshev_distance(mask: np.ndarray, max_d: int, value: np.ndarray | None = None) -> tuple[np.ndarray, np.ndarray]:
    """([mask] 화소까지의 체비쇼프 거리(최대 [max_d], 넘으면 max_d + 1), 처음 닿은 [mask] 화소들의 [value] 최소). 3×3 팽창을 반복한다."""
    h, w = mask.shape
    dist = np.full(mask.shape, max_d + 1, dtype=np.int32)
    val = np.full(mask.shape, np.inf)
    if value is not None:
        val[mask] = value[mask]
    cur = mask.copy()
    dist[cur] = 0
    for k in range(1, max_d + 1):
        p = np.pad(cur, 1)
        pv = np.pad(val, 1, constant_values=np.inf)
        grown = np.zeros_like(cur)
        near = np.full(mask.shape, np.inf)
        for i in range(3):
            for j in range(3):
                grown |= p[i:i + h, j:j + w]
                near = np.minimum(near, pv[i:i + h, j:j + w])
        new = grown & ~cur
        dist[new] = k
        val[new] = near[new]
        cur = grown
    return dist, val


def spill_distances(smoothed: np.ndarray, raw: np.ndarray, ratio: float) -> np.ndarray:
    """번짐 화소(원시 유효, 평활 < 원시 × (1 − ratio), 평활 ≈ 가장 가까운 경사의 앞쪽 깊이)에서 그 경사까지의 거리.
    MAX_DIST_PX 넘는 것은 뺀다."""
    cand = (raw > 0) & (smoothed > 0) & (smoothed < raw * (1 - ratio))
    if not cand.any():
        return np.empty(0, dtype=np.int32)
    ramp, front = ramp_mask(smoothed, ratio)
    dist, fg = chebyshev_distance(ramp, MAX_DIST_PX, front)
    spill = cand & (dist <= MAX_DIST_PX) & (np.abs(smoothed - fg) <= ratio * fg)
    return dist[spill]


def session_distances(session: Path, ratio: float) -> np.ndarray:
    f = pd.read_csv(session / "frames.csv")
    raw = f[f.rawDepthFile.notna()]
    smo = f[f.depthFile.notna()][["depthTNs", "depthFile"]]
    pairs = raw.merge(smo, left_on="rawDepthTNs", right_on="depthTNs", suffixes=("", "_s"))
    out = [spill_distances(np.asarray(Image.open(session / r.depthFile_s), dtype=np.float32),
                           np.asarray(Image.open(session / r.rawDepthFile), dtype=np.float32), ratio)
           for r in pairs.itertuples()]
    return np.concatenate(out) if out else np.empty(0, dtype=np.int32), len(pairs)


def selftest() -> None:
    # 1차원 장면: 0~19열 물체(1.0 m), 20열부터 배경(2.0 m). 평활은 물체를 4화소 번지게 하고(20~23열 1.0 m) 24~25열이 경사
    raw = np.full((5, 40), 2000.0)
    raw[:, :20] = 1000
    sm = raw.copy()
    sm[:, 20:24] = 1000
    sm[:, 24] = 1300
    sm[:, 25] = 1700
    d = spill_distances(sm, raw, 0.1)
    # 번짐 화소 = 20~23열(물체 깊이 1.0 m). 경사 마스크는 23~26열(이웃에 단차) → 거리 3,2,1,0.
    # 24열(1.3 m)은 원시보다 가깝지만 물체 깊이와 10% 넘게 달라 끌림 쪽이라 세지 않는다
    assert sorted(d.tolist()) == sorted([3, 2, 1, 0] * 5), d
    assert chebyshev_distance(np.eye(5, dtype=bool), 3)[0][0, 4] == 2  # (2, 2)까지
    print("spill selftest ok")


def main(args: list[str]) -> None:
    if args == ["--selftest"]:
        return selftest()
    if not args:
        sys.exit(__doc__)
    ratio = step_ratio()
    allv = []
    for a in args:
        d, n = session_distances(Path(a), ratio)
        allv.append(d)
        q = np.percentile(d, [50, 90, 95]).round(1).tolist() if len(d) else None
        print(json.dumps({"session": Path(a).name, "pairs": n, "spillPx": int(len(d)), "p50_p90_p95": q}), flush=True)
    v = np.concatenate(allv)
    print(json.dumps({"all": True, "spillPx": int(len(v)), "p50": float(np.percentile(v, 50)), "p90": float(np.percentile(v, 90)),
                      "p95": float(np.percentile(v, 95))}))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
