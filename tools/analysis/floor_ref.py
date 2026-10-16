"""정답 정렬용 바닥 기준 (M32): 시험 대상 파이프라인의 바닥 추정과 독립된 바닥 높이로 `align.json`을 쓴다.

지금 정렬(align.py)은 느린 경로 바닥 추정의 중앙값을 쓰는데, 걷는 세션에서는 끝 벽 앞에서 그 추정이 벽을 따라 올라가 정답 바닥이
실제보다 높게 놓였다(M32 결과 검토, E02 152354에서 0.17~0.19 m). 여기서는 깊이 점 중 카메라에서 수평 1~2 m(M30·M31 오차 모델에서
σ가 가장 작은 구간, depth_error.py의 바닥 기준과 같은 거리)이고, 정답 형상의 바닥 영역(depth_error.py `floor_mask`: 보행선 띠
|x| < 0.30 m, 끝 벽 0.3 m 앞까지, 물체 자리 ± 0.3 m 밖, 수평 정렬은 자세만으로 정해진다)에 든 점의 높이를 모아 2 cm 히스토그램
최빈 칸 ± 5 cm 안의 중앙값을 바닥으로 본다. 끝 벽 앞에 서 있는 동안 문 아랫부분이 1~2 m 안에 들어와 최빈 칸이 되던 것을 영역으로 막는다.
평활 깊이와 원시 깊이(신뢰도 1 이상)를 따로 잰다. 둘은 세션에 따라 크게 다르다(E02 152354: 평활 −1.53, 원시 −1.32 m). M31 K01의
2 m 절대 편향(평활 −0.20, 원시 −0.10 m, 실제보다 멀리)과 같은 방향이라 깊이 바닥은 낮게 잡힌다(검증 안 함). 그래서 정렬 바닥은
깊이와 독립된 RGB 바닥(M26 `floor_rgb.json`)이 있으면 그것을, 없으면 두 깊이 바닥의 평균을 쓴다. 세 값을 모두 기록한다.

    python floor_ref.py <출력 폴더> <세션 폴더> ...   → <출력 폴더>/<세션ID>/align.json (floorRef 항목 포함)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

from align import fit, load_frames, to_truth
from depth_error import floor_mask
from error_decomp import end_face, load_cfg, targets
from metrics import load_truth
from worldpts import iter_world

REF_D_M = (1.0, 2.0)
BIN_M = 0.02
KEEP_M = 0.05


def floor_height(session: Path, frames, source: str, min_conf: int | None, below_m: float, al0: dict, truth: list[dict]) -> tuple[float | None, int]:
    z_end = end_face(targets(truth))
    ys = []
    for row, p, _ in iter_world(session, frames, source, 2, min_conf):
        d = np.hypot(p[:, 0] - row.tx, p[:, 2] - row.tz)
        x, _, z = to_truth(al0, p[:, 0], p[:, 1], p[:, 2])
        m = (d >= REF_D_M[0]) & (d < REF_D_M[1]) & (p[:, 1] < row.ty - below_m) & floor_mask(x, z, truth, z_end)
        ys.append(p[m, 1])
    y = np.concatenate(ys) if ys else np.array([])
    if len(y) == 0:
        return None, 0
    b = np.floor(y / BIN_M).astype(int)
    vals, counts = np.unique(b, return_counts=True)
    mode = (vals[np.argmax(counts)] + 0.5) * BIN_M
    sel = y[np.abs(y - mode) <= KEEP_M]
    return float(np.median(sel)), int(len(sel))


def main(out: Path, sessions: list[Path]) -> None:
    for s in sessions:
        cfg = load_cfg(s)
        frames = load_frames(s)
        tr = frames[frames.tracking == "TRACKING"].reset_index(drop=True)
        below = cfg["floor"]["minBelowCameraM"]
        al0 = fit(frames, cfg["align"], cfg["head"]["offsetFromCameraM"], 0.0)  # 수평 정렬(바닥과 무관)
        truth = load_truth(s, cfg)[0] or []
        sm, n_sm = floor_height(s, tr, "SMOOTHED", None, below, al0, truth)
        raw, n_raw = floor_height(s, tr, "RAW", 1, below, al0, truth)
        vals = [v for v in (sm, raw) if v is not None]
        rgb_f = s / "floor_rgb.json"
        rgb = json.loads(rgb_f.read_text(encoding="utf-8"))["floorY"] if rgb_f.is_file() else None
        if rgb is None and not vals:
            print(s.name, "바닥 점 없음")
            continue
        fy = float(rgb) if rgb is not None else float(np.mean(vals))
        al = fit(frames, cfg["align"], cfg["head"]["offsetFromCameraM"], fy)
        al["floorRef"] = {"used": "rgb" if rgb is not None else "depthMean", "rgbM": rgb, "smoothedM": sm, "rawM": raw,
                          "nSmoothed": n_sm, "nRaw": n_raw, "rangeM": list(REF_D_M)}
        d = out / s.name
        d.mkdir(parents=True, exist_ok=True)
        (d / "align.json").write_text(json.dumps(al, indent=2), encoding="utf-8")
        print(s.name, f"floor {fy:.3f} ({al['floorRef']['used']}; rgb {rgb}, smoothed {sm}, raw {raw})")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(Path(sys.argv[1]), [Path(a) for a in sys.argv[2:]])
