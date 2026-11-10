"""M57 시선 그늘 깊이 D_그늘 유도(설계 3.2절 규칙 7, 재생 없음): 새로 보인 정지 면의 깊이 수렴 전 메아리가 얼마나 뒤에 찍히는가.

    python shadow_depth.py <재생 폴더>...      (B+W5 재생, 정지 창 = 출력 상자 ± matchRadiusM, stage0과 같은 정의)

정지 출력 창이 처음 생긴 뒤 T_수렴(1.5 s, A-0) 안의 원시 장마다 창 안 정지 원시 점을 되돌아볼 장(v_min 0.30 표)에 투영해, 위반한 점의
깊이 차(지난 깊이 − 지금 점의 그 카메라 깊이)를 모은다. 위반 = 지난 장이 그 자리를 지나 더 먼 곳을 봄 = 수렴 전 메아리의 거리.
D_그늘 = 그 깊이 차의 99% 분위수(Claude 의견의 규칙: 메아리의 99%를 덮는 가장 짧은 그늘). T는 움직이는 정답 1 m 안 창을 뺀다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

import stage0 as S
from free_space import compare

T_CONV_S = 1.5
V_MIN = 0.30
ROOT = Path(__file__).resolve().parents[3]


def run(runs: list[Path]) -> None:
    lag_b, m_b, _ = S.margin_table(ROOT / "data/sweeps/m56/stage0/sigma.json", V_MIN, 0.1)
    allv = []
    for run in runs:
        session, cfg = S.session_of(run), S.load_config(run)
        margin = cfg["track"]["matchRadiusM"]
        meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
        fr = S.load_frames(session).reset_index(drop=True)
        sp = pd.read_csv(run / "slow_path.csv")
        ob = pd.read_csv(run / "obstacles.csv")
        first = ob.groupby("id").tCaptureNs.min()
        frames = S.raw_frames(session, meta, fr)
        caps = np.array([f.t_capture_ns for f, _ in frames])
        mv = S.movers(session)
        ob_t = np.sort(ob.tCaptureNs.unique())
        for i, (f, arrive) in enumerate(frames):
            fy = S.floor_at(sp, f.t_capture_ns)
            k = int(np.searchsorted(ob_t, arrive, side="right")) - 1
            if fy is None or k < 0:
                continue
            p, z = S.points_of(f, 30)
            keep = S.obstacle_points(p, f.cam_w, fy, cfg)
            p, z = p[keep], z[keep]
            for o in ob[ob.tCaptureNs == ob_t[k]].itertuples(index=False):
                if arrive - first[o.id] > T_CONV_S * 1e9:
                    continue
                cx, cz = (o.aabbMinX + o.aabbMaxX) / 2, (o.aabbMinZ + o.aabbMaxZ) / 2
                if any((r := tr(f.t_capture_ns / 1e9)) is not None and np.hypot(cx - r[0][0], cz - r[0][1]) <= S.NEAR_PERSON_M for _, tr, _, _ in mv):
                    continue
                lo = np.array([o.aabbMinX, o.aabbMinY, o.aabbMinZ]) - margin
                hi = np.array([o.aabbMaxX, o.aabbMaxY, o.aabbMaxZ]) + margin
                sel = np.all((p >= lo) & (p <= hi), axis=1)
                if sel.sum() < 5:
                    continue
                q, zq = p[sel], z[sel]
                zb = np.clip(np.digitize(zq, S.Z_EDGES) - 1, 0, len(S.Z_EDGES) - 2)
                back = np.searchsorted(caps[:i], f.t_capture_ns - (lag_b[zb] * 0.1e9).astype(np.int64), side="right") - 1
                for j in np.unique(back):
                    if j < 0:
                        continue
                    s2 = back == j
                    ok, diff, _ = compare(q[s2], frames[j][0], 30)
                    v = ok & (diff > m_b[zb[s2]])
                    allv.append(diff[v])
        print(f"{run.name}: {sum(len(a) for a in allv)}", file=sys.stderr)
    d = np.concatenate(allv) if allv else np.array([])
    print(f"위반 점 {len(d)}, 깊이 차 분위수 50/90/95/99/99.9%:", np.quantile(d, [0.5, 0.9, 0.95, 0.99, 0.999]).round(3).tolist() if len(d) else None)


if __name__ == "__main__":
    run([Path(a) for a in sys.argv[1:]])
