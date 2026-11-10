"""빈 곳 위반(M56 설계 3.3절): 지금 원시 점이 지난 원시 장에서 비어 있다고 본 자리에 있는지.

이식 대상: core/…/tracking(Kotlin). numpy 벡터 연산은 Kotlin에서 점마다 반복문 하나로 옮긴다(아래 주석).
좌표: 월드(W, +Y 위), 카메라 C_cv(+Y 아래, +Z 앞). 시각은 int64 ns. 과거 장만 쓴다.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class RawFrame:
    """원시 깊이 장 하나: 깊이(m, 0 = 없음), 신뢰도(0~255), 자세(C_cv → 월드 회전, 카메라 위치), 내부 파라미터."""
    t_capture_ns: int
    depth_m: np.ndarray  # H×W float32
    conf: np.ndarray  # H×W uint8
    rot_w_from_c: np.ndarray  # 3×3
    cam_w: np.ndarray  # 3
    fx: float
    fy: float
    cx: float
    cy: float


def sigma_raw_m(z_m: np.ndarray, sigma0_m: float, per_m2: float) -> np.ndarray:
    """원시 깊이 오차 σ(Z) = σ₀ + kZ²."""
    return sigma0_m + per_m2 * z_m * z_m


def margin_m(z_m: np.ndarray, sigma0_m: float, per_m2: float, sigma_multiple: float) -> np.ndarray:
    """위반 여유 m(Z) = sigma_multiple · √2 · σ(Z): 두 원시 측정 차의 표준편차가 √2σ."""
    return sigma_multiple * np.sqrt(2.0) * sigma_raw_m(z_m, sigma0_m, per_m2)


def compare(points_w: np.ndarray, past: RawFrame, min_conf: int):
    """월드 점들(N×3)을 지난 원시 장에 투영해 (비교했는가, 지난 깊이 − 점의 그 카메라 깊이, 점의 그 카메라 깊이)를 낸다.

    화소 = 내림(core LabelImage.pixelAt·SlowPath.rawVisibility와 같음). 비교 안 함 = 카메라 뒤·영상 밖·지난 원시 없음·신뢰도 미만.
    Kotlin: for (i in 0 until n) { c = R^T (p_i − cam); if (c.z <= 0.1) continue; u = floor(fx c.x / c.z + cx); … }
    """
    c = (points_w - past.cam_w) @ past.rot_w_from_c  # = R^T (p − cam)
    z = c[:, 2]
    ok = z > 0.1
    zs = np.where(ok, z, 1.0)
    u = np.floor(past.fx * c[:, 0] / zs + past.cx).astype(np.int64)
    v = np.floor(past.fy * c[:, 1] / zs + past.cy).astype(np.int64)
    h, w = past.depth_m.shape
    ok &= (u >= 0) & (v >= 0) & (u < w) & (v < h)
    uu, vv = np.where(ok, u, 0), np.where(ok, v, 0)
    d = past.depth_m[vv, uu]
    ok &= (d > 0) & (past.conf[vv, uu] >= min_conf)
    return ok, np.where(ok, d - z, 0.0), z


def violations(points_w: np.ndarray, past: RawFrame, min_conf: int, sigma0_m: float, per_m2: float, sigma_multiple: float):
    """(비교했는가, 위반인가): 지난 깊이가 점의 그 카메라 깊이보다 m(Z) 넘게 멀면 위반(그 자리를 지난 빛이 지나갔음)."""
    ok, diff, z = compare(points_w, past, min_conf)
    return ok, ok & (diff > margin_m(z, sigma0_m, per_m2, sigma_multiple))


def lookback_s(z_m: np.ndarray, sigma0_m: float, per_m2: float, sigma_multiple: float, v_min_mps: float) -> np.ndarray:
    """되돌아볼 시간 m(Z) / v_min(설계 표 4): v_min으로 움직인 물체가 여유를 넘는 데 걸리는 시간."""
    return margin_m(z_m, sigma0_m, per_m2, sigma_multiple) / v_min_mps


def window_stat(n_compared: np.ndarray, n_violation: np.ndarray, w: int, min_samples: int) -> np.ndarray:
    """track 한 줄의 원시 장별 (비교 수, 위반 수) → 최근 w장 합의 위반 비율 S_W(비교 수 < min_samples 또는 장 수 < w면 NaN)."""
    n = np.asarray(n_compared, dtype=np.float64)
    k = np.asarray(n_violation, dtype=np.float64)
    cn, ck = np.cumsum(np.r_[0.0, n]), np.cumsum(np.r_[0.0, k])
    out = np.full(len(n), np.nan)
    for i in range(w - 1, len(n)):  # Kotlin: 같은 반복문
        sn, sk = cn[i + 1] - cn[i + 1 - w], ck[i + 1] - ck[i + 1 - w]
        if sn >= min_samples:
            out[i] = sk / sn
    return out
