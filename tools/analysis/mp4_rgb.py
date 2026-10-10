"""ARCore 녹화 MP4에서 매 프레임 밝기 영상 꺼내기 (M19, IMPROVE_SPEC §6.1.1 M19).

    python mp4_rgb.py <세션 폴더> ...        [--force]  → <세션>/rgb_mp4/<프레임번호>.jpg, index.csv, extract.json
    python mp4_rgb.py --selftest

앱은 RGB를 `record.rgbEveryN`장마다 JPEG로 저장하지만, ARCore 녹화(`arcore.mp4`)에는 카메라 영상(640×480 H.264)이 프레임마다 있다.
영상 트랙 바로 뒤의 메타데이터 트랙이 장마다 protobuf 하나를 갖고, 그 필드 2.5가 frames.csv의 카메라 시각 `tNs`와 ±1 ms로 맞는다
(M19 설계 표 2, 정답 세션 21개 실측). 디코딩 순서의 k번째 장 = 메타데이터 k번째 샘플(장 수가 같고 pts가 단조 증가할 때만).

밝기는 디코더의 범위 변환 없이 Y 평면을 그대로 쓴다: 앱이 녹화한 JPEG(`YuvImage` NV21 → JPEG)의 밝기와 같은 척도라 RGB 보정의
밝기 σ(`frontend.rgbGuideLumaSigma`)를 그대로 쓸 수 있다. 회색조 JPEG로 저장하면 core `RgbFrames`(ImageIO)가 R=G=B=Y로 읽는다.
원본(frames.csv, rgb/)은 건드리지 않는다. 짝이 없는 장(MP4 시작 전 처음 몇 장, 2 ms 넘게 벗어난 장, 한 행에 두 장)은 쓰지 않는다.
"""
from __future__ import annotations

import bisect
import csv
import io
import json
import struct
import sys
from pathlib import Path

import numpy as np

OUT_DIR = "rgb_mp4"
MAX_DT_NS = 2_000_000  # 프레임 간격 33 ms, 실측 짝 차이 ±1 ms
JPEG_QUALITY = 95
VIDEO_SIZE = (640, 480)


def _boxes(b: bytes, o: int, e: int):
    while o + 8 <= e:
        size, kind = struct.unpack(">I4s", b[o:o + 8])
        head = 8
        if size == 1:
            size = struct.unpack(">Q", b[o + 8:o + 16])[0]
            head = 16
        elif size == 0:
            size = e - o
        yield kind.decode("latin1"), o + head, o + size
        o += size


def _find(b: bytes, o: int, e: int, path: list[str]):
    for kind, s, en in _boxes(b, o, e):
        if kind == path[0]:
            if len(path) == 1:
                yield s, en
            else:
                yield from _find(b, s, en, path[1:])


def _first(b, o, e, path):
    return next(_find(b, o, e, path), None)


def tracks(data: bytes) -> list[dict]:
    """moov의 트랙 순서대로 {handler, width, height, samples: [bytes]}. 샘플 바이트는 stsz·stsc·stco(co64)로 자른다."""
    out = []
    moov = _first(data, 0, len(data), ["moov"])
    for kind, ts, te in _boxes(data, *moov):
        if kind != "trak":
            continue
        tk = _first(data, ts, te, ["tkhd"])
        w, h = struct.unpack(">II", data[tk[1] - 8:tk[1]])
        hd = _first(data, ts, te, ["mdia", "hdlr"])
        stbl = ["mdia", "minf", "stbl"]
        a, _ = _first(data, ts, te, stbl + ["stsz"])
        fixed, n = struct.unpack(">II", data[a + 4:a + 12])
        sizes = [fixed] * n if fixed else list(struct.unpack(f">{n}I", data[a + 12:a + 12 + 4 * n]))
        co = _first(data, ts, te, stbl + ["stco"])
        if co:
            m = struct.unpack(">I", data[co[0] + 4:co[0] + 8])[0]
            offs = struct.unpack(f">{m}I", data[co[0] + 8:co[0] + 8 + 4 * m])
        else:
            co = _first(data, ts, te, stbl + ["co64"])
            m = struct.unpack(">I", data[co[0] + 4:co[0] + 8])[0]
            offs = struct.unpack(f">{m}Q", data[co[0] + 8:co[0] + 8 + 8 * m])
        a, _ = _first(data, ts, te, stbl + ["stsc"])
        m = struct.unpack(">I", data[a + 4:a + 8])[0]
        stsc = [struct.unpack(">III", data[a + 8 + 12 * i:a + 20 + 12 * i]) for i in range(m)]
        samples, si = [], 0
        for ci, off in enumerate(offs):
            per = [r for r in stsc if r[0] <= ci + 1][-1][1]
            for _ in range(per):
                if si >= n:
                    break
                samples.append(data[off:off + sizes[si]])
                off += sizes[si]
                si += 1
        out.append({"handler": data[hd[0] + 8:hd[0] + 12].decode("latin1"), "width": w >> 16, "height": h >> 16, "samples": samples})
    return out


def protobuf(b: bytes) -> dict[int, list]:
    """protobuf 한 메시지 → {필드: [값]}. 길이 구분 필드는 하위 메시지로 읽어 보고, 안 되면 바이트로 둔다."""
    i, out = 0, {}

    def varint():
        nonlocal i
        v = shift = 0
        while True:
            c = b[i]
            i += 1
            v |= (c & 0x7F) << shift
            shift += 7
            if c < 0x80:
                return v

    try:
        while i < len(b):
            key = varint()
            field, wire = key >> 3, key & 7
            if wire == 0:
                out.setdefault(field, []).append(varint())
            elif wire == 2:
                n = varint()
                sub = b[i:i + n]
                i += n
                try:
                    out.setdefault(field, []).append(protobuf(sub) if sub else {})
                except Exception:
                    out.setdefault(field, []).append(sub)
            elif wire == 5:
                i += 4
            elif wire == 1:
                i += 8
            else:
                break
    except IndexError:
        pass
    return out


def frame_time_ns(sample: bytes) -> int | None:
    """메타데이터 샘플의 카메라 시각(ns): 필드 2(하위 메시지)의 필드 5.
    VERIFY: ARCore MP4 메타데이터 형식은 문서화되지 않았다. 정답 세션 21개에서 frames.csv tNs와 ±1 ms로 맞는 것만 확인했다."""
    m = protobuf(sample).get(2, [None])[0]
    if not isinstance(m, dict) or 5 not in m:
        return None
    return m[5][0]


def match(mp4_ns: list[int | None], frame_ns: list[int], max_dt: int = MAX_DT_NS) -> dict[int, tuple[int, int]]:
    """MP4 장마다 가장 가까운 frames.csv 행(정렬된 [frame_ns]의 위치) → {행 위치: (장 번호, 차이 ns)}.
    차이가 [max_dt]를 넘으면 버리고, 한 행에 두 장이 붙으면 그 행은 버린다(어느 장인지 모름)."""
    hits: dict[int, list[tuple[int, int]]] = {}
    for k, t in enumerate(mp4_ns):
        if t is None:
            continue
        i = bisect.bisect_left(frame_ns, t)
        cand = [j for j in (i - 1, i) if 0 <= j < len(frame_ns)]
        if not cand:
            continue
        j = min(cand, key=lambda c: abs(frame_ns[c] - t))
        if abs(frame_ns[j] - t) <= max_dt:
            hits.setdefault(j, []).append((k, t - frame_ns[j]))
    return {j: v[0] for j, v in hits.items() if len(v) == 1}


def y_plane(frame) -> np.ndarray:
    """PyAV 장의 Y 평면(범위 변환 없음)."""
    if frame.format.name not in ("yuv420p", "yuvj420p", "nv12"):
        raise ValueError(f"예상하지 못한 화소 형식 {frame.format.name}")
    p = frame.planes[0]
    return np.frombuffer(p, np.uint8).reshape(frame.height, p.line_size)[:, :frame.width]


def extract(session: Path, force: bool = False) -> dict:
    import av  # 분석 도구 전용 의존성(docs/LICENSES.md)
    from PIL import Image

    out = session / OUT_DIR
    if (out / "extract.json").is_file() and not force:
        return json.loads((out / "extract.json").read_text(encoding="utf-8"))
    mp4 = session / "arcore.mp4"
    data = mp4.read_bytes()
    tr = tracks(data)
    vi = next(i for i, t in enumerate(tr) if t["handler"] == "vide" and (t["width"], t["height"]) == VIDEO_SIZE)
    meta = tr[vi + 1]
    if meta["handler"] != "meta" or len(meta["samples"]) != len(tr[vi]["samples"]):
        raise ValueError(f"{session.name}: 영상 트랙 뒤에 같은 장 수의 메타데이터 트랙이 없다")
    mp4_ns = [frame_time_ns(s) for s in meta["samples"]]

    with open(session / "frames.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    rows.sort(key=lambda r: int(r["tNs"]))
    frame_ns = [int(r["tNs"]) for r in rows]
    pairs = match(mp4_ns, frame_ns)
    by_sample = {k: (j, dt) for j, (k, dt) in pairs.items()}

    out.mkdir(exist_ok=True)
    index, diffs = [], []
    with av.open(str(mp4)) as c:
        stream = c.streams[vi]  # moov 트랙 순서 = 스트림 순서
        last_pts = None
        n_dec = 0
        for k, fr in enumerate(c.decode(stream)):
            n_dec += 1
            if last_pts is not None and fr.pts <= last_pts:
                raise ValueError(f"{session.name}: 표시 순서가 샘플 순서와 다르다(pts {last_pts} → {fr.pts})")
            last_pts = fr.pts
            if k not in by_sample:
                continue
            j, dt = by_sample[k]
            row = rows[j]
            y = y_plane(fr)
            name = f"{int(row['frameIndex']):06d}.jpg"
            buf = io.BytesIO()
            Image.fromarray(y, "L").save(buf, "JPEG", quality=JPEG_QUALITY)
            (out / name).write_bytes(buf.getvalue())
            index.append((int(row["frameIndex"]), int(row["tNs"]), k, round(dt / 1e6, 3)))
            if row.get("rgbFile") and (session / row["rgbFile"]).is_file():  # 녹화 JPEG와 밝기 비교
                rec = np.asarray(Image.open(session / row["rgbFile"]).convert("L"), dtype=np.int16)
                if rec.shape == y.shape:
                    diffs.append(float(np.median(np.abs(rec - y.astype(np.int16)))))
    if n_dec != len(mp4_ns):
        raise ValueError(f"{session.name}: 디코딩 {n_dec}장 ≠ 메타데이터 {len(mp4_ns)}개")

    with open(out / "index.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["frameIndex", "tNs", "mp4Sample", "dtMs"])
        w.writerows(sorted(index))
    paired = {i[0] for i in index}
    depth_rows = [r for r in rows if r.get("depthFile")]
    unpaired = [int(r["frameIndex"]) for r in depth_rows if int(r["frameIndex"]) not in paired]
    first_mp4 = min((t for t in mp4_ns if t is not None), default=None)
    info = {
        "session": session.name, "mp4Frames": len(mp4_ns), "frames": len(rows), "written": len(index),
        "depthFrames": len(depth_rows), "depthUnpaired": len(unpaired),
        "depthUnpairedAfterMp4Start": sum(1 for r in depth_rows if int(r["frameIndex"]) in unpaired and int(r["tNs"]) >= first_mp4 - MAX_DT_NS),
        "maxAbsDtMs": max((abs(i[3]) for i in index), default=None),
        "lumaDiffVsRecordedP50": float(np.median(diffs)) if diffs else None, "nCompared": len(diffs),
    }
    (out / "extract.json").write_text(json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8")
    return info


def selftest() -> None:
    # protobuf: 필드 2(하위 메시지) 안 필드 5 = 300
    sub = bytes([0x08, 0x01, 0x28]) + bytes([0xAC, 0x02])
    msg = bytes([0x0A, 0x00, 0x12, len(sub)]) + sub
    assert frame_time_ns(msg) == 300, frame_time_ns(msg)
    assert frame_time_ns(b"\x0a\x00") is None
    # 짝짓기: 33 ms 간격 행, MP4는 1 ms 흔들림 + 처음 두 행 전 시작 + 한 행에 두 장 + 멀리 벗어난 장
    rows = [i * 33_000_000 for i in range(10)]
    mp4 = [rows[2] + 1_000_000, rows[3] - 500_000, rows[4], rows[4] + 1_500_000, rows[6] + 10_000_000, None, rows[9]]
    m = match(mp4, rows)
    assert m == {2: (0, 1_000_000), 3: (1, -500_000), 9: (6, 0)}, m
    print("mp4_rgb selftest ok")


def main(args: list[str]) -> None:
    if args == ["--selftest"]:
        return selftest()
    force = "--force" in args
    sessions = [Path(a) for a in args if a != "--force"]
    if not sessions:
        sys.exit(__doc__)
    for s in sessions:
        info = extract(s, force)
        print(json.dumps(info, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
