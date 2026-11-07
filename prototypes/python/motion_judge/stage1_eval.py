"""M56 1단계 판정 요약(설계 표 7): 변형 재생 폴더들을 기준과 비교한다.

    python stage1_eval.py <변형 이름> <실내 폴더> <T 폴더> <O01 폴더>

실내(metrics.json): STOP 놓침 합·오경보 시작 합, 물체별 첫 STOP이 기준(29 = data/sweeps/m55/in_B_L)보다 0.05 m 넘게 늦은 물체.
T(event_eval.json): 정답 없는 경고 s 합·거짓 시작 합, 150338 사람 STOP 거리·TTC, 받침대(150957·151045) 첫 STOP 거리.
O01(guidance.csv): 세션마다 첫 STOP(시각, 거리)을 P+B(data/sweeps/m54/o01s_PB)와 비교.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
BASE_IN = ROOT / "data/sweeps/m55/in_B_L"
PB_O01 = ROOT / "data/sweeps/m54/o01s_PB"


def indoor(d: Path) -> None:
    miss = fa = 0
    late = []
    for s in sorted(p for p in d.iterdir() if p.is_dir()):
        if not (s / "metrics.json").is_file() or not (BASE_IN / s.name / "metrics.json").is_file():
            print(f"  (metrics.json 없음: {s.name})")
            continue
        m = json.loads((s / "metrics.json").read_text(encoding="utf-8"))
        b = json.loads((BASE_IN / s.name / "metrics.json").read_text(encoding="utf-8"))
        miss += m.get("missedStop", 0)
        fa += m.get("distanceGuidance", {}).get("falseAlarmOnsets", 0)
        for o, v in (b.get("firstStopDistanceM") or {}).items():
            w = (m.get("firstStopDistanceM") or {}).get(o)
            if v is not None and (w is None or w < v - 0.05):
                late.append(f"{s.name[9:15]} {o} {w if w is None else round(w, 3)} (기준 {round(v, 3)})")
    print(f"  실내: STOP 놓침 {miss}, 오경보 시작 {fa}, 첫 STOP 한도 넘음 {late if late else '없음'}")


def outdoor(d: Path) -> None:
    uw = fa = 0.0
    extra = []
    for s in sorted(p for p in d.iterdir() if p.is_dir()):
        e = json.loads((s / "event_eval.json").read_text(encoding="utf-8"))
        uw += e["unexplainedWarn"]["s"]
        fa += e["falseAlarm"]["false"]
        for ev in e.get("events", []):
            if s.name.startswith("20261102_150338") and ev["kind"] == "moving":
                extra.append(f"150338 STOP {ev.get('stopAlongM')} m TTC {ev.get('ttcS', ev.get('ttc'))}")
            if s.name[9:15] in ("150957", "151045") and ev["kind"] == "static":
                extra.append(f"{s.name[9:15]} 받침대 첫 STOP {ev.get('stopAlongM')}")
    print(f"  T: 정답 없는 경고 {uw:.2f} s, 거짓 시작 {int(fa)}; " + "; ".join(extra))


def o01(d: Path) -> None:
    out = []
    for s in sorted(p for p in d.iterdir() if p.is_dir()):
        def first(p):
            g = pd.read_csv(p / "guidance.csv")
            st = g[g.band == "STOP"]
            return None if st.empty else (round((st.tBlockNs.iloc[0] - g.tBlockNs.iloc[0]) / 1e9, 2), round(float(st.distanceM.iloc[0]), 3))
        a, b = first(PB_O01 / s.name), first(s)
        if a != b:
            out.append(f"{s.name[9:15]} P+B {a} → {b}")
    print("  O01: 첫 STOP이 P+B와 다른 세션 " + (", ".join(out) if out else "없음"))


if __name__ == "__main__":
    name, din, dt, do = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4])
    print(name)
    indoor(din)
    outdoor(dt)
    o01(do)
