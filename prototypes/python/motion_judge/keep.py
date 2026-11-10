"""M58 유지 지표(설계 표 1): 첫 움직임 판정 뒤, 정답 사람 원기둥 안 원시 점(신뢰도 ≥ 30)이 N개 이상인 깊이 장 중
정답 0.8 m 안에 움직이는 track이 있는 장과 출력이 있는 장(diag_person.py와 같은 정의, N = 50 = M57 N_min).

    python keep.py <재생 폴더>...
"""
from __future__ import annotations

import sys
from pathlib import Path

from diag_person import rows

N_RAW = 50


def keep(run: Path) -> dict:
    r = list(rows(run))
    first = next((t for t, *_, trk, _o in r if any(x.endswith("M") for x in trk)), None)
    if first is None:
        return {"first": None}
    vis = [(t, trk, o) for t, _d, _l, _s, n_r, _c, trk, o in r if t >= first and n_r >= N_RAW]
    moving = [t for t, _d, _l, _s, _r, _c, trk, _o in r if any(x.endswith("M") for x in trk)]
    return {
        "first": first, "vis": len(vis),
        "track": sum(any(x.endswith("M") for x in trk) for _, trk, _ in vis),
        "out": sum(o > 0 for *_, o in vis),
        "lastVis": max((t for t, *_ in vis), default=None), "lastTrack": max(moving),
    }


if __name__ == "__main__":
    for a in sys.argv[1:]:
        k = keep(Path(a))
        name = Path(a).name.split("_")[1] if "_" in Path(a).name else a
        if k["first"] is None:
            print(f"{name}: 움직이는 track 없음")
            continue
        print(f"{name}: 첫 판정 {k['first']:.2f} s, 원시 ≥ {N_RAW} 장 {k['vis']}, 움직이는 track {k['track']} ({k['track'] / max(k['vis'], 1):.0%}), "
              f"출력 {k['out']}, 마지막 원시 ≥ {N_RAW} {k['lastVis']:.2f} s / 마지막 track {k['lastTrack']:.2f} s")
