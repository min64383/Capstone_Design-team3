"""0-2 비교 요약: 창(track)별 위반 비율 S_W의 정지 분포와 사람 분포(거리 구간별).

    python compare_windows.py <viol.csv> [hi]     hi면 신뢰도 ≥ 128 점만(nCmpHi·nViolHi), 아니면 전체(nCmp·nViol 또는 Hi+Lo)
"""
from __future__ import annotations

import sys

import numpy as np
import pandas as pd

from free_space import window_stat


def summarize(path: str, hi_only: bool) -> None:
    d = pd.read_csv(path)
    if hi_only:
        d["n"], d["v"] = d.nCmpHi, d.nViolHi
    elif "nCmp" in d:
        d["n"], d["v"] = d.nCmp, d.nViol
    else:
        d["n"], d["v"] = d.nCmpHi + d.nCmpLo, d.nViolHi + d.nViolLo
    print(path, "hi only" if hi_only else "all conf")
    print("  points compared per row (median):", d.groupby(["group", "kind"]).n.median().to_dict())
    for W in (1, 3, 5):
        res = []
        for (s, k, g), x in d.sort_values("tRawNs").groupby(["session", "kind", "gate"]):
            sw = window_stat(x.n.values, x.v.values, W, 5)
            hd = x["hd"].values if "hd" in x else np.full(len(x), np.nan)
            for v, h in zip(sw, hd):
                if np.isfinite(v):
                    res.append((x.group.iloc[0], k, v, h))
        r = pd.DataFrame(res, columns=["group", "kind", "S", "hd"])
        st = r[r.kind == "static"]
        line = "  W%d static p99/max: " % W + " ".join(f"{g} {np.percentile(x.S, 99):.2f}/{x.S.max():.2f}(n{len(x)})" for g, x in st.groupby("group"))
        pr = r[r.kind == "person"]
        line += " | person " + " ".join(
            f"{a}-{b}m p10/p50 {np.percentile(z.S, 10):.2f}/{np.median(z.S):.2f}(n{len(z)})"
            for a, b in ((0, 2), (2, 3), (3, 9)) for z in [pr[(pr.hd >= a) & (pr.hd < b)]] if len(z))
        print(line)


if __name__ == "__main__":
    summarize(sys.argv[1], len(sys.argv) > 2 and sys.argv[2] == "hi")
