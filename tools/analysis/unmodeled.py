"""정답 밖 물체 후보 (M33 설계 3.4절). 두 변형(기본 B와 R3)의 재생에서 core가 쓴 `false_cells.csv`(정답 좌표 칸마다 헛 칸으로 센 표본 수와
처음·마지막 시각)를 읽어, 두 변형 모두에서 1 s 넘게 남고 정답 바닥 0.15 m 위인 헛 칸을 26이웃으로 묶은 덩어리를 후보로 낸다.
개발자가 후보 시각의 RGB 그림(`:viewer:snapshot -Ptimes=`)으로 실제 물체인지 확인하고, 실제 물체면 정답에 `kind: "unmodeled"` 상자로 넣는다.

    python unmodeled.py <재생 폴더 A> <재생 폴더 B> <세션 ID> ...   → 후보 표(JSON), 세션마다 그림 시각
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

VOXEL_M = 0.05
MIN_SPAN_S = 1.0
FLOOR_BAND_M = 0.15
MIN_CELLS = 10


def persistent(run: Path) -> set[tuple[int, int, int]]:
    f = run / "false_cells.csv"
    if not f.is_file():
        return set()
    d = pd.read_csv(f)
    d = d[((d.tLastNs - d.tFirstNs) / 1e9 >= MIN_SPAN_S) & ((d.iy + 0.5) * VOXEL_M > FLOOR_BAND_M)]
    return set(zip(d.ix, d.iy, d.iz))


def components(cells: set[tuple[int, int, int]]) -> list[list[tuple[int, int, int]]]:
    left, out = set(cells), []
    while left:
        stack = [left.pop()]
        comp = []
        while stack:
            c = stack.pop()
            comp.append(c)
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        n = (c[0] + dx, c[1] + dy, c[2] + dz)
                        if n in left:
                            left.remove(n)
                            stack.append(n)
        out.append(comp)
    return out


def main(args: list[str]) -> None:
    a, b, sessions = Path(args[0]), Path(args[1]), args[2:]
    res = {}
    for s in sessions:
        both = persistent(a / s) & persistent(b / s)
        f = pd.read_csv(a / s / "false_cells.csv")
        t0 = f.tFirstNs.min()
        times = f.set_index(["ix", "iy", "iz"])
        cands = []
        for comp in components(both):
            if len(comp) < MIN_CELLS:
                continue
            c = np.array(comp, dtype=float)
            lo, hi = c.min(axis=0) * VOXEL_M, (c.max(axis=0) + 1) * VOXEL_M
            mid = np.median([(times.loc[k].tFirstNs + times.loc[k].tLastNs) / 2 for k in comp if k in times.index])
            cands.append({"cells": len(comp), "min": lo.round(3).tolist(), "max": hi.round(3).tolist(), "tS": round(float((mid - t0) / 1e9), 2)})
        res[s] = sorted(cands, key=lambda x: -x["cells"])
        print(s, [(x["cells"], x["min"], x["max"], x["tS"]) for x in res[s]])
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
