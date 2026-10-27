"""사용자 수준 실패 진단(C1 → M41): 변형마다 걸은 10 m당 오경보 시작(옆 벽·바닥 높이·그 밖으로 나눔), 깜빡임, 거리 오차, STOP 누락과
세션마다 STOP 구간에 들어갔는지(STOP 시험 범위)를 한 표로 낸다. 정답 대입 변형(깊이 × 진행 방향, IMPROVE_SPEC §9.2: B·BH·D·DH 등)을
나란히 놓아 어느 단계가 사용자에게 보이는 실패를 만드는지 가른다(C1 지도 변경의 근거 표와 같은 계산).

    python diagnose.py <출력 접두어> <이름>=<변형 재생 폴더> ... --sessions <세션 폴더> ...

변형 재생 폴더 아래 `<세션ID>/`가 재생 출력이다(`:core:replay -Pout`, sweep.py와 같은 배치). 재생이 없는 (변형, 세션)은 stderr로 알리고
빼며(`missing`), 끝나지 않은 재생은 metrics.py가 멈춘다. 출력: `<접두어>_sessions.csv`(세션 × 변형), `<접두어>_variants.csv`(변형별)와 화면 표.

정의(모두 정답 좌표, 정렬은 metrics.py와 같다. 정답 대입 재생은 기준 정렬을 같이 쓴다):
- 오경보 시작: metrics.distance_guidance의 falseAlarmOnsets와 그 자리 나눔(falseAlarmOnsetsBy: 옆 벽 = 실측 옆 벽 면 SIDE_WALL_TOL_M 안,
  바닥 = 대표점 높이 FLOOR_LEVEL_M 이하, 그 밖). 변형 값은 세션 합 ÷ 걸은 거리 합 × 10.
- 깜빡임: 느린 경로 단계마다 통로 안 장애물 수가 바뀐 횟수, 대표점 높이(정렬 바닥 위) FLOOR_LEVEL_M 이하(low)·위(high). 변형 값은 분당.
- 거리 오차: distance_guidance distanceErrorM p50(세션). 변형 값은 세션 중앙값.
- STOP 누락: metrics missedStop(합). STOP 시험 범위: 머리 앞(진행 방향 거리 0 이상) 정답이 STOP 구간(policy.stopM 안)에 든 시간
  (metrics stopZoneAheadS, 정답마다)이 성적표 `zoneMinS`(scorecard.json, 명세 탐지율의 "1 s 이상") 이상인 정답이 있으면 stopEntered.
  들지 않은 세션의 "누락 0"은 시험되지 않은 것이라 변형 값에 `stopSessions`(든 세션 수 / 세션 수)를 같이 낸다. 참고로 metrics의
  stopZoneS(합)·stopNeeded는 통로 뒤 여유 안 머리 옆·뒤의 정답(옆 벽 등)도 센다.
- 진행 방향 오차: metrics headingErrorDeg p50(걷는 블록, 정답 보행 방향 기준). 진행 방향 대입 변형은 0에 가깝다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from align import load_config
from metrics import FLOOR_LEVEL_M, compute

ZONE_MIN_S = json.loads((Path(__file__).parent / "scorecard.json").read_text(encoding="utf-8"))["zoneMinS"]  # 구간에 든 것으로 볼 최소 시간


def flicker(run_dir: Path, strategy: str, floor_y: float) -> dict:
    """느린 경로 단계마다 통로 안 장애물 수가 바뀐 횟수(대표점 높이 FLOOR_LEVEL_M 이하 low·위 high). 장애물이 없는 단계는 0개로 센다."""
    obs = pd.read_csv(run_dir / "obstacles.csv")
    steps = pd.read_csv(run_dir / "slow_path.csv").tCaptureNs
    ob = obs[obs.inCorridor.astype(str).str.lower() == "true"]
    h = ob[f"rep{strategy}_y"] - floor_y
    out = {}
    for k, m in (("low", h <= FLOOR_LEVEL_M), ("high", h > FLOOR_LEVEL_M)):
        cnt = ob[m].groupby("tCaptureNs").size().reindex(steps, fill_value=0)
        out[k] = int((cnt.diff().fillna(0) != 0).sum())
    return out


def run_row(session: Path, run_dir: Path) -> dict:
    """(세션, 재생) 하나의 진단 값. 정답(`annotations/obstacles.json`)이 있어야 한다."""
    m = compute(session, run_dir)
    if m.get("truth") is None:
        raise ValueError(f"{session}: 정답 파일이 없다")
    cfg = load_config(run_dir)
    al = json.loads((run_dir / "align.json").read_text(encoding="utf-8"))  # compute가 쓴 정렬
    dg = m["distanceGuidance"]
    by = dg["falseAlarmOnsetsBy"]
    sf = m.get("stateFraction") or {}
    fl = flicker(run_dir, cfg["repPoint"]["strategy"], al["floorY"])
    ahead = m.get("stopZoneAheadS", {})
    num =lambda v: np.nan if v is None else float(v)  # noqa: E731  값 없음은 0이 아니라 NaN
    return {
        "scene": m["scene"], "walkedM": dg["walkedM"], "minutes": dg["durationMin"],
        "fa": dg["falseAlarmOnsets"], "faSideWall": by["sideWall"], "faFloor": by["floor"], "faOther": by["other"],
        "distErrP50M": num(dg["distanceErrorM"]["p50"]), "distErrN": dg["distanceErrorM"]["n"],
        "missedStop": m["missedStop"], "stopNeeded": m["stopNeeded"], "stopZoneS": round(float(sum(m.get("stopZoneS", {}).values())), 3),
        "stopAheadS": round(max(ahead.values(), default=0.0), 3), "stopEntered": any(s >= ZONE_MIN_S for s in ahead.values()),
        "unknown": float(sf.get("UNKNOWN", 0.0)), "degraded": float(sf.get("DEGRADED", 0.0)),
        "flickLow": fl["low"], "flickHigh": fl["high"],
        "headingErrP50Deg": num((m.get("headingErrorDeg") or {}).get("p50")),
    }


def summarize(df: pd.DataFrame) -> pd.DataFrame:
    """세션 표 → 변형 표(입력 순서 유지). 10 m당·분당 값은 합끼리 나눈다(짧은 세션이 비율을 흔들지 않게)."""
    g = df.groupby("variant", sort=False)
    walked, minutes = g.walkedM.sum(), g.minutes.sum()
    out = pd.DataFrame({
        "sessions": g.size(), "walkedM": walked.round(2),
        **{f"{c}Per10m": (g[c].sum() / walked * 10).round(2) for c in ("fa", "faSideWall", "faFloor", "faOther")},
        "distErrP50M": g.distErrP50M.median().round(3),
        "missedStop": g.missedStop.sum(), "stopSessions": g.stopEntered.sum().astype(int).astype(str) + "/" + g.size().astype(str),
        "unknown": g.unknown.mean().round(3), "degraded": g.degraded.mean().round(3),
        "flickLowPerMin": (g.flickLow.sum() / minutes).round(1), "flickHighPerMin": (g.flickHigh.sum() / minutes).round(1),
        "headingErrP50Deg": g.headingErrP50Deg.median().round(2),
    })
    return out


def diagnose(variants: list[tuple[str, Path]], sessions: list[Path]) -> tuple[pd.DataFrame, list[str]]:
    """(세션 표, 재생이 없어 뺀 '변형/세션' 목록)."""
    rows, missing = [], []
    for name, vdir in variants:
        for s in sessions:
            run = vdir / s.name
            if not (run / "guidance.csv").is_file():
                missing.append(f"{name}/{s.name}")
                continue
            rows.append({"variant": name, "session": s.name, **run_row(s, run)})
    if missing:
        print(f"주의: 재생이 없어 뺀 {len(missing)}개: {', '.join(missing)}", file=sys.stderr)
    return pd.DataFrame(rows), missing


def main(args: list[str]) -> None:
    if "--sessions" not in args or args.index("--sessions") < 2:
        sys.exit(__doc__)
    k = args.index("--sessions")
    prefix, pairs, sessions = Path(args[0]), args[1:k], [Path(a) for a in args[k + 1:]]
    variants = [(p.split("=", 1)[0], Path(p.split("=", 1)[1])) for p in pairs]
    df, _ = diagnose(variants, sessions)
    if df.empty:
        sys.exit("진단할 재생이 없다")
    per = summarize(df)
    df.to_csv(f"{prefix}_sessions.csv", index=False)
    per.to_csv(f"{prefix}_variants.csv")
    pd.set_option("display.width", 250)
    print(per.to_string())
    print()
    print(df.drop(columns=["minutes"]).round(3).to_string(index=False))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    sys.stderr.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
