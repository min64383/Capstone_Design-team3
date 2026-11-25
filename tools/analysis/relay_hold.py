"""데모 중계 묵힘(holdMs) 정하기(M68 설계 3.3절).

서버 실행 폴더(data/relay/runs/<시각>)의 uploads.csv로 서버 재생 규칙(core RelayPlayback)을 다시 돌린다.
묵힘 후보 h마다 서버 블록 간격으로 재생 시각 P = 서버 시각 − 시계 차(그때까지 온 묶음의 도착 − 보낸 시각 최솟값) − h를 만들고,
그때까지 도착한 표본 중 P 이하의 마지막 것을 고른다. 덧붙은 나이(P − 그 표본 시각)가 A = maxInfoAgeMs − S10 정보 나이 중앙값을
넘는 시간 몫 f(h)를 센다(중계 때문에 소리가 꺼졌을 몫). 규칙: f(h) ≤ 목표인 가장 작은 h + 여유.
버린 묶음(번호 빈 곳)은 어떤 h로도 메워지지 않으므로 따로 센다. 실제 묵힘의 계산값은 stats.csv 1 s 점과 견줘 계산을 확인한다.

    python tools/analysis/relay_hold.py data/relay/runs/<시각> --s10-run <S10 runs/시각> [--s10-run ...]
    python tools/analysis/relay_hold.py <서버 폴더> --age-ms 265      # S10 기록이 없을 때 나이 중앙값을 직접
"""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]


def settings() -> dict:
    """서버와 같은 값: relay.json(+ git 밖 server.json)의 holdMs·lostMs, 앱 기본 설정의 maxInfoAgeMs·블록 크기·표본율."""
    r = json.loads((ROOT / "app/src/bench/assets/relay.json").read_text(encoding="utf-8"))
    local = ROOT / "data/relay/server.json"
    if local.is_file():
        r.update(json.loads(local.read_text(encoding="utf-8")))
    c = json.loads((ROOT / "app/src/main/assets/config/default.json").read_text(encoding="utf-8"))
    return {"holdMs": r["holdMs"], "lostMs": r["lostMs"], "maxInfoAgeMs": c["policy"]["maxInfoAgeMs"],
            "blockSize": c["audio"]["blockSize"], "sampleRate": c["audio"]["sampleRate"]}


def block_times(lo_ns: int, hi_ns: int, block: int, rate: int) -> np.ndarray:
    """서버 렌더 블록 시각(t0부터 ns). RelayServer.renderLoop와 같은 식: k·block·1e9 / rate(정수 나눗셈)."""
    k0 = lo_ns * rate // (block * 1_000_000_000)
    k1 = hi_ns * rate // (block * 1_000_000_000) + 2
    k = np.arange(k0, k1, dtype=np.int64)
    return k * block * 1_000_000_000 // rate


def simulate(rows: pd.DataFrame, hold_ns: int, block: int, rate: int):
    """한 세션(응답 200 묶음, 도착 순). 돌려줌: 블록 시각 w, 재생 시각 P, 덧붙은 나이(ns, 표본 없으면 inf). 범위는 P가 첫·끝 표본 사이인 블록."""
    rows = rows.sort_values("arrivalNs")
    a = rows.arrivalNs.to_numpy(np.int64)
    off = np.minimum.accumulate(a - rows.sentNs.to_numpy(np.int64))
    # 표본 시각: sampleUs 칸(첫 표본부터 µs)이 있으면 그대로, 없으면(M68 초기 기록) 첫·끝 사이를 고르게 어림(실제 간격 17~31 ms라 오차 수 ms)
    ts, ta = [], []
    exact = rows.sampleUs if "sampleUs" in rows else [None] * len(rows)
    for f, l, n, arr, us in zip(rows.firstTNs, rows.lastTNs, rows.samples, a, exact):
        if n > 0:
            if isinstance(us, str) and us:
                ts.append(int(f) + np.array([int(x) for x in us.split(";")], np.int64) * 1000)
            else:
                ts.append(np.linspace(f, l, int(n)).astype(np.int64))
            ta.append(np.full(int(n), arr, np.int64))
    ts, ta = np.concatenate(ts), np.concatenate(ta)
    w = block_times(int(a[0]), int(a[-1]) + hold_ns, block, rate)
    w = w[w >= a[0]]
    p = w - off[np.searchsorted(a, w, "right") - 1] - hold_ns
    keep = (p >= ts.min()) & (p <= ts.max())
    w, p = w[keep], p[keep]
    # 표본 i는 도착했고(w ≥ 도착) P ≥ 표본 시각인 첫 블록부터 쓸 수 있다. P는 줄지 않으므로 그 블록의 누적 최댓값이 쓰는 표본이다
    use = np.maximum(np.searchsorted(w, ta, "left"), np.searchsorted(p, ts, "left"))
    held = np.full(len(w), np.iinfo(np.int64).min, np.int64)
    ok = use < len(w)
    np.maximum.at(held, use[ok], ts[ok])
    held = np.maximum.accumulate(held)
    added = np.where(held == np.iinfo(np.int64).min, np.inf, (p - held).astype(float))
    return w, p, added


def silent_fraction(sessions: list[pd.DataFrame], hold_ms: float, a_ms: float, block: int, rate: int) -> tuple[float, float]:
    """(f, 평가한 시간 s). 세션들의 블록을 합쳐 덧붙은 나이 > A인 몫."""
    n_bad = n_all = 0
    for rows in sessions:
        _, _, added = simulate(rows, int(hold_ms * 1e6), block, rate)
        n_bad += int((added > a_ms * 1e6).sum())
        n_all += len(added)
    return (n_bad / n_all if n_all else float("nan")), n_all * block / rate


def dropped(rows_all: pd.DataFrame) -> dict:
    """세션마다 번호 빈 곳(S10이 버리거나 보내지 못한 묶음)과 409 수."""
    out = {}
    for s, g in rows_all.groupby("session"):
        span = int(g.seq.max() - g.seq.min() + 1)
        out[s] = {"made": span, "arrived": int(g.seq.nunique()), "missing": span - int(g.seq.nunique()), "409": int((g.code == 409).sum())}
    return out


def check_against_stats(run: Path, sessions: list[pd.DataFrame], hold_ms: float, block: int, rate: int) -> np.ndarray:
    """실제 묵힘의 계산 덧붙은 나이와 서버 stats.csv(1 s 점, 그 순간의 마지막 블록)의 차이(ms). 세션 처음 10 s는 뺀다."""
    st = pd.read_csv(run / "stats.csv").dropna(subset=["heldAgeMs"])
    diffs = []
    for rows in sessions:
        w, _, added = simulate(rows, int(hold_ms * 1e6), block, rate)
        if not len(w):
            continue
        now = (st.wallS * 1e9).to_numpy()
        sel = (now >= w[0] + 10e9) & (now <= w[-1])
        i = np.searchsorted(w, now[sel], "right") - 1
        diffs.append(added[i] / 1e6 - st.heldAgeMs.to_numpy()[sel])
    return np.concatenate(diffs) if diffs else np.array([])


def s10_age_ms(dirs: list[Path]) -> float:
    from relay_impact import run_stats
    return float(np.median([run_stats(d)["ageP50Ms"] for d in dirs]))


def report(run: Path, age_ms: float, cands: list[float], target: float, margin_ms: float, session: str | None = None) -> dict:
    cfg = settings()
    block, rate = cfg["blockSize"], cfg["sampleRate"]
    a_ms = cfg["maxInfoAgeMs"] - age_ms
    up = pd.read_csv(run / "uploads.csv")
    if session:  # 한 실행 폴더에 녹화 재생기·S10 세션이 섞일 수 있다
        up = up[up.session.astype(str).str.startswith(session)]
    sessions = [g for _, g in up[up.code == 200].groupby("session") if g.samples.sum() > 0]
    rows = []
    for h in cands:
        f, dur = silent_fraction(sessions, h, a_ms, block, rate)
        rows.append({"holdMs": h, "silentPct": 100 * f, "seconds": dur})
    ok = [r["holdMs"] for r in rows if r["silentPct"] <= 100 * target]
    jit = []
    for g in sessions:
        d = (g.arrivalNs - g.sentNs).to_numpy()
        jit.append((d - d.min()) / 1e6)
    jit = np.concatenate(jit) if jit else np.array([np.nan])
    diff = check_against_stats(run, sessions, cfg["holdMs"], block, rate) if (run / "stats.csv").is_file() else np.array([])
    cl = pd.read_csv(run / "clients.csv") if (run / "clients.csv").is_file() else pd.DataFrame()
    pages = {}
    for c, g in (cl.groupby("client") if len(cl) else []):
        pages[c] = {"ua": str(g.ua.iloc[-1])[:40], "bufferedMsP50": float(g.bufferedMs.median()), "underruns": int(g.underruns.max() - g.underruns.min()),
                    "drops": int(g.drops.max() - g.drops.min()), "seconds": float(g.wallS.max() - g.wallS.min())}
    missing = (run / "missing.txt").read_text(encoding="utf-8").splitlines() if (run / "missing.txt").is_file() else []
    return {
        "settings": cfg, "s10AgeP50Ms": age_ms, "thresholdA_Ms": a_ms, "candidates": rows,
        "chosenHoldMs": (min(ok) + margin_ms) if ok else None,
        "uploaderProblem": not ok,  # 가장 큰 후보로도 목표를 못 맞춤 = 버린 묶음·긴 끊김 탓(묵힘으로 못 고침)
        "delayRiseMs": {q: float(np.percentile(jit, q)) for q in (50, 95, 99)},
        "batches": dropped(up),
        "statsCheckAbsDiffP95Ms": float(np.percentile(np.abs(diff), 95)) if len(diff) else None,
        "pages": pages, "missingTexts": missing,
    }


def selftest() -> None:
    """그림 2(M68 설계)의 계산 예: 0.2 s 묶음·20 ms 표본, 묶음 B가 620 ms 늦고 그동안 C·D·E를 버림 → 묵힘 600 ms에서 1215~1600 ms 무음."""
    block, rate, ms = 256, 48000, 1_000_000
    def batch(seq, first_ms, delay_ms):
        sent = (first_ms + 200) * ms
        return {"arrivalNs": sent + delay_ms * ms + 10_000 * ms, "session": "s", "seq": seq, "sentNs": sent,
                "firstTNs": first_ms * ms, "lastTNs": (first_ms + 180) * ms, "samples": 10, "code": 200}
    # 서버 시계 = S10 시계 + 10 s, 가장 빠른 지연 0. 앞뒤로 제때 온 묶음을 두어 범위를 넓힌다
    ok = [batch(i, -2000 + 200 * i, 0) for i in range(10)]  # −2000~0 ms
    late = [batch(10, 0, 0), batch(11, 200, 620), batch(15, 1000, 0)] + [batch(16 + i, 1200 + 200 * i, 0) for i in range(5)]
    up = pd.DataFrame(ok + late)
    w, p, added = simulate(up, 600 * ms, block, rate)
    a_ms = 235
    bad = (w[added > a_ms * ms] - 10_000 * ms) / ms  # S10 시각에 맞춘 서버 시각
    main = bad[bad > 1100]
    assert len(main) and 1205 <= main.min() <= 1225 and 1590 <= main.max() <= 1606, (main.min(), main.max())
    assert bad[bad <= 1100].size == 0 or 1010 <= bad[bad <= 1100].min() <= bad[bad <= 1100].max() <= 1026, bad[bad <= 1100]
    peak = added[(w >= 10_800 * ms) & (w < 11_020 * ms)].max() / ms
    assert 230 <= peak <= 246, peak  # B의 늦음은 240 ms 언저리에서 끝남(그림 2)
    # 버림 없이 지연만 300 ms 늘면 묵힘이 덮는다: 묵힘 600 ms는 무음 0, 200 ms는 무음이 생김
    up2 = pd.DataFrame([batch(i, 200 * i, 300 if i >= 5 else 0) for i in range(30)])
    f200, _ = silent_fraction([up2], 200, a_ms, block, rate)
    f600, _ = silent_fraction([up2], 600, a_ms, block, rate)
    assert f200 > 0 and f600 == 0, (f200, f600)
    d = dropped(pd.DataFrame(ok + late))
    assert d["s"]["missing"] == 3, d  # 12·13·14
    print("relay_hold selftest ok")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run", nargs="?", type=Path, help="서버 실행 폴더(uploads.csv)")
    ap.add_argument("--s10-run", type=Path, action="append", default=[], help="같은 때의 S10 실행 기록 폴더(정보 나이 중앙값)")
    ap.add_argument("--age-ms", type=float, help="S10 정보 나이 중앙값을 직접 줄 때")
    ap.add_argument("--target", type=float, default=0.01, help="중계 탓 무음 몫 목표(설계: 1%%, Claude 의견)")
    ap.add_argument("--margin-ms", type=float, default=100, help="다른 날을 위한 여유(설계: 100 ms, Claude 의견)")
    ap.add_argument("--from-ms", type=float, default=300)
    ap.add_argument("--to-ms", type=float, default=1000)
    ap.add_argument("--step-ms", type=float, default=50)
    ap.add_argument("--session", help="이 이름으로 시작하는 세션만")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        selftest()
        return
    if a.run is None or (a.age_ms is None and not a.s10_run):
        ap.error("run folder and --s10-run (or --age-ms) are required")
    age = a.age_ms if a.age_ms is not None else s10_age_ms(a.s10_run)
    cands = list(np.arange(a.from_ms, a.to_ms + 1e-9, a.step_ms))
    r = report(a.run, age, cands, a.target, a.margin_ms, a.session)
    print(f"A = {r['settings']['maxInfoAgeMs']} - S10 info age p50 {age:.1f} = {r['thresholdA_Ms']:.1f} ms")
    for c in r["candidates"]:
        print(f"  hold {c['holdMs']:6.0f} ms: relay silent {c['silentPct']:6.2f} % of {c['seconds']:.0f} s")
    print("chosen holdMs:", r["chosenHoldMs"], "(uploader problem: no hold meets the target)" if r["uploaderProblem"] else "")
    print("delay rise ms p50/p95/p99:", {k: round(v, 1) for k, v in r["delayRiseMs"].items()})
    print("batches per session:", r["batches"])
    print("stats.csv check |diff| p95 ms:", r["statsCheckAbsDiffP95Ms"])
    for c, g in r["pages"].items():
        print(f"  page {c}: {g}")
    print("missing texts:", len(r["missingTexts"]))


if __name__ == "__main__":
    main()
