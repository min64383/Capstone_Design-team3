"""M1 스파이크(F1~F7) 확인용: 녹화 세션 폴더 하나를 읽어 docs/FORMAT.md에 옮길 수치를 출력한다.

사용 (PowerShell):
    .venv\\Scripts\\python.exe tools/analysis/spike_check.py data/sessions/<세션ID>
    .venv\\Scripts\\python.exe tools/analysis/spike_check.py data/sessions/<세션ID> --roi 0.1   # 중앙 10% 영역 깊이

형식은 core/session/SessionFormat.kt(v0)와 같다. 이 스크립트는 판정하지 않고 측정값만 보여 준다.
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image


def quat_to_rot(q: np.ndarray) -> np.ndarray:
    """(qx, qy, qz, qw) 배열 → 회전행렬 배열 (N, 3, 3)."""
    x, y, z, w = q[:, 0], q[:, 1], q[:, 2], q[:, 3]
    r = np.empty((len(q), 3, 3))
    r[:, 0, 0] = 1 - 2 * (y * y + z * z)
    r[:, 0, 1] = 2 * (x * y - z * w)
    r[:, 0, 2] = 2 * (x * z + y * w)
    r[:, 1, 0] = 2 * (x * y + z * w)
    r[:, 1, 1] = 1 - 2 * (x * x + z * z)
    r[:, 1, 2] = 2 * (y * z - x * w)
    r[:, 2, 0] = 2 * (x * z - y * w)
    r[:, 2, 1] = 2 * (y * z + x * w)
    r[:, 2, 2] = 1 - 2 * (x * x + y * y)
    return r


def jpeg_size(path: Path) -> tuple[int, int]:
    """JPEG SOF 마커에서 (width, height)."""
    b = path.read_bytes()
    i = 2
    while i < len(b):
        if b[i] != 0xFF:
            i += 1
            continue
        marker = b[i + 1]
        length = struct.unpack(">H", b[i + 2 : i + 4])[0]
        if marker in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", b[i + 5 : i + 9])
            return w, h
        i += 2 + length
    raise ValueError(f"no SOF in {path}")


def load_u16(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path), dtype=np.uint16)


def section(title: str) -> None:
    print(f"\n## {title}")


def stats_ms(dt_ns: np.ndarray) -> str:
    if len(dt_ns) == 0:
        return "데이터 없음"
    ms = dt_ns / 1e6
    return (f"n={len(ms)} 중앙값 {np.median(ms):.1f} ms, 평균 {ms.mean():.1f} ms, "
            f"p5 {np.percentile(ms, 5):.1f}, p95 {np.percentile(ms, 95):.1f}, 최대 {ms.max():.1f} → 약 {1000 / np.median(ms):.1f} Hz")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔 기본 cp949에서 한글·기호 출력 오류 방지
    ap = argparse.ArgumentParser()
    ap.add_argument("session", type=Path)
    ap.add_argument("--roi", type=float, default=0.1, help="중앙 영역 비율(F4 평면 거리 확인용)")
    args = ap.parse_args()
    d: Path = args.session

    meta = json.loads((d / "meta.json").read_text(encoding="utf-8"))
    frames = pd.read_csv(d / "frames.csv")
    device = pd.read_csv(d / "device.csv")
    print(f"# {meta['sessionId']} ({meta['sceneId']}), 형식 {meta['formatVersion']}")
    print(f"기기 {meta['device']['model']} / Android {meta['device']['androidRelease']} / SoC {meta['device']['socModel']}")
    print(f"ARCore SDK {meta['arcore']['sdkVersion']}, APK {meta['arcore']['apkVersion']}")

    section("F1 깊이 지원")
    dep = meta["depth"]
    print(f"AUTOMATIC={dep['automaticSupported']}, RAW_DEPTH_ONLY={dep['rawDepthOnlySupported']}, 사용 모드={dep['modeUsed']}")

    section("F2 자세 규약")
    tr = frames[frames.tracking == "TRACKING"]
    print(f"추적 프레임 {len(tr)}/{len(frames)}, 실패 사유 분포: {frames.trackingFailure.value_counts().to_dict()}")
    if len(tr):
        r = quat_to_rot(tr[["qx", "qy", "qz", "qw"]].to_numpy())
        poses = [("getPose", r)]
        if "dqx" in tr.columns:  # 형식 v0에만 화면 기준 자세가 있다
            poses.append(("displayOriented", quat_to_rot(tr[["dqx", "dqy", "dqz", "dqw"]].to_numpy())))
        # GL 카메라 축(열)의 월드 방향: +X 오른쪽, +Y 위, -Z 시선
        for name, rot in poses:
            x, y, fwd = rot[:, :, 0].mean(0), rot[:, :, 1].mean(0), -rot[:, :, 2].mean(0)
            print(f"{name}: 평균 +X_cam(월드)={np.round(x, 2)}, +Y_cam={np.round(y, 2)}, 시선(-Z)={np.round(fwd, 2)}")
        if len(poses) == 2:
            rd = poses[1][1]
            rel = np.einsum("nji,njk->nik", r, rd)  # R_pose^T · R_display
            ang = np.degrees(np.arctan2(rel[:, 1, 0], rel[:, 0, 0]))
            print(f"두 자세의 카메라 Z축 회전 차이: 중앙값 {np.median(ang):.1f}°, 범위 {ang.min():.1f}~{ang.max():.1f}°")
            print(f"  두 자세의 위치 차이 최대 {np.abs(tr[['tx','ty','tz']].to_numpy() - tr[['dtx','dty','dtz']].to_numpy()).max():.4f} m")
        pos = tr[["tx", "ty", "tz"]].to_numpy()
        print(f"이동: 시작→끝 {np.round(pos[-1] - pos[0], 2)} m, 수평 경로 길이 {np.linalg.norm(np.diff(pos[:, [0, 2]], axis=0), axis=1).sum():.2f} m, "
              f"높이(Y) 범위 {pos[:, 1].min():.2f}~{pos[:, 1].max():.2f} m")

    section("F3 CPU 이미지·내부 파라미터")
    cam = meta["camera"]
    ki, kt = cam["imageIntrinsics"], cam["textureIntrinsics"]
    print(f"CPU 이미지 K: {ki}")
    print(f"GPU 텍스처 K: {kt}")
    print(f"화면 회전 {cam['displayRotationDeg']}°, fps {cam['fpsMin']}~{cam['fpsMax']}")
    rgb = frames.rgbFile.dropna()
    if len(rgb):
        w, h = jpeg_size(d / rgb.iloc[0])
        print(f"저장된 RGB {w}x{h} ({'가로' if w > h else '세로'}) — K 크기와 {'일치' if (w, h) == (ki['width'], ki['height']) else '불일치'}")

    section("F4 깊이 해상도·내부 파라미터 환산")
    dfiles = frames.depthFile.dropna()
    if len(dfiles):
        dm = load_u16(d / dfiles.iloc[len(dfiles) // 2])
        h, w = dm.shape
        sx, sy = w / ki["width"], h / ki["height"]
        print(f"깊이 {w}x{h}, 가로세로비 {w / h:.4f} (CPU 이미지 {ki['width'] / ki['height']:.4f})")
        print(f"후보 A (CPU K를 크기 비율로): fx={ki['fx'] * sx:.2f} fy={ki['fy'] * sy:.2f} cx={ki['cx'] * sx:.2f} cy={ki['cy'] * sy:.2f}"
              f" (sx={sx:.4f}, sy={sy:.4f}{', 비등방 → 부적합 가능' if abs(sx - sy) > 1e-3 else ''})")
        tx, ty = w / kt["width"], h / kt["height"]
        print(f"후보 B (텍스처 K를 크기 비율로): fx={kt['fx'] * tx:.2f} fy={kt['fy'] * ty:.2f} cx={kt['cx'] * tx:.2f} cy={kt['cy'] * ty:.2f}"
              f" (sx={tx:.4f}, sy={ty:.4f}{', 비등방 → 부적합 가능' if abs(tx - ty) > 1e-3 else ''})")
        print(f"meta depth 크기: {dep['width']}x{dep['height']}, meta depth K(v1): {dep.get('intrinsics')}")
        # 중앙 영역: 알려진 거리 평면을 찍은 세션에서 기대 거리와 비교
        rh, rw = max(1, int(h * args.roi)), max(1, int(w * args.roi))
        center = []
        for f in dfiles:
            a = load_u16(d / f)
            roi = a[h // 2 - rh // 2 : h // 2 + rh // 2 + 1, w // 2 - rw // 2 : w // 2 + rw // 2 + 1]
            v = roi[roi > 0]
            if len(v):
                center.append(np.median(v))
        if center:
            c = np.array(center)
            print(f"중앙 {args.roi:.0%} 영역 깊이 중앙값: 전체 {np.median(c):.0f} mm (파일별 p5 {np.percentile(c, 5):.0f}, p95 {np.percentile(c, 95):.0f})")

    section("F5 타임스탬프와 갱신 빈도")
    clk = meta.get("elapsedMinusMonotonicNs")
    if clk is not None:
        print(f"녹화 시작 시 elapsedRealtime − monotonic = {clk / 1e6:.1f} ms "
              "(0에 가까우면 두 시계를 구분할 수 없음. 기기가 한 번 잠든 뒤 녹화해야 판별 가능)")
    t = frames.tNs.to_numpy()
    print(f"ARCore 프레임 간격: {stats_ms(np.diff(t))}")
    off = (frames.sysElapsedNs - frames.tNs).to_numpy() / 1e6
    print(f"sysElapsedNs - tNs: 중앙값 {np.median(off):.1f} ms, 범위 {off.min():.1f}~{off.max():.1f} ms "
          "(수 ms 수준이면 같은 시간 기준 = elapsedRealtime 계열)")
    for col, name in (("depthTNs", "일반 깊이"), ("rawDepthTNs", "원시 깊이")):
        ts = frames[col].dropna().astype(np.int64).to_numpy()
        uniq = np.unique(ts)
        print(f"{name}: 프레임 중 제공 {len(ts)}/{len(frames)}, 서로 다른 시각 {len(uniq)}개, 갱신 간격 {stats_ms(np.diff(uniq))}")
        if len(ts):
            lag = (frames.loc[frames[col].notna(), "tNs"].to_numpy() - ts) / 1e6
            print(f"  프레임 시각 - {name} 시각: 중앙값 {np.median(lag):.1f} ms, p95 {np.percentile(lag, 95):.1f} ms")

    section("F6 일반 깊이 vs 원시 깊이")
    both = frames.dropna(subset=["depthFile", "rawDepthFile"])
    if len(both):
        diffs, valid_d, valid_r = [], [], []
        for _, row in both.iterrows():
            a = load_u16(d / row.depthFile).astype(np.int32)
            b = load_u16(d / row.rawDepthFile).astype(np.int32)
            if a.shape != b.shape:
                print(f"크기 다름: {a.shape} vs {b.shape}")
                break
            valid_d.append((a > 0).mean())
            valid_r.append((b > 0).mean())
            m = (a > 0) & (b > 0)
            if m.any():
                diffs.append(np.abs(a[m] - b[m]))
        print(f"비교 프레임 {len(both)}: 유효 픽셀 비율 일반 {np.mean(valid_d):.1%}, 원시 {np.mean(valid_r):.1%}")
        if diffs:
            dd = np.concatenate(diffs)
            print(f"둘 다 유효한 픽셀 |차이|: 중앙값 {np.median(dd):.0f} mm, p95 {np.percentile(dd, 95):.0f} mm")
    else:
        print("같은 프레임에 두 종류가 모두 저장된 행 없음")
    cf = frames.confFile.dropna()
    if len(cf):
        c = np.concatenate([np.asarray(Image.open(d / f), dtype=np.uint8).ravel() for f in cf[:: max(1, len(cf) // 50)]])
        print(f"신뢰도 분포: 0 {np.mean(c == 0):.1%}, <128 {np.mean(c < 128):.1%}, =255 {np.mean(c == 255):.1%}, 중앙값 {np.median(c):.0f}")

    section("F7 저장 처리량·발열")
    st = meta.get("stats")
    if st:
        dur = st["durationS"]
        print(f"길이 {dur:.1f} s, 프레임 {st['nFrames']} ({st['nFrames'] / dur:.1f} fps)")
        print(f"저장: 깊이 {st['nDepthSaved']} ({st['nDepthSaved'] / dur:.1f}/s), 원시 {st['nRawDepthSaved']} ({st['nRawDepthSaved'] / dur:.1f}/s), "
              f"RGB {st['nRgbSaved']} ({st['nRgbSaved'] / dur:.1f}/s)")
        print(f"건너뜀 {st['nFileJobsSkipped']}, 쓰기 오류 {st['nWriteErrors']}")
    else:
        print("meta.json에 stats 없음(녹화가 정상 종료되지 않음)")
    size = sum(f.stat().st_size for f in d.rglob("*") if f.is_file())
    mp4 = d / "arcore.mp4"
    print(f"폴더 크기 {size / 1e6:.1f} MB, arcore.mp4 {mp4.stat().st_size / 1e6 if mp4.exists() else 0:.1f} MB")
    if len(device):
        dev_t = (device.tNs - device.tNs.iloc[0]) / 1e9
        print(f"발열 상태 변화: {[(round(float(t), 0), int(s)) for t, s in zip(dev_t, device.thermalStatus) if not pd.isna(s)][:: max(1, len(device) // 10)]}")
        print(f"배터리 {device.batteryPct.iloc[0]}% → {device.batteryPct.iloc[-1]}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
