"""두 재생 변형의 출력이 바이트 단위로 같은지(M53 회귀: 새 키를 끈 b0 대 M59 조합 a4, 실내 a1 대 b0).

    python same_bytes.py <폴더 A(in·t·o01 위)> <변형 A> <폴더 B> <변형 B> [묶음...]

replay_info.json(커밋·설정 기록)과 stage_timing.csv(처리 시간)는 뺀다. 한쪽에만 있는 파일은 다름으로 센다.
"""
import sys
from pathlib import Path

SKIP = {"replay_info.json", "stage_timing.csv", "replay_done"}


def main(a_root: str, a_var: str, b_root: str, b_var: str, groups: list[str]) -> None:
    same = diff = 0
    bad = []
    for g in groups:
        for sa in sorted((Path(a_root) / g / a_var).iterdir()):
            sb = Path(b_root) / g / b_var / sa.name
            names = {p.name for p in sa.iterdir()} | ({p.name for p in sb.iterdir()} if sb.is_dir() else set())
            for n in sorted(names - SKIP):
                fa, fb = sa / n, sb / n
                if fa.is_file() and fb.is_file() and fa.read_bytes() == fb.read_bytes():
                    same += 1
                else:
                    diff += 1
                    bad.append(f"{g}/{sa.name}/{n}")
    print(f"같음 {same}, 다름 {diff}")
    for b in bad[:30]:
        print("  다름", b)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:] or ["in", "t", "o01"])
