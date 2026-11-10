"""M56 0-6 P처럼 판 사전 점검(설계 3.7절, 표 5, 재생 없음).

    python plike_check.py <재생 폴더>...

정답 물체마다(실내 obstacles.json 상자 + align.json, T 정지 정답 선 클릭 둘레 0.3 m) 상자 안 원시 점(신뢰도 ≥ 30, 바닥 띠 위, 경계 띠 깎음)의
다수 라벨을 본다. 장애물 묶음(라벨 없음·나무·구조물·물체·차량·사람)이면 P처럼 판의 영향을 받는다.
원시만 점유 시각: 5 cm 칸마다 원시 장 하나에 한 표(무게 1 = vision.obstacleRawWeight), 감쇠 없음(P처럼 판에 유리하게), 표 ≥ 5인 칸이
cluster.minSamples(5)개 이상이 된 첫 시각. 평활 기준 시각: 같은 규칙을 평활 장(30 Hz)으로. 마감: 머리(카메라 − 0.39 m, 실내 정렬 규약)가
물체 앞면에서 1.0 m(policy.stopM) 안에 든 첫 시각(T는 카메라 기준). 한 물체라도 원시만 점유가 마감보다 늦으면 재생하지 않는다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

import stage0 as S
import same_time as T
from align import to_truth  # noqa: E402

OBSTACLE_LABELS = {0, 3, 7, 8, 9, 10}
VOXEL_M, VOTES, MIN_VOX, HEAD_BACK_M, STOP_M = 0.05, 5, 5, 0.39, 1.0
NAMES = ["UNLABELED", "SKY", "BUILDING", "TREE", "ROAD", "SIDEWALK", "TERRAIN", "STRUCTURE", "OBJECT", "VEHICLE", "PERSON", "WATER"]


def regions(run: Path, session: Path):
    """(이름, 점 → 안인지 함수, 카메라 → 앞면까지 수평 거리 함수)."""
    out = []
    ob = session / "annotations" / "obstacles.json"
    al = run / "align.json"
    if ob.is_file() and al.is_file():
        a = json.loads(al.read_text(encoding="utf-8"))
        for o in json.loads(ob.read_text(encoding="utf-8")).get("obstacles", []):
            lo, hi = np.array(o["min"]) - 0.05, np.array(o["max"]) + 0.05

            def inside(p, lo=lo, hi=hi, a=a):
                x, y, z = to_truth(a, p[:, 0], p[:, 1], p[:, 2])
                return (x >= lo[0]) & (x <= hi[0]) & (y >= max(lo[1], 0.05)) & (y <= hi[1]) & (z >= lo[2]) & (z <= hi[2])

            def dist(cam, o=o, a=a):
                x, _, z = to_truth(a, cam[0], cam[1], cam[2])
                z = z - HEAD_BACK_M
                dx = max(o["min"][0] - x, 0.0, x - o["max"][0])
                dz = max(o["min"][2] - z, 0.0, z - o["max"][2])
                return float(np.hypot(dx, dz))
            out.append((o.get("name", "?"), inside, dist))
    ev = S.E.load_events(session) or {}
    for o in ev.get("objects", []):
        if o["kind"] != "static" or o.get("added") == "check":
            continue
        cs = np.array([[c["pointW"][0], c["pointW"][2]] for c in S.E.clicks_of(o)])
        fy = float(np.median([c["floorY"] for c in S.E.clicks_of(o)]))

        def near(xz, cs=cs):
            return np.min(np.hypot(xz[:, None, 0] - cs[None, :, 0], xz[:, None, 1] - cs[None, :, 1]), axis=1)

        def inside(p, near=near, fy=fy):
            return (near(p[:, [0, 2]]) <= 0.3) & (p[:, 1] > fy + 0.05) & (p[:, 1] < fy + 1.5)

        def dist(cam, near=near):
            return float(near(np.array([[cam[0], cam[2]]]))[0])
        out.append((o.get("label", "?"), inside, dist))
    return out


def first_occupied(seq) -> float | None:
    """[(시각 s, 칸 키 집합)] → 표 ≥ VOTES인 칸이 MIN_VOX개 이상이 된 첫 시각."""
    votes: dict = {}
    for t, keys in seq:
        for k in keys:
            votes[k] = votes.get(k, 0) + 1
        if sum(1 for v in votes.values() if v >= VOTES) >= MIN_VOX:
            return t
    return None


def keys_of(p: np.ndarray) -> set:
    return set(map(tuple, np.floor(p / VOXEL_M).astype(np.int64)))


def check(run: Path) -> list[dict]:
    session, cfg = S.session_of(run), S.load_config(run)
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = S.load_frames(session).reset_index(drop=True)
    sp = pd.read_csv(run / "slow_path.csv")
    regs = regions(run, session)
    if not regs:
        return []
    raw = S.raw_frames(session, meta, fr)
    scaps, sfr = T.smoothed_frames(session, meta, fr)
    L = S._Labels(session, fr, meta) if (session / "semantic" / "index.csv").is_file() else None
    band = T.Band(L, raw[0][0].fx if raw else 1.0)
    max_age = cfg["vision"]["maxLabelAgeMs"] * 1e6
    t0 = raw[0][0].t_capture_ns / 1e9 if raw else 0.0
    res = []
    for name, inside, dist in regs:
        lab = np.zeros(12, np.int64)
        seq_raw, seq_sm, deadline = [], [], None
        for f, arrive in raw:
            fy = S.floor_at(sp, f.t_capture_ns)
            if fy is None:
                continue
            p, _, conf = S.points_of(f, 30, with_conf=True)
            p = p[S.obstacle_points(p, f.cam_w, fy, cfg)]
            if len(p) == 0:
                continue
            p = p[inside(p)]
            if len(p):
                p = p[~band.inside(arrive, max_age, p)]
            if len(p) and L is not None:
                got = L.at(arrive)
                if got is not None:
                    v = S._Labels.read(got[1], p)
                    lab += np.bincount(v[v >= 0], minlength=12)[:12]
            seq_raw.append((f.t_capture_ns / 1e9, keys_of(p) if len(p) else set()))
            if deadline is None and dist(f.cam_w) <= STOP_M:
                deadline = f.t_capture_ns / 1e9
        for f in sfr:
            fy = S.floor_at(sp, f.t_capture_ns)
            if fy is None:
                continue
            p, _ = S.points_of(f, 1)
            p = p[S.obstacle_points(p, f.cam_w, fy, cfg)]
            p = p[inside(p)] if len(p) else p
            seq_sm.append((f.t_capture_ns / 1e9, keys_of(p) if len(p) else set()))
        maj = int(lab.argmax()) if lab.sum() else -1
        tr, ts = first_occupied(seq_raw), first_occupied(sorted(seq_sm))
        res.append(dict(session=session.name, object=name, majority=NAMES[maj] if maj >= 0 else "-",
                        obstacleLabel=maj in OBSTACLE_LABELS, labelShare=float(lab.max() / lab.sum()) if lab.sum() else np.nan,
                        rawOccS=None if tr is None else tr - t0, smOccS=None if ts is None else ts - t0,
                        deadlineS=None if deadline is None else deadline - t0))
    return res


def main(runs: list[Path]) -> None:
    rows = []
    for run in runs:
        rows += check(run)
        print(run.name, file=sys.stderr)
    r = pd.DataFrame(rows)
    pd.set_option("display.width", 200)
    out = Path(__file__).resolve().parents[3] / "data" / "sweeps" / "m56" / "stage0" / "plike.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    r.to_csv(out, index=False)
    print(r.round(2).to_string(index=False))
    aff = r[r.obstacleLabel]
    late = aff[(aff.rawOccS.isna()) | (aff.deadlineS.notna() & (aff.rawOccS > aff.deadlineS))]
    print(f"장애물 라벨 정답 물체 {len(aff)}개 중 원시만 점유가 마감보다 늦거나 없음: {len(late)}개")


if __name__ == "__main__":
    main([Path(a) for a in sys.argv[1:]])
