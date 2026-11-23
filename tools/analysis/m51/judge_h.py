"""M51 H 판정(명세 §13 M51 v0.4.48 규칙 H1~H3·C1~C4, 재생 없음): run.sh가 쓴 event_eval.json으로.
    python judge_h.py <출력 뿌리(data/sweeps/m51/h)> [--c-pair c,a | c_chest,a_chest]
H1 A + 가슴이 A보다 ①②③에서 나쁘지 않음(STOP 놓침·오경보 시작이 늘지 않고 첫 STOP이 0.10 m 넘게 늦지 않음),
H2 ④ 전환 구간 5° 초과 비율(걷는 세션 블록 합) ≤ 기준선, H3 정상 걷기 부호 오차 중앙값 |·| ≤ 5°(세션마다).
C1 받침대 놓침 0·첫 STOP ≥ 0.9 m(판정 ok)·A보다 0.10 m 넘게 늦지 않음, C2 판정 가능 접근에서 A가 STOP하면 C도 STOP·TTC가 A보다 짧지 않음,
C3 세션별 오경보 시작 ≤ A, C4 물체별 첫 경고 거리가 A보다 0.10 m 넘게 짧지 않음(거리가 없으면 시각 0.1 s). C5·C6은 eval_circ.py.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

TOL_M = 0.10
TOL_S = 0.10
HEADING_MEDIAN_DEG = 5.0


def load(root: Path, v: str) -> dict[str, dict]:
    return {d.name: json.loads((d / "event_eval.json").read_text(encoding="utf-8")) for d in sorted((root / v).iterdir())
            if (d / "event_eval.json").is_file()}


def short(s: str) -> str:
    return s.split("_")[1]


def ev_key(e: dict) -> tuple:
    return e["object"], round(e["entryS"], 1)


def match(ea: list[dict], eb: list[dict]):
    """같은 물체의 STOP 구간 진입을 순서대로 짝짓는다(진입 시각은 설정마다 블록 단위로 조금 다를 수 있음)."""
    by = {}
    for e in eb:
        by.setdefault(e["object"], []).append(e)
    for e in ea:
        cands = by.get(e["object"], [])
        best = min(cands, key=lambda x: abs(x["entryS"] - e["entryS"]), default=None)
        if best is not None and abs(best["entryS"] - e["entryS"]) < 1.0:
            yield e, best
        else:
            yield e, None


def worse_stop(x: dict, ref: dict | None) -> list[str]:
    """x(새 설정)가 ref보다 나쁜 점. 판정 불가·짧은 진입은 비교에서 뺀다."""
    out = []
    if ref is None:
        return out
    if x.get("verdict") in ("undecided", "short") or ref.get("verdict") in ("undecided", "short"):
        return out
    if "stopAlongM" in ref and "stopAlongM" not in x:
        out.append("ref STOP, new none")
    elif "stopAlongM" in ref and x["stopAlongM"] < ref["stopAlongM"] - TOL_M:
        out.append(f"first STOP {x['stopAlongM']} < ref {ref['stopAlongM']} − {TOL_M}")
    if x["kind"] == "moving" and ref.get("ttcS") is not None and x.get("ttcS") is not None and x["ttcS"] < ref["ttcS"]:
        out.append(f"TTC {x['ttcS']} < ref {ref['ttcS']}")
    return out


def main(args: list[str]) -> None:
    root = Path(args[0])
    pair = next((a.split("=", 1)[1] for a in args[1:] if a.startswith("--c-pair=")), "c,a").split(",")
    V = {v: load(root, v) for v in ("base", "a", "a_chest", "c", "c_chest")}
    sess = list(V["a"])

    print("① ② 사건(설정별: 판정 · STOP 거리 · TTC · 첫 경고 거리)")
    for s in sess:
        for v in V:
            for e in V[v][s]["events"]:
                print(f"  {short(s)} {v:8s} {e['kind']:6s} {e['object']:3s} 진입 {e['entryS']:6.2f}  {e.get('verdict'):10s} "
                      f"STOP {e.get('stopAlongM', '-')}  TTC {e.get('ttcS', '-')}±{e.get('ttcBandS', '-')}  최소 {e.get('minAlongM')}")
    print("\n③ 오경보 시작(거짓/전체) · 정답 없는 경고 s · ④ 전환 over5(n) · 정상 over5(n) · 정상 중앙값")
    for s in sess:
        for v in V:
            r = V[v][s]
            fa, h = r["falseAlarm"], r["heading"]
            print(f"  {short(s)} {v:8s} {fa['false']}/{fa['onsets']}  {r['unexplainedWarn']['s']:6.2f}  "
                  f"{h['transition']['over5']}({h['transition']['n']})  {h['steady']['over5']}({h['steady']['n']})  {h['steady'].get('medianDeg')}")
    print("\n첫 경고(물체별, 출력이 정답 0.8 m 안): 설정별 거리 m (시각 s)")
    for s in sess:
        for j, fw in enumerate(V["a"][s]["firstWarn"]):
            cells = []
            for v in V:
                f = V[v][s]["firstWarn"][j]
                cells.append(f"{v} {f['warnAlongM']}({f['warnS']})")
            print(f"  {short(s)} {fw['object']}  " + "  ".join(cells))

    def stop_cmp(new: str, ref: str) -> list[str]:
        bad = []
        for s in sess:
            for x, r in match(V[new][s]["events"], V[ref][s]["events"]):
                bad += [f"{short(s)} {x['object']}: {w}" for w in worse_stop(x, r)]
            mx = sum(e.get("verdict") in ("missed", "late") for e in V[new][s]["events"])
            mr = sum(e.get("verdict") in ("missed", "late") for e in V[ref][s]["events"])
            if mx > mr:
                bad.append(f"{short(s)}: 놓침·늦음 {mx} > {mr}")
        return bad

    def fa_cmp(new: str, ref: str) -> list[str]:
        return [f"{short(s)}: 오경보 {V[new][s]['falseAlarm']['false']} > {V[ref][s]['falseAlarm']['false']}"
                for s in sess if V[new][s]["falseAlarm"]["false"] > V[ref][s]["falseAlarm"]["false"]]

    def pooled_over5(v: str, part: str):
        n = sum(V[v][s]["heading"][part]["n"] for s in sess)
        k = sum((V[v][s]["heading"][part]["over5"] or 0) * V[v][s]["heading"][part]["n"] for s in sess)
        return (k / n if n else None), n

    print("\n=== 진행 방향 규칙(A + 가슴 대 A, ④는 기준선과)")
    h1 = stop_cmp("a_chest", "a") + fa_cmp("a_chest", "a")
    print("H1", "충족" if not h1 else "어긋남", h1)
    tb, nb = pooled_over5("base", "transition")
    tc, nc = pooled_over5("a_chest", "transition")
    print(f"H2 전환 over5 기준선 {tb}({nb}) · A + 가슴 {tc}({nc}) →", "충족" if tc is not None and tb is not None and tc <= tb else "어긋남/판정 불가")
    meds = {short(s): V["a_chest"][s]["heading"]["steady"].get("medianDeg") for s in sess if V["a_chest"][s]["heading"]["steady"]["n"]}
    h3 = all(abs(m) <= HEADING_MEDIAN_DEG for m in meds.values()) if meds else None
    print(f"H3 정상 걷기 중앙값 {meds} →", {True: "충족", False: "어긋남", None: "판정 불가"}[h3])
    for v in ("base", "a", "a_chest", "c", "c_chest"):
        print(f"   ④ {v}: 전환 {pooled_over5(v, 'transition')} 정상 {pooled_over5(v, 'steady')}")

    c, a = pair
    print(f"\n=== C 규칙({c} 대 {a})")
    c1 = []
    for s in sess:
        for x, r in match(V[c][s]["events"], V[a][s]["events"]):
            if x["kind"] == "moving":
                continue
            if x.get("verdict") not in ("ok", "short", "undecided"):
                c1.append(f"{short(s)} {x['object']}: {x.get('verdict')}")
            c1 += [f"{short(s)} {x['object']}: {w}" for w in worse_stop(x, r)]
    print("C1", "충족" if not c1 else "어긋남", c1)
    c2 = []
    for s in sess:
        for x, r in match(V[c][s]["events"], V[a][s]["events"]):
            if x["kind"] == "moving":
                c2 += [f"{short(s)} {x['object']}: {w}" for w in worse_stop(x, r)]
    print("C2", "충족" if not c2 else "어긋남", c2)
    c3 = fa_cmp(c, a)
    print("C3", "충족" if not c3 else "어긋남", c3)
    c4 = []
    for s in sess:
        for fc, fa_ in zip(V[c][s]["firstWarn"], V[a][s]["firstWarn"]):
            if fa_["warnS"] is None:
                continue
            if fc["warnS"] is None:
                c4.append(f"{short(s)} {fc['object']}: A 경고, C 없음")
            elif fc["warnAlongM"] is not None and fa_["warnAlongM"] is not None and fc["warnAlongM"] == fc["warnAlongM"] and fa_["warnAlongM"] == fa_["warnAlongM"]:
                if fc["warnAlongM"] < fa_["warnAlongM"] - TOL_M:
                    c4.append(f"{short(s)} {fc['object']}: {fc['warnAlongM']} < {fa_['warnAlongM']} − {TOL_M}")
            elif fc["warnS"] > fa_["warnS"] + TOL_S:
                c4.append(f"{short(s)} {fc['object']}: 시각 {fc['warnS']} > {fa_['warnS']} + {TOL_S}")
    print("C4", "충족" if not c4 else "어긋남", c4)


if __name__ == "__main__":
    main(sys.argv[1:])
