"""정답 정렬 (MVP_SPEC §10.3, M8).

녹화 세션의 카메라 궤적(수평) 처음 `align.fitLengthM`에 직선을 맞춰 그 방향을 정답 +z(보행선)로 둔다.
원점은 시작 시 **머리** 아래 바닥(= 시작 표시, 줄자 기준), 머리 = 카메라 + `head.offsetFromCameraM`(진행 방향 기준).
정답 좌표: +x 오른쪽, +y 위(바닥 0), +z 보행선 앞, 미터 (docs/FORMAT.md "정답 obstacles.json").
카메라가 `align.minTravelM`보다 적게 움직인 세션(정지 녹화)은 궤적 방향이 잡음이라 처음 `align.headingWindowS`초의
카메라 시선(수평)을 +z로 쓴다(`byHeading`). Kotlin `Alignment`와 같은 계산.

    python align.py <세션 폴더> <실행 로그 폴더>   → <실행 로그 폴더>/align.json
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

REPO = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO / "app/src/main/assets/config/default.json"


def _merge(a: dict, b: dict) -> dict:
    out = dict(a)
    for k, v in b.items():
        out[k] = _merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


def load_config(run_dir: Path) -> dict:
    """기본 설정 + 오프라인 재생의 덮어쓰기(`replay_info.json`, 있으면)."""
    cfg = json.loads(DEFAULT_CONFIG.read_text(encoding="utf-8"))
    info = run_dir / "replay_info.json"
    if info.is_file():
        cfg = _merge(cfg, json.loads(info.read_text(encoding="utf-8")).get("configOverrides", {}))
    return cfg


def load_frames(session: Path) -> pd.DataFrame:
    """frames.csv (자세 위치는 GL/C_cv 규약과 무관하게 같다). ARCore가 같은 프레임을 두 번 돌려준 행은 첫 행만."""
    return pd.read_csv(session / "frames.csv").drop_duplicates("tNs")


def _by_heading(tr: pd.DataFrame, window_s: float, head_offset: list[float], floor_y: float) -> dict:
    """정지 녹화: 처음 `window_s`초 카메라 시선(GL: −Z가 앞)의 수평 성분 평균을 +z로."""
    win = tr[(tr.tNs - tr.tNs.iloc[0]) / 1e9 <= window_s]
    x, y, z, w = (win[c].to_numpy() for c in ("qx", "qy", "qz", "qw"))
    f = np.stack([-2 * (x * z + y * w), -(1 - 2 * (x * x + y * y))], axis=1)
    n = np.linalg.norm(f, axis=1)
    if (n <= 1e-3).any():
        raise ValueError("camera looks straight up or down; no horizontal heading")
    f /= n[:, None]
    m = f.mean(axis=0)
    if np.linalg.norm(m) <= 1e-3:
        raise ValueError(f"camera heading is not stable in the first {window_s}s")
    d = m / np.linalg.norm(m)
    right = np.array([-d[1], d[0]])
    spread = np.degrees(np.sqrt(np.mean(np.arctan2(f @ right, f @ d) ** 2)))
    start = tr[["tx", "tz"]].to_numpy()[0]
    ox, _, oz = head_offset
    span = float(np.linalg.norm(tr[["tx", "tz"]].to_numpy() - start, axis=1).max())
    return {
        "origin": (start + right * ox + d * oz).tolist(), "dir": d.tolist(), "floorY": floor_y,
        "fitLengthM": span, "fitShort": True, "byHeading": True, "nPoints": int(len(win)),
        "residualRmsM": 0.0, "angleUncertaintyDeg": float(spread),
    }


def fit(frames: pd.DataFrame, cfg_align: dict, head_offset: list[float], floor_y: float) -> dict:
    fit_length_m = cfg_align["fitLengthM"]
    tr = frames[frames.tracking == "TRACKING"]
    xz = tr[["tx", "tz"]].to_numpy()
    start = xz[0]
    dist = np.linalg.norm(xz - start, axis=1)
    if dist.max() < cfg_align["minTravelM"]:
        return _by_heading(tr, cfg_align["headingWindowS"], head_offset, floor_y)
    reach = np.nonzero(dist >= fit_length_m)[0]
    short = len(reach) == 0  # 궤적이 fitLengthM보다 짧으면 전체로 맞추고 표시(불확실성 큼)
    pts = xz if short else xz[: reach[0] + 1]
    c = pts.mean(axis=0)
    _, _, vt = np.linalg.svd(pts - c)
    d = vt[0] / np.linalg.norm(vt[0])
    if np.dot(pts[-1] - pts[0], d) < 0:
        d = -d
    right = np.array([-d[1], d[0]])  # (dx, dz) → 오른쪽 = d × up = (−dz, dx)
    lateral = (pts - c) @ right
    rms = float(np.sqrt(np.mean(lateral**2)))
    ox, _, oz = head_offset
    origin = start + right * ox + d * oz
    return {
        "origin": origin.tolist(),
        "dir": d.tolist(),
        "floorY": floor_y,
        "fitLengthM": float(np.linalg.norm(pts[-1] - pts[0])) if short else fit_length_m,
        "fitShort": bool(short),
        "byHeading": False,
        "nPoints": int(len(pts)),
        "residualRmsM": rms,
        # 직선 방향의 대략적 불확실성: 잔차가 길이 양 끝에서 반대로 기울었을 때의 각
        "angleUncertaintyDeg": float(np.degrees(np.arctan2(2 * rms, np.linalg.norm(pts[-1] - pts[0])))),
    }


def to_truth(al: dict, x, y, z):
    """월드 좌표 → 정답 좌표 (x 오른쪽, y 바닥 위, z 앞). 배열 가능."""
    o = np.asarray(al["origin"])
    d = np.asarray(al["dir"])
    r = np.array([-d[1], d[0]])
    px = np.asarray(x) - o[0]
    pz = np.asarray(z) - o[1]
    return px * r[0] + pz * r[1], np.asarray(y) - al["floorY"], px * d[0] + pz * d[1]


def align(session: Path, run_dir: Path) -> dict:
    """정렬을 맞춰 `align.json`에 쓴다. 정답 대입 재생(M12.3)이면 맞추지 않고 그 재생이 쓴 기준 정렬(`substitution.alignFrom`)을
    그대로 쓴다: 정렬은 재생의 바닥 추정에 기대므로, 변형마다 맞추면 정답이 변형마다 달라진다."""
    info = run_dir / "replay_info.json"
    src = json.loads(info.read_text(encoding="utf-8")).get("substitution", {}).get("alignFrom") if info.is_file() else None
    if src:
        al = json.loads(Path(src).read_text(encoding="utf-8"))
        (run_dir / "align.json").write_text(json.dumps(al, indent=2), encoding="utf-8")
        return al
    cfg = load_config(run_dir)
    sp = pd.read_csv(run_dir / "slow_path.csv")
    floor = sp.floorY.dropna()
    if floor.empty:
        raise ValueError("no floor estimate in slow_path.csv")
    al = fit(load_frames(session), cfg["align"], cfg["head"]["offsetFromCameraM"], float(floor.median()))
    (run_dir / "align.json").write_text(json.dumps(al, indent=2), encoding="utf-8")
    return al


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    print(json.dumps(align(Path(sys.argv[1]), Path(sys.argv[2])), indent=2))
