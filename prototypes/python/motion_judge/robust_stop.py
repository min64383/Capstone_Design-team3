"""자세 튐에 흔들리지 않는 첫 STOP 늦음(M56, 130646: 한 프레임 늦은 STOP이 그 프레임 ARCore 자세 +6.9 cm 튐으로 0.069 m로 부풀었다).

늦음(m) = (변형의 첫 STOP 시각 − 기준의 첫 STOP 시각) × 그 사이(앞뒤 0.5 s 포함) 카메라 수평 속도의 프레임별 중앙값.
첫 STOP 시각은 metrics.block_rows가 그 정답 물체에 맞춘 첫 STOP 블록(metrics.firstStopDistanceM과 같은 블록). 중앙값이라 한 프레임 튐은 빠진다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "analysis"))
from align import align, load_config, load_frames  # noqa: E402
from metrics import block_rows, load_truth  # noqa: E402


def first_stop_times(session: Path, run: Path) -> dict:
    """정답 물체 이름 → 그 물체에 맞춘 첫 STOP 블록 시각(s, 블록 기준)."""
    cfg = load_config(run)
    frames = load_frames(session)
    g = pd.read_csv(run / "guidance.csv")
    obs = pd.read_csv(run / "obstacles.csv")
    t0 = g.tBlockNs.min()
    truth, _ = load_truth(session, cfg)
    if truth is None:
        return {}
    al = align(session, run)
    rows, _ = block_rows(session, run, cfg, frames, g, obs, g.drop_duplicates("tBlockNs"), t0, truth, al)
    out = {}
    for r in rows:
        if r["band"] == "STOP" and r["match"] is not None:
            out.setdefault(truth[r["match"]]["name"], (r["t"], t0))
    return out


def cam_speed(session: Path, t0_ns: int, t_lo: float, t_hi: float) -> float:
    fr = load_frames(session).drop_duplicates("tNs").sort_values("tNs")
    t = (fr.tNs.to_numpy() - t0_ns) / 1e9
    sel = (t >= t_lo - 0.5) & (t <= t_hi + 0.5)
    x, z, tt = fr.tx.to_numpy()[sel], fr.tz.to_numpy()[sel], t[sel]
    if len(tt) < 3:
        return float("nan")
    v = np.hypot(np.diff(x), np.diff(z)) / np.maximum(np.diff(tt), 1e-6)
    return float(np.median(v))


def lateness(session: Path, run: Path, base: Path) -> dict:
    """물체 이름 → (시각 차 s, 속도 m/s, 늦음 m). 한쪽에 STOP이 없으면 None(놓침)."""
    a, b = first_stop_times(session, run), first_stop_times(session, base)
    out = {}
    for name, (tb, t0b) in b.items():
        if name not in a:
            out[name] = None
            continue
        ta, t0a = a[name]
        # 두 재생의 블록 시각 기준(t0)이 같다고 보고(같은 세션) 기준 쪽 t0로 속도를 잰다
        dt = (ta + (t0a - t0b) / 1e9) - tb
        v = cam_speed(session, t0b, min(tb, tb + dt), max(tb, tb + dt))
        out[name] = (round(dt, 3), round(v, 3), round(dt * v, 3))
    return out


if __name__ == "__main__":
    s, r, b = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    print(json.dumps(lateness(s, r, b), ensure_ascii=False))
