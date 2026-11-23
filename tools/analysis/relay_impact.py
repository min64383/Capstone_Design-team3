"""데모 중계가 S10 안내에 주는 영향(M67 설계 표 7의 ①, 프로포절 기준 ⑦).

실행 기록 폴더(S10 files/runs/<시각>)를 중계 끔·켬으로 받아 느린 경로 처리 빈도, 처리 시간(tDone − tStart) p95,
정보 나이 중앙값을 폴더마다 구하고, 끔들의 평균과 켬들의 평균을 견준다. 기준은 M65 안내 영향 기준(빈도 감소·p95 증가 ≤ 10 %,
나이 중앙값 증가 ≤ 10 ms).

    python tools/analysis/relay_impact.py --off <끔1> <끔2> --on <켬1> <켬2>
"""
import argparse
from pathlib import Path

import pandas as pd

KEYS = ("hz", "procP95Ms", "ageP50Ms")


def run_stats(d: Path) -> dict:
    sp = pd.read_csv(d / "slow_path.csv")
    g = pd.read_csv(d / "guidance.csv")
    dur = (sp.tCaptureNs.max() - sp.tCaptureNs.min()) / 1e9
    age = g.infoAgeMs.dropna()  # 음원이 있는 블록만 기록된다(metrics.run_metrics와 같음)
    return {
        "run": d.name,
        "durationS": float(dur),
        "hz": float(len(sp) / dur) if dur > 0 else float("nan"),
        "procP95Ms": float(((sp.tDoneNs - sp.tStartNs) / 1e6).quantile(0.95)),
        "ageP50Ms": float(age.median()) if len(age) else float("nan"),
    }


def compare(off: list[dict], on: list[dict]) -> dict:
    o = {k: sum(r[k] for r in off) / len(off) for k in KEYS}
    n = {k: sum(r[k] for r in on) / len(on) for k in KEYS}
    return {
        "off": o, "on": n,
        "hzDropPct": 100 * (o["hz"] - n["hz"]) / o["hz"],
        "procP95RisePct": 100 * (n["procP95Ms"] - o["procP95Ms"]) / o["procP95Ms"],
        "ageP50RiseMs": n["ageP50Ms"] - o["ageP50Ms"],
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--off", nargs="+", type=Path, required=True)
    ap.add_argument("--on", nargs="+", type=Path, required=True)
    ap.add_argument("--max-pct", type=float, default=10.0, help="빈도 감소·p95 증가 허용(%%)")
    ap.add_argument("--max-age-ms", type=float, default=10.0, help="나이 중앙값 증가 허용(ms)")
    a = ap.parse_args()
    off = [run_stats(d) for d in a.off]
    on = [run_stats(d) for d in a.on]
    for label, rows in (("off", off), ("on", on)):
        for r in rows:
            print(f"{label:3} {r['run']}: {r['durationS']:.0f} s, slow path {r['hz']:.2f} Hz, proc p95 {r['procP95Ms']:.1f} ms, info age p50 {r['ageP50Ms']:.1f} ms")
    c = compare(off, on)
    ok = c["hzDropPct"] <= a.max_pct and c["procP95RisePct"] <= a.max_pct and c["ageP50RiseMs"] <= a.max_age_ms
    print(f"mean off {c['off']['hz']:.2f} Hz / {c['off']['procP95Ms']:.1f} ms / {c['off']['ageP50Ms']:.1f} ms, "
          f"on {c['on']['hz']:.2f} Hz / {c['on']['procP95Ms']:.1f} ms / {c['on']['ageP50Ms']:.1f} ms")
    print(f"slow path rate drop {c['hzDropPct']:+.1f} %, proc p95 rise {c['procP95RisePct']:+.1f} %, info age p50 rise {c['ageP50RiseMs']:+.1f} ms "
          f"-> {'within' if ok else 'OVER'} the limit ({a.max_pct:g} %, {a.max_age_ms:g} ms)")


if __name__ == "__main__":
    main()
