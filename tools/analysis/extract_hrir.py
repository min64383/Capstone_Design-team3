"""SOFA HRTF → 앱용 수평면 HRIR 바이너리 (MVP_SPEC §7.6, 부록 C).

사용 (PowerShell, 저장소 루트):
    .venv\\Scripts\\python.exe tools/analysis/extract_hrir.py data/hrtf/D1_HRIR_SOFA/D1_48K_24bit_256tap_FIR_SOFA.sofa app/src/main/assets/hrtf/sadie2_d1_48k.hrir

고도 0°(±0.01°)의 측정점만 뽑는다. SOFA 방위각(반시계, 왼쪽 +)을 명세 규약(오른쪽 +, −180~180)으로 바꿔 오름차순 저장한다.

바이너리 형식 (리틀 엔디언, core/audio/Hrtf.kt가 읽음):
    char[4] magic = "WAHR"
    int32   version = 1
    int32   sampleRate
    int32   taps
    int32   count
    count × { float32 azimuthDeg, float32 left[taps], float32 right[taps] }
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

import h5py
import numpy as np

MAGIC = b"WAHR"
VERSION = 1


def extract(sofa: Path) -> tuple[int, np.ndarray, np.ndarray, np.ndarray, str]:
    with h5py.File(sofa, "r") as f:
        conv = f.attrs["SOFAConventions"].decode()
        if conv != "SimpleFreeFieldHRIR":
            raise ValueError(f"unsupported convention {conv}")
        rp = f["ReceiverPosition"][:].reshape(-1, 3)
        # 수신기 0이 왼쪽 귀(y > 0)인지 확인
        if not (rp[0, 1] > 0 > rp[1, 1]):
            raise ValueError(f"unexpected receiver order {rp}")
        if np.any(f["Data.Delay"][:] != 0):
            raise ValueError("non-zero Data.Delay not supported")
        sr = int(f["Data.SamplingRate"][0])
        sp = f["SourcePosition"][:]
        ir = f["Data.IR"][:]
        lic = f.attrs.get("License", b"").decode()
    h = np.abs(sp[:, 1]) < 0.01
    az_ours = -sp[h, 0]  # 왼쪽 + → 오른쪽 +
    az_ours = (az_ours + 180.0) % 360.0 - 180.0
    order = np.argsort(az_ours, kind="stable")
    az = az_ours[order].astype(np.float32)
    left = ir[h][order, 0, :].astype(np.float32)
    right = ir[h][order, 1, :].astype(np.float32)
    # 같은 방위각 중복 제거(첫 번째 유지)
    keep = np.concatenate([[True], np.diff(az) > 1e-3])
    return sr, az[keep], left[keep], right[keep], lic


def write(out: Path, sr: int, az: np.ndarray, left: np.ndarray, right: np.ndarray) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    taps = left.shape[1]
    with open(out, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<iiii", VERSION, sr, taps, len(az)))
        for a, l, r in zip(az, left, right):
            f.write(struct.pack("<f", float(a)))
            f.write(l.astype("<f4").tobytes())
            f.write(r.astype("<f4").tobytes())


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("sofa", type=Path)
    ap.add_argument("out", type=Path)
    args = ap.parse_args()
    sr, az, left, right, lic = extract(args.sofa)
    write(args.out, sr, az, left, right)
    gaps = np.diff(np.concatenate([az, [az[0] + 360]]))
    el = lambda a: float(np.sum(left[np.argmin(np.abs(az - a))] ** 2))  # noqa: E731
    er = lambda a: float(np.sum(right[np.argmin(np.abs(az - a))] ** 2))  # noqa: E731
    print(f"{args.out}: {len(az)} azimuths, {left.shape[1]} taps @ {sr} Hz, max gap {gaps.max():.2f}°, {args.out.stat().st_size / 1024:.0f} KB")
    print(f"check: -90° L/R energy {el(-90):.3f}/{er(-90):.3f} (left louder), +90° {el(90):.3f}/{er(90):.3f} (right louder)")
    print(f"source license: {lic[:80]}...")
    # 자기 확인: 규약이 뒤집히지 않았는지
    assert el(-90) > er(-90) and er(90) > el(90), "azimuth convention flipped"
    return 0


if __name__ == "__main__":
    sys.exit(main())
