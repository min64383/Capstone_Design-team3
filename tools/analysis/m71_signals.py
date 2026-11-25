"""M71 신호 판정(설계 5.3절): 재생 묶음 폴더의 speech.csv를 세션 정답 annotations/signals.json과 견준다.

    python tools/analysis/m71_signals.py <재생 묶음 폴더> [<재생 묶음 폴더> ...]

재생 묶음 폴더 = 세션 ID 폴더들을 담은 폴더(data/sweeps/m71/out/<이름>). 정답이 없는 세션은 신호 전환이 없는 것으로 본다(기존 76세션·M69 장면).
① 거짓 전환: “초록불로 바뀜”이 정답 전환(시야 안) 뒤 [0, 3] s 안이 아니면 거짓. 보정 세션은 따로 센다.
② 전환 안내: 시험 세션의 시야 안 전환 가운데 3 s 안에 말한 몫과 지연.
보고만: “빨간불”이 정답상 RED(또는 FLASH 뒤 RED) 구간에서 나왔는지.
"""
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GREEN, RED = "초록불로 바뀜", "빨간불"
WITHIN_S = 3.0


def load_gt(sid):
    p = ROOT / "data" / "sessions" / sid / "annotations" / "signals.json"
    if not p.is_file():
        return None
    return json.loads(p.read_text(encoding="utf-8"))


def t0_of(sid):
    with open(ROOT / "data" / "sessions" / sid / "frames.csv", encoding="utf-8") as f:
        return int(next(csv.DictReader(f))["tNs"])


def state_at(gt, t):
    for ph in gt["phases"]:
        if ph["from"] <= t <= ph["to"]:
            return ph["state"]
    return "UNKNOWN"


def judge(folder):
    folder = Path(folder)
    false_test, false_cal, spoken, total, lat, red_ok, red_all = [], [], 0, 0, [], 0, 0
    n_sessions = 0
    for sd in sorted(p for p in folder.iterdir() if p.is_dir()):
        sp = sd / "speech.csv"
        if not sp.is_file():
            continue
        n_sessions += 1
        sid = sd.name
        gt = load_gt(sid)
        role = gt["role"] if gt else "none"
        trans = [tr for tr in (gt["transitions"] if gt else []) if tr.get("inView", True)]
        with open(sp, encoding="utf-8") as f:
            said = list(csv.DictReader(f))
        t0 = gt["t0Ns"] if gt else (t0_of(sid) if said else 0)
        greens = [(int(r["tNs"]) - t0) / 1e9 for r in said if r["text"] == GREEN]
        reds = [(int(r["tNs"]) - t0) / 1e9 for r in said if r["text"] == RED]
        for g in greens:
            ok = any(0 <= g - tr["t"] <= WITHIN_S for tr in trans)
            if not ok:
                (false_cal if role == "calibration" else false_test).append((sid, round(g, 2)))
        if gt:
            for r in reds:
                red_all += 1
                red_ok += state_at(gt, r) == "RED"
        if role == "test":
            for tr in trans:
                total += 1
                hits = [g - tr["t"] for g in greens if 0 <= g - tr["t"] <= WITHIN_S]
                if hits:
                    spoken += 1
                    lat.append(round(min(hits), 2))
        if gt or greens or reds:
            print(f"  {sid} [{role}] 전환 {[tr['t'] for tr in trans]} 초록불로 바뀜 {[round(g, 2) for g in greens]} 빨간불 {[round(r, 2) for r in reds]}")
    print(f"{folder.name}: 세션 {n_sessions}")
    print(f"  ① 거짓 '초록불로 바뀜'(보정 세션 뺌) {len(false_test)} {false_test}  / 보정 세션 {len(false_cal)} {false_cal}")
    print(f"  ② 시험 전환 {total}개 중 3 s 안 {spoken} (지연 s {lat})")
    print(f"  보고: '빨간불' {red_all}번 중 정답 RED 구간 {red_ok} (정답 있는 세션만)")


if __name__ == "__main__":
    for a in sys.argv[1:]:
        judge(a)
