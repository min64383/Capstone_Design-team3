"""비교 실험 (M10): 변형 목록 × 세션마다 오프라인 재생(`:core:replay`) → metrics.json.

    python sweep.py <변형 목록 json> <출력 폴더> <세션 폴더> ...
    python report.py --group <출력 폴더>/*/*        → 장면 × 변형 비교표

변형 목록 형식(예: sweeps/m10.json): {"variants": [{"name": "default", "overrides": {}}, ...]}.
`overrides`는 앱 설정(default.json)에 덮어쓸 부분이다. 출력은 <출력 폴더>/<변형>/<세션ID>/.
이미 metrics.json이 있는 조합은 건너뛴다(중단 후 이어 돌리기). 다시 돌리려면 그 폴더를 지운다.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

from align import REPO
from metrics import compute

GRADLEW = REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew")


def plan(variants: list[dict], sessions: list[Path], out_root: Path) -> list[tuple[dict, Path, Path]]:
    """(변형, 세션, 출력 폴더) 목록. 변형 이름은 폴더 이름이 되므로 겹치면 안 된다."""
    names = [v["name"] for v in variants]
    if len(set(names)) != len(names):
        raise ValueError(f"duplicate variant names: {names}")
    return [(v, s, out_root / v["name"] / s.name) for v in variants for s in sessions]


def run_one(variant: dict, session: Path, out: Path) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    ov = out / "overrides.json"
    ov.write_text(json.dumps(variant["overrides"]), encoding="utf-8")
    subprocess.run([str(GRADLEW), ":core:replay", f"-Psession={session.resolve()}", f"-Pout={out.resolve()}",
                    f"-PoverridesFile={ov.resolve()}", "-q"], cwd=REPO, check=True)
    m = compute(session, out)
    m["variant"] = variant["name"]
    (out / "metrics.json").write_text(json.dumps(m, indent=2, ensure_ascii=False), encoding="utf-8")
    return m


def main(args: list[str]) -> None:
    if len(args) < 3:
        sys.exit(__doc__)
    variants = json.loads(Path(args[0]).read_text(encoding="utf-8"))["variants"]
    jobs = plan(variants, [Path(a) for a in args[2:]], Path(args[1]))
    for k, (v, s, out) in enumerate(jobs, 1):
        if (out / "metrics.json").is_file():
            continue
        print(f"[{k}/{len(jobs)}] {v['name']} / {s.name}", flush=True)
        run_one(v, s, out)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    main(sys.argv[1:])
