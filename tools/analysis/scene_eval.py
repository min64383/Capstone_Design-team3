"""장면 지표 집계 (M30, IMPROVE_SPEC §9.4). core `SceneEval`이 재생마다 쓴 `scene_eval.json`을 묶음별로 모은다.

    python scene_eval.py synth <합성 출력 폴더>                       → 열화 강도별 평균·95% 신뢰구간, 단조성, s=0 대 s=1
    python scene_eval.py real <재생 폴더> <변형> <변형> [--scene E]   → 세션 단위 변형 비교(예: B D), 판정 세트는 E 장면
    python scene_eval.py bookmarks <기준 재생 폴더> <출력 json> <세션 폴더> ...   → 정성 평가 책갈피 시각(M30 설계 표 6)

신뢰구간: 부트스트랩 2,000회, 장면(합성)·세션(녹화) 단위 재표본(Efron & Tibshirani 1993). 한 세션 안의 칸은 서로 상관되어
칸 단위 재표본은 구간을 지나치게 좁힌다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

# 주 지표(M30 설계 표 2의 ★)와 나빠지는 방향(+1 = 클수록 나쁨)
HEADLINE = {"surfaceErrorP90M": +1, "falseRatio": +1, "completenessWallObject": -1}
EXTRA = ["surfaceErrorP50M", "insideRatio", "completenessAll", "f1tau", "unknownRatio"]
N_BOOT = 2000
SEED = 30


def load(path: Path) -> dict:
    d = json.loads(path.read_text(encoding="utf-8"))
    d["f1tau"] = d["fScore"]["1tau"]
    return d


def boot_ci(v: np.ndarray, n: int = N_BOOT, seed: int = SEED) -> tuple[float, float, float]:
    """평균과 95% 부트스트랩 구간(백분위)."""
    v = v[~np.isnan(v)]
    if len(v) == 0:
        return np.nan, np.nan, np.nan
    rng = np.random.default_rng(seed)
    m = rng.choice(v, size=(n, len(v)), replace=True).mean(axis=1)
    return float(v.mean()), float(np.percentile(m, 2.5)), float(np.percentile(m, 97.5))


def synth_table(root: Path) -> pd.DataFrame:
    rows = []
    for f in sorted(root.glob("*/s*/scene_eval.json")):
        d = load(f)
        rows.append({"seed": int(f.parent.parent.name), "s": float(f.parent.name[1:]),
                     **{k: (np.nan if d.get(k) is None else float(d[k])) for k in [*HEADLINE, *EXTRA]}})
    return pd.DataFrame(rows)


def judge_synth(df: pd.DataFrame) -> dict:
    """강도별 평균·구간, 주 지표마다 단조성(평균이 강도 순으로 나빠짐)과 s=0·s=1 구간 분리."""
    out = {"scenes": int(df.seed.nunique()), "strengths": sorted(df.s.unique().tolist()), "metrics": {}}
    for k in [*HEADLINE, *EXTRA]:
        per = {s: boot_ci(df[df.s == s][k].to_numpy(float)) for s in out["strengths"]}
        m = {"bySeverity": {str(s): {"mean": v[0], "lo": v[1], "hi": v[2]} for s, v in per.items()}}
        if k in HEADLINE:
            sign = HEADLINE[k]
            means = [per[s][0] * sign for s in out["strengths"]]
            m["monotone"] = bool(all(a < b for a, b in zip(means, means[1:])))
            a, b = per.get(0.0), per.get(1.0)
            m["separated01"] = bool(a and b and ((sign > 0 and a[2] < b[1]) or (sign < 0 and a[1] > b[2])))
        out["metrics"][k] = m
    return out


def real_table(root: Path, variants: list[str], scene: str | None) -> pd.DataFrame:
    rows = []
    for v in variants:
        for f in sorted((root / v).glob("*/scene_eval.json")):
            d = load(f)
            m = json.loads((f.parent / "metrics.json").read_text(encoding="utf-8")) if (f.parent / "metrics.json").is_file() else {}
            sc = m.get("scene", "")
            if scene and not sc.startswith(scene):
                continue
            r = {"variant": v, "session": f.parent.name, "scene": sc, "floorY": d.get("floorY")}
            r.update({k: (np.nan if d.get(k) is None else float(d[k])) for k in [*HEADLINE, *EXTRA]})
            if "rgbFloor" in d:
                rg = d["rgbFloor"]
                r.update({f"rgb_{k}": (np.nan if rg.get(k) is None else float(rg[k])) for k in HEADLINE})
            rows.append(r)
    return pd.DataFrame(rows)


def judge_real(df: pd.DataFrame, base: str, other: str) -> dict:
    """세션 단위 평균·구간과, [other]가 [base]보다 좋고 구간이 겹치지 않는지(주 지표마다)."""
    out = {"sessions": int(df.session.nunique()), "metrics": {}}
    for k in [*HEADLINE, *EXTRA]:
        a = boot_ci(df[df.variant == base][k].to_numpy(float))
        b = boot_ci(df[df.variant == other][k].to_numpy(float))
        m = {base: {"mean": a[0], "lo": a[1], "hi": a[2]}, other: {"mean": b[0], "lo": b[1], "hi": b[2]}}
        if k in HEADLINE:
            sign = HEADLINE[k]
            m["otherBetter"] = bool(b[0] * sign < a[0] * sign)
            m["separated"] = bool((sign > 0 and b[2] < a[1]) or (sign < 0 and b[1] > a[2]))
        out["metrics"][k] = m
    return out


def bookmarks(runs: Path, sessions: list[Path]) -> list[dict]:
    """책갈피 시각(첫 프레임 수신 시각부터 s). 거리는 머리에서 정답 앞면까지 보행선 방향 거리. 규칙(M30 설계 표 6을 실제 보행에 맞춤:
    S 세션은 출발 때 이미 물체가 2.4 m 앞, E02는 머리가 끝 벽 1.2 m까지만 감): E01h 시작 3 s·가운데·끝, E03 돌기 전 끝 벽 1.0 m·돈 뒤 2 s,
    물체 장면 걷기 시작(머리 0.1 m 이동)·가장 가까이, 끝 벽·문 장면 3.0 m·가장 가까이."""
    from align import load_frames, to_truth
    from error_decomp import forward_xz, head_xz, load_cfg
    from metrics import load_truth, scene_of

    out = []
    for s in sessions:
        cfg = load_cfg(s)
        al = json.loads((runs / s.name / "align.json").read_text(encoding="utf-8"))
        f = load_frames(s)
        t_first = f.sysElapsedNs.iloc[0]  # 재생·viewer의 시작 시각 = 첫 행 수신 시각
        f = f[f.tracking == "TRACKING"].reset_index(drop=True)
        fwd = forward_xz(f)
        hxz = head_xz(f[["tx", "tz"]].to_numpy(), fwd, cfg["head"]["offsetFromCameraM"])
        _, _, hz = to_truth(al, hxz[:, 0], np.zeros(len(f)), hxz[:, 1])
        fz = to_truth({**al, "origin": [0.0, 0.0]}, fwd[:, 0], np.zeros(len(f)), fwd[:, 1])[2]
        t = (f.sysElapsedNs - t_first).to_numpy() / 1e9
        truth = load_truth(s, cfg)[0]
        sc = scene_of(s)
        objs = [o for o in truth if o["kind"] == "object"]
        ends = [o for o in truth if o["kind"] == "structure" and o["max"][2] - o["min"][2] < 0.5]
        marks = []

        def first(dist: np.ndarray, d: float, label: str):
            i = np.flatnonzero(dist <= d)
            if len(i):
                marks.append({"tS": round(float(t[i[0]]), 2), "label": label})

        def start_and_closest(front: float, name: str):
            moved = np.flatnonzero(hz - hz[0] > 0.1)
            if len(moved):
                marks.append({"tS": round(float(t[moved[0]]), 2), "label": f"걷기 시작({name} {front - hz[moved[0]]:.1f} m)"})
            i = int(np.argmin(front - hz))
            marks.append({"tS": round(float(t[i]), 2), "label": f"가장 가까이({name} {front - hz[i]:.1f} m)"})

        if sc.startswith("E01"):
            for lab, x in (("시작 3 s", 3.0), ("가운데", t[-1] / 2), ("끝", t[-1] - 1.0)):
                marks.append({"tS": round(float(x), 2), "label": lab})
        elif sc.startswith("E03"):
            front = min(o["min"][2] for o in ends)
            first(front - hz, 1.0, "돌기 전 끝 벽 1.0 m")
            back = np.flatnonzero(fz < -0.5)
            if len(back):
                marks.append({"tS": round(float(t[back[0]] + 2.0), 2), "label": "돈 뒤 2 s"})
        elif objs and objs[0]["name"] != "door":
            # 보행선에 가장 가까운 물체(가로 가운데 |x| 최소)
            o = min(objs, key=lambda o: abs(o["min"][0] + o["max"][0]))
            start_and_closest(o["min"][2], "물체")
        else:
            front = min(o["min"][2] for o in (objs or ends))
            name = "문" if objs else "끝 벽"
            first(front - hz, 3.0, f"{name} 3.0 m")
            i = int(np.argmin(front - hz))
            marks.append({"tS": round(float(t[i]), 2), "label": f"가장 가까이({name} {front - hz[i]:.1f} m)"})
        out.append({"session": s.name, "scene": sc, "truthColors": sc.startswith("E"), "marks": marks})
    return out


def fmt(m: dict) -> str:
    return f"{m['mean']:.3f} [{m['lo']:.3f}, {m['hi']:.3f}]"


def main(args: list[str]) -> None:
    if not args:
        sys.exit(__doc__)
    if args[0] == "synth":
        root = Path(args[1])
        df = synth_table(root)
        res = judge_synth(df)
        (root / "summary.json").write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding="utf-8")
        df.to_csv(root / "summary.csv", index=False)
        print(f"합성 {res['scenes']}장면, 강도 {res['strengths']}")
        for k, m in res["metrics"].items():
            line = "  ".join(f"s{s} {fmt(v)}" for s, v in m["bySeverity"].items())
            tag = f"  단조 {m['monotone']}  s0·s1 분리 {m['separated01']}" if "monotone" in m else ""
            print(f"{k:24s} {line}{tag}")
    elif args[0] == "real":
        root, base, other = Path(args[1]), args[2], args[3]
        scene = args[args.index("--scene") + 1] if "--scene" in args else None
        df = real_table(root, [base, other], scene)
        res = judge_real(df, base, other)
        df.to_csv(root / f"scene_{base}_{other}{'_' + scene if scene else ''}.csv", index=False)
        (root / f"scene_{base}_{other}{'_' + scene if scene else ''}.json").write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding="utf-8")
        print(f"녹화 {res['sessions']}세션 ({scene or '전체'})")
        for k, m in res["metrics"].items():
            tag = f"  {other} 더 좋음 {m['otherBetter']}  분리 {m['separated']}" if "separated" in m else ""
            print(f"{k:24s} {base} {fmt(m[base])}  {other} {fmt(m[other])}{tag}")
    elif args[0] == "bookmarks":
        res = bookmarks(Path(args[1]), [Path(a) for a in args[3:]])
        Path(args[2]).write_text(json.dumps({"note": "M30 정성 평가 책갈피(설계 표 6). scene_eval.py bookmarks가 규칙으로 계산", "sessions": res},
                                            indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        for r in res:
            print(r["session"], r["scene"], [(m["label"], m["tS"]) for m in r["marks"]])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
