"""M55 점유 판정(IMPROVE_SPEC §13 M55, 설계 3.3~3.5절): 칸 관측 기록(`cell_obs.bin.gz`, core `CellTrace`)으로 점유 규칙을 재생 없이 다시 돌린다.

    python occupancy.py repro <재생 폴더> ...      # 0단계: 기록으로 돌린 지금 규칙(VOTES)의 점유 칸이 재생과 모든 장에서 같은가
    python occupancy.py derive <기록 루트> <정렬 루트> <out.json>   # 1단계: λ 표·N·A·B·상한 유도와 오프라인 비교(설계 3.3~3.5절, 표 8)
    python occupancy.py selftest

기록 루트 = 세션 ID 폴더들(각각 cell_obs.bin.gz·align.json·replay_info.json), 정렬 루트 = `floor_ref.py` 출력(있으면 그 align.json, 정답과
깊이에 독립인 바닥). 정답은 `data/sessions/<ID>/annotations/obstacles.json`.

재생 폴더는 `-PcellTrace=true`로 돌린 `:core:replay` 출력(같은 폴더의 replay_info.json에서 설정을 읽음)이다.
"""
from __future__ import annotations

import gzip
import json
import struct
import sys
from pathlib import Path

import numpy as np

from align import load_config, to_truth
from metrics import load_truth

ROOT = Path(__file__).resolve().parents[2]
HIT = 0
# 분석 상수(M55 설계 표 5, 결과를 보기 전에 정함)
DONT_CARE_TAU = 3  # 면 칸 τ 밖 ~ 이 배수 τ 안은 판정 안 함(장면 지표 F 배수 중 가장 큰 것, 자체 선택)
FLOOR_IGNORE_M = 0.15  # 정답 바닥 + 이 높이 아래 칸은 판정 안 함(장면 지표 정답 바닥 불확실성 FLOOR_UNCERTAIN_M)
BANDS_M = (2.0, 3.5)  # 거리 띠 경계(장면 지표 FALSE_BANDS_M)
MIN_COUNT = 1000  # 조건마다 면·헛 칸·장 최소(모자라면 가까운 쪽 띠와 합침, 자체 선택)
MIN_WINDOWS = 30  # 문턱 맞추기에 쓰는 구간의 최소 면 칸 창 수(모자라면 그 구간은 맞추기에서 빼고 보고, 자체 선택)
SMOOTH = 0.5  # 칸·장 수의 Jeffreys 더하기(0 개수에서도 λ가 유한, 자체 선택)
LOW_OBJECTS = {"20260928_084542_S02": "paper_box", "20261003_131003_S01": "suitcase_lying",
               "20261003_131021_S01": "suitcase_lying", "20261003_131039_S01": "suitcase_lying"}
E_IDS = ["20261007_151701_S01", "20261007_151747_S01", "20261007_151835_S01", "20261007_152354_S01", "20261007_152418_S01",
         "20261007_152720_S01", "20261007_152743_S01", "20261007_152808_S01", "20261007_152833_S01", "20261007_152859_S01"]
K01_IDS = ["20261016_170426_S01", "20261016_170447_S01", "20261016_170537_S01", "20261016_170600_S01", "20261016_170655_S01",
           "20261016_170713_S01", "20261016_171028_S01", "20261016_171047_S01", "20261016_171216_S01", "20261016_171233_S01"]
E04_JUMP_S = 21.9  # 152859 자세 점프(DECISIONS 2026-10-27 M42): 이 뒤는 정렬이 맞지 않아 뺌

HIT_DT = np.dtype([("c", ">i4", 3), ("b", "u1"), ("v", ">f4")])  # 맞은 칸, 구간, 이번 장 표 무게
OBS_DT = np.dtype([("c", ">i4", 3), ("t", "u1"), ("b", "u1")])  # 관측 칸, 종류(1 가까움, 2 빔), 구간
CELL_DT = np.dtype((">i4", 3))
NEAR, FREE = 1, 2


def read_trace(path: Path):
    """기록을 차례로 낸다: ("R",), ("S", 배율), ("F", tNs, 카메라 xyz, hits, obs, pruned, occ)."""
    buf = gzip.decompress(path.read_bytes())
    i, n = 0, len(buf)
    while i < n:
        kind = chr(buf[i])
        i += 1
        if kind == "R":
            yield ("R",)
        elif kind == "S":
            yield ("S", struct.unpack_from(">f", buf, i)[0])
            i += 4
        elif kind == "F":
            t, x, y, z = struct.unpack_from(">qfff", buf, i)
            i += 20
            parts = []
            for dt in (HIT_DT, OBS_DT, CELL_DT, CELL_DT):
                (m,) = struct.unpack_from(">i", buf, i)
                i += 4
                parts.append(np.frombuffer(buf, dt, m, i))
                i += m * dt.itemsize
            yield ("F", t, np.array([x, y, z], np.float32), *parts)
        else:
            raise ValueError(f"{path}: unknown record {kind!r} at {i - 1}")


def _keys(cells: np.ndarray) -> list[tuple]:
    return [tuple(c) for c in cells.reshape(-1, 3).tolist()]


def repro(run_dir: Path) -> dict:
    """지금 규칙(HITS, hitWeighting DISTANCE, 감쇠 무게 없음)을 기록으로 돌려 장마다 점유 칸을 재생 기록과 비교한다. core `VoxelMap`과 같은
    float32 산술·순서(맞음 → 빔 → 삭제)."""
    m = load_config(run_dir)["map"]
    assert m["mode"] == "HITS" and m["hitWeighting"] == "DISTANCE" and m["decayWeighting"] == "NONE" and not m["decayBehind"], "VOTES 기준만"
    assert m["freeEvidenceDecay"] == 0, "VOTES 기준만"
    gain, decay, min_score = np.float32(m["hitGain"]), np.float32(m["decayPerObservation"]), np.float32(m["minScore"])
    min_hits = np.float32(m["minHits"])
    one, zero = np.float32(1), np.float32(0)
    cells: dict[tuple, list] = {}  # 칸 → [가중 표, 점수]
    frames = mismatched = 0
    first_bad = None
    for rec in read_trace(run_dir / "cell_obs.bin.gz"):
        if rec[0] == "R":
            cells.clear()
            continue
        if rec[0] == "S":
            f = np.float32(rec[1])
            for k in list(cells):
                c = cells[k]
                c[1] = max(zero, min(one, c[1] * f))
                if c[1] <= zero:
                    del cells[k]
            continue
        _, t, _, hits, obs, pruned, occ = rec
        for k, v in zip(_keys(hits["c"]), hits["v"].astype(np.float32).tolist()):
            v = np.float32(v)
            c = cells.get(k)
            if c is None:
                cells[k] = [v, min(one, gain * v)]
            else:
                c[0] = c[0] + v
                c[1] = min(one, c[1] + gain * v)
        for k in _keys(obs["c"][obs["t"] == FREE]):
            c = cells.get(k)
            if c is not None:
                c[1] = c[1] - decay
                if c[1] <= zero:
                    del cells[k]
        for k in _keys(pruned):
            cells.pop(k, None)
        mine = {k for k, c in cells.items() if c[0] >= min_hits and c[1] >= min_score}
        real = set(_keys(occ))
        frames += 1
        if mine != real:
            mismatched += 1
            if first_bad is None:
                first_bad = {"tNs": int(t), "onlySim": len(mine - real), "onlyReplay": len(real - mine)}
    return {"run": run_dir.name, "frames": frames, "mismatchedFrames": mismatched, "firstMismatch": first_bad}


# ---- 1단계: 정답으로 칸 나누기, λ 표, 창 N, 문턱 유도, 오프라인 비교 ----

def _pack(c: np.ndarray) -> np.ndarray:
    c = c.reshape(-1, 3).astype(np.int64) + (1 << 20)
    return (c[:, 0] << 42) | (c[:, 1] << 21) | c[:, 2]


def _unpack(k: np.ndarray) -> np.ndarray:
    m = (1 << 21) - 1
    return np.stack([(k >> 42) & m, (k >> 21) & m, k & m], 1) - (1 << 20)


class Session:
    """기록 한 세션을 평평한 배열로: 장 번호 f, 칸 키, 관측 종류(0 맞음·1 가까움·2 빔), 구간, 표 무게, 칸–카메라 거리·띠. 장마다 카메라 위치·
    시각, 삭제 칸 키, 지도 사건(R·S와 그 다음 장 번호)."""

    def __init__(self, run_dir: Path, voxel_m: float, max_t_s: float | None = None):
        cols = {k: [] for k in ("f", "key", "typ", "bin", "vote")}
        self.cam, self.tns, self.pruned, self.events = [], [], [], []
        fi = 0
        for rec in read_trace(run_dir / "cell_obs.bin.gz"):
            if rec[0] != "F":
                self.events.append((fi, rec[0], rec[1] if rec[0] == "S" else None))
                continue
            _, t, cam, hits, obs, pruned, _ = rec
            if max_t_s is not None and self.tns and (t - self.tns[0]) / 1e9 > max_t_s:
                break
            for keys, typ, b, v in ((_pack(hits["c"]), np.zeros(len(hits), np.uint8), hits["b"], hits["v"]),
                                    (_pack(obs["c"]), obs["t"], obs["b"], np.zeros(len(obs), np.float32))):
                cols["f"].append(np.full(len(keys), fi, np.int32))
                cols["key"].append(keys)
                cols["typ"].append(typ.astype(np.uint8))
                cols["bin"].append(b.astype(np.uint8))
                cols["vote"].append(v.astype(np.float32))
            self.cam.append(cam)
            self.tns.append(t)
            self.pruned.append(_pack(pruned))
            fi += 1
        self.nframes = fi
        if fi == 0:  # 바닥을 한 번도 못 잡아 지도가 갱신된 장이 없음(K01 끝 벽 1.0 m 등)
            return
        for k, v in cols.items():
            setattr(self, k, np.concatenate(v))
        self.cam = np.array(self.cam, np.float64)
        self.nframes = fi
        self.ukeys, self.inv = np.unique(self.key, return_inverse=True)
        self.centers = (_unpack(self.ukeys) + 0.5) * voxel_m
        self.dist = np.linalg.norm(self.centers[self.inv] - self.cam[self.f], axis=1)
        self.band = np.searchsorted(BANDS_M, self.dist, side="right").astype(np.uint8)
        self.starts = np.searchsorted(self.f, np.arange(self.nframes + 1))  # 장마다 행 범위(f는 오름차순)


def box_sdist(p: np.ndarray, lo, hi) -> np.ndarray:
    """점들 [p](N×3)에서 상자까지의 부호 있는 거리(안이면 음수)."""
    q = np.maximum(np.asarray(lo) - p, p - np.asarray(hi))
    return np.linalg.norm(np.maximum(q, 0), axis=1) + np.minimum(q.max(axis=1), 0)


def _truth_of(sid: str, run_dir: Path, align_root: Path):
    cfg = load_config(run_dir)
    f = align_root / sid / "align.json"
    al = json.loads((f if f.is_file() else run_dir / "align.json").read_text(encoding="utf-8"))
    truth, _ = load_truth(ROOT / "data" / "sessions" / sid, cfg)
    return cfg, al, truth or [], f.is_file()


def cell_labels(centers: np.ndarray, al: dict, truth: list[dict], tau: float) -> tuple[np.ndarray, np.ndarray]:
    """칸 중심(월드) → 1 면 칸, 0 헛 칸, −1 판정 안 함(설계 3.2절), 그리고 정답 좌표. 헛 칸은 구조물(벽·천장)이 정답에 있어 빈 곳을 아는
    세션에서만(구조물 상자들의 바깥 경계 안)."""
    x, y, z = to_truth(al, centers[:, 0], centers[:, 1], centers[:, 2])
    pt = np.stack([x, y, z], 1)
    d = np.min([box_sdist(pt, o["min"], o["max"]) for o in truth], axis=0) if truth else np.full(len(pt), np.inf)
    lab = np.full(len(pt), -1, np.int8)
    lab[d <= tau] = 1
    st = [o for o in truth if o["kind"] == "structure"]
    if st:
        lo = np.min([o["min"] for o in st], axis=0)
        hi = np.max([o["max"] for o in st], axis=0)
        inside = np.all((pt >= lo) & (pt <= hi), axis=1)
        lab[(d > DONT_CARE_TAU * tau) & (y > FLOOR_IGNORE_M) & inside] = 0
    return lab, pt


def lam_table(counts: np.ndarray, merge: list) -> np.ndarray:
    """counts[면·헛 2][관측 3][구간 4][띠 3] → λ[관측][구간][띠]. 같은 띠 묶음은 합쳐 셈."""
    lam = np.zeros((3, 4, 3))
    for b in range(4):
        for grp in merge[b]:
            c = counts[:, :, b, grp].sum(axis=2)  # 2 × 3
            p = (c + SMOOTH) / (c.sum(axis=1, keepdims=True) + 3 * SMOOTH)
            for d in grp:
                lam[:, b, d] = np.log(p[0]) - np.log(p[1])
    return lam


def band_merge(total: np.ndarray) -> tuple[list, list[int]]:
    """조건(구간, 띠)마다 면·헛 칸·장이 MIN_COUNT 미만이면 그 띠를 가까운 쪽 띠와 합친다(0~2 m 띠는 다음 띠와). 합쳐도 모자란 구간을 함께 낸다."""
    merge, short = [], []
    for b in range(4):
        groups = [[0], [1], [2]]
        changed = True
        while changed and len(groups) > 1:
            changed = False
            for gi in range(len(groups) - 1, -1, -1):
                if total[:, :, b, groups[gi]].sum(axis=(1, 2)).min() < MIN_COUNT:
                    j = gi - 1 if gi > 0 else 1
                    lo, hi = min(gi, j), max(gi, j)
                    groups[lo] = sorted(groups[lo] + groups[hi])
                    del groups[hi]
                    changed = True
                    break
        if total[:, :, b, :].sum(axis=(1, 2)).min() < MIN_COUNT:
            short.append(b)
        merge.append(groups)
    return merge, short


def windows(s: Session, lab: np.ndarray, n: int):
    """면·헛 칸마다 창: 칸이 3.5 m 안에서 처음 관측된 행부터 관측 n개(설계 3.4절 1번). 돌려주는 값: 칸 라벨, (창 × n) 종류·구간·띠·무게(빈 자리 종류 255)."""
    order = np.lexsort((s.f, s.inv))
    inv = s.inv[order]
    gstart = np.searchsorted(inv, np.arange(len(s.ukeys) + 1))
    ok = s.dist[order] <= BANDS_M[1]
    out_lab, rows = [], []
    for c in np.nonzero(lab >= 0)[0]:
        a, e = gstart[c], gstart[c + 1]
        first = np.nonzero(ok[a:e])[0]
        if len(first) == 0:
            continue
        st = a + first[0]
        rows.append(order[st:min(st + n, e)])
        out_lab.append(lab[c])
    W = len(rows)
    T = np.full((W, n), 255, np.uint8)
    B = np.zeros((W, n), np.uint8)
    D = np.zeros((W, n), np.uint8)
    V = np.zeros((W, n), np.float32)
    for i, r in enumerate(rows):
        T[i, :len(r)] = s.typ[r]
        B[i, :len(r)] = s.bin[r]
        D[i, :len(r)] = s.band[r]
        V[i, :len(r)] = s.vote[r]
    return np.array(out_lab, np.int8), T, B, D, V


def votes_detect(T, V, m: dict) -> np.ndarray:
    """창마다 지금 규칙(빈 칸에서 시작)이 한 번이라도 점유로 만드는가."""
    W, n = T.shape
    exists = np.zeros(W, bool)
    wh = np.zeros(W)
    sc = np.zeros(W)
    det = np.zeros(W, bool)
    g, dec = m["hitGain"], m["decayPerObservation"]
    for j in range(n):
        t, v = T[:, j], V[:, j].astype(np.float64)
        h = t == HIT
        wh = np.where(h, np.where(exists, wh + v, v), wh)
        sc = np.where(h, np.minimum(1, np.where(exists, sc + g * v, g * v)), sc)
        exists |= h
        fr = (t == FREE) & exists
        sc = np.where(fr, sc - dec, sc)
        exists &= ~(fr & (sc <= 0))
        det |= exists & (wh >= m["minHits"]) & (sc >= m["minScore"])
    return det


def llr_max(T, B, D, lam: np.ndarray, b_del: float) -> np.ndarray:
    """창마다 증거 합의 최댓값(빈 칸에서 시작, L ≤ B면 지움). 상한은 A보다 커서 'A에 닿는가'에 영향이 없다."""
    W, n = T.shape
    exists = np.zeros(W, bool)
    L = np.zeros(W)
    best = np.full(W, -np.inf)
    for j in range(n):
        t = T[:, j]
        valid = t != 255
        x = np.where(valid, lam[np.minimum(t, 2), B[:, j], D[:, j]], 0.0)
        h = t == HIT
        L = np.where(h, np.where(exists, L + x, x), np.where(exists & valid, L + x, L))
        exists |= h
        exists &= ~(L <= b_del)
        best = np.where(exists, np.maximum(best, L), best)
    return best


def derive_threshold(lab, T, B, D, V, lam, m: dict) -> dict:
    """설계 3.4절: 구간마다 지금 규칙의 면 칸 창 검출률 p0(b)에 맞춘 가장 큰 A_b, A = min A_b. B = ln(β/(1−α))는 A와 함께 되풀이해 맞춘다."""
    S = lab == 1
    valid = T != 255
    maj = np.array([np.bincount(b[v], minlength=4).argmax() if v.any() else 0 for b, v in zip(B, valid)])
    det0 = votes_detect(T, V, m)
    b_del, hist, per, A = -np.inf, [], {}, None
    for _ in range(20):
        best = llr_max(T, B, D, lam, b_del)
        per = {}
        for b in range(4):
            sel = S & (maj == b)
            nb = int(sel.sum())
            p0 = float(det0[sel].mean()) if nb else None
            if nb < MIN_WINDOWS or not p0:
                per[b] = {"windows": nb, "p0": p0, "A": None}
                continue
            k = int(np.ceil(p0 * nb))
            per[b] = {"windows": nb, "p0": p0, "A": float(np.sort(best[sel])[::-1][k - 1])}
        As = [v["A"] for v in per.values() if v["A"] is not None]
        A = min(As) if As else None
        if A is None:
            break
        alpha = float((best[~S] >= A).mean())
        beta = float(1 - (best[S] >= A).mean())
        nb_del = float(np.log(beta / (1 - alpha))) if beta > 0 else -np.inf
        hist.append({"A": A, "B": nb_del, "alphaWindow": alpha, "betaWindow": beta})
        if nb_del == b_del or abs(nb_del - b_del) < 1e-9:
            break
        b_del = nb_del
    return {"perBin": per, "A": A, "B": b_del, "iterations": hist, "windows": {"surface": int(S.sum()), "false": int((~S).sum())},
            "votesWindow": {"p0All": float(det0[S].mean()), "alphaWindow": float(det0[~S].mean())}}


class Votes:
    """지금 규칙(VOTES). [conf_weights]를 주면 M54 지도 무게 W(근삿값: 기록의 맞음 구간은 칸 중심 화소, core W는 그 장 점들의 가장 큰 무게)."""

    def __init__(self, m: dict, conf_weights=None):
        self.m, self.cw = m, conf_weights

    def hit(self, st, v, b, d):
        v = self.cw[b] if self.cw is not None else v
        g = self.m["hitGain"]
        return [v, min(1.0, g * v)] if st is None else [st[0] + v, min(1.0, st[1] + g * v)]

    def obs(self, st, t, b, d):
        if t != FREE:
            return st
        st[1] -= self.m["decayPerObservation"]
        return st if st[1] > 0 else None

    def occ(self, st):
        return st[0] >= self.m["minHits"] and st[1] >= self.m["minScore"]

    def scale(self, st, f):
        st[1] = max(0.0, min(1.0, st[1] * f))
        return st if st[1] > 0 else None


class Llr:
    """새 규칙(설계 3.4~3.5절): 맞음이면 칸을 만들고 L += λ, 가까움·빔이면 있는 칸만 L += λ, 상한으로 자르고 L ≤ B면 지움, 점유는 L ≥ A."""

    def __init__(self, lam, A, B, lmax):
        self.lam, self.A, self.B, self.lmax = lam, A, B, lmax

    def _clip(self, L):
        L = min(self.lmax, L)
        return None if L <= self.B else [L]

    def hit(self, st, v, b, d):
        return self._clip(self.lam[HIT, b, d] + (st[0] if st is not None else 0.0))

    def obs(self, st, t, b, d):
        return self._clip(st[0] + self.lam[t, b, d])

    def occ(self, st):
        return st[0] >= self.A

    def scale(self, st, f):
        return self._clip(st[0] * f)


def simulate(s: Session, rule, keyset: np.ndarray, per_frame) -> None:
    """[keyset] 칸만 장 순서대로 규칙을 돌리고 장마다 per_frame(장 번호, 점유 칸 키 배열)을 부른다(맞음 → 가까움·빔 → 삭제, core와 같은 순서)."""
    sel = np.isin(s.key, keyset)
    ev = iter(s.events)
    nxt = next(ev, None)
    cells: dict[int, list] = {}
    for fi in range(s.nframes):
        while nxt is not None and nxt[0] <= fi:
            if nxt[1] == "R":
                cells.clear()
            else:
                for k in list(cells):
                    st = rule.scale(cells[k], nxt[2])
                    if st is None:
                        del cells[k]
                    else:
                        cells[k] = st
            nxt = next(ev, None)
        a, e = s.starts[fi], s.starts[fi + 1]
        r = np.nonzero(sel[a:e])[0] + a
        for i in r[s.typ[r] == HIT]:
            k = int(s.key[i])
            st = rule.hit(cells.get(k), float(s.vote[i]), int(s.bin[i]), int(s.band[i]))
            if st is None:
                cells.pop(k, None)
            else:
                cells[k] = st
        for i in r[s.typ[r] != HIT]:
            k = int(s.key[i])
            if k in cells:
                st = rule.obs(cells[k], int(s.typ[i]), int(s.bin[i]), int(s.band[i]))
                if st is None:
                    del cells[k]
                else:
                    cells[k] = st
        for k in s.pruned[fi].tolist():
            cells.pop(k, None)
        per_frame(fi, np.array([k for k, st in cells.items() if rule.occ(st)], np.int64))


def derive(trace_root: Path, align_root: Path, out: Path) -> dict:
    rep: dict = {"constants": {"dontCareTau": DONT_CARE_TAU, "floorIgnoreM": FLOOR_IGNORE_M, "bandsM": BANDS_M, "minCount": MIN_COUNT,
                               "minWindows": MIN_WINDOWS, "smooth": SMOOTH}}
    cfg0 = load_config(trace_root / E_IDS[0])
    m, vs = cfg0["map"], cfg0["map"]["voxelSizeM"]
    tau = float(np.sqrt(3) / 2 * vs)
    # 창 N: 낮은 물체 네 통과에서 물체 칸이 맞음을 받은 장 수(규칙과 무관). 물체 칸 = 물체 바닥 자리(x·z ± τ)이고 정답 높이 ≤ 윗면 + 0.15 m
    # (정답 바닥이 원천마다 0.1~0.37 m 어긋나 높이로는 고르지 않음, 1단계 구현 중 정함)
    lows, low_sessions = {}, {}
    for sid, name in LOW_OBJECTS.items():
        s = Session(trace_root / sid, vs)
        cfg, al, truth, ref = _truth_of(sid, trace_root / sid, align_root)
        o = next(o for o in truth if o["name"] == name)
        x, y, z = to_truth(al, s.centers[:, 0], s.centers[:, 1], s.centers[:, 2])
        own = ((x >= o["min"][0] - tau) & (x <= o["max"][0] + tau) & (z >= o["min"][2] - tau) & (z <= o["max"][2] + tau)
               & (y <= o["max"][1] + FLOOR_IGNORE_M))
        hit_frames = np.unique(s.f[(s.typ == HIT) & own[s.inv]])
        lows[sid] = {"object": name, "cells": int(own.sum()), "hitFrames": int(len(hit_frames)), "alignFloorRef": ref}
        low_sessions[sid] = (s, own, al, o)
    # 물체 칸이 맞음을 한 번도 받지 않은 통과(131021: 바닥을 늦게 잡아 지도가 93장만 갱신, 캐리어 자리 칸 없음)는 어떤 점유 규칙도 잡을 수 없어 빼고 잼
    N = min(v["hitFrames"] for v in lows.values() if v["hitFrames"] > 0)
    rep["N"] = N
    rep["lowObjects"] = lows
    # λ 표: 측정 세션의 면·헛 칸·장 수
    per_sess, wins, skipped = {}, [], []
    for sid in E_IDS + K01_IDS:
        s = Session(trace_root / sid, vs, E04_JUMP_S if sid == "20261007_152859_S01" else None)
        if s.nframes == 0:
            skipped.append(sid)
            continue
        cfg, al, truth, ref = _truth_of(sid, trace_root / sid, align_root)
        lab, pt = cell_labels(s.centers, al, truth, tau)
        rl = lab[s.inv]
        c = np.zeros((2, 3, 4, 3), np.int64)
        k = rl >= 0
        np.add.at(c, (1 - rl[k], s.typ[k], s.bin[k], s.band[k]), 1)  # 0 = 면, 1 = 헛
        per_sess[sid] = {"counts": c, "alignFloorRef": ref, "surface": int((lab == 1).sum()), "false": int((lab == 0).sum())}
        wins.append((sid, s, lab, pt))
    total = sum(v["counts"] for v in per_sess.values())
    merge, short = band_merge(total)
    lam = lam_table(total, merge)
    rng = np.random.default_rng(1)
    sids = list(per_sess)
    boot = np.array([lam_table(sum(per_sess[sids[i]]["counts"] for i in rng.integers(0, len(sids), len(sids))), merge) for _ in range(1000)])
    rep["table"] = {"order": "[관측 맞음·가까움·빔][구간 원시 없음·1~29·30~127·≥128][띠 0~2·2~3.5·3.5~ m]; counts는 앞에 [면·헛]",
                    "lambda": lam.round(4).tolist(), "lo": np.percentile(boot, 2.5, axis=0).round(4).tolist(),
                    "hi": np.percentile(boot, 97.5, axis=0).round(4).tolist(), "merge": merge, "shortBins": short, "counts": total.tolist()}
    rep["sessions"] = {sid: {k: v[k] for k in ("alignFloorRef", "surface", "false")} for sid, v in per_sess.items()}
    rep["skippedNoMapFrames"] = skipped
    # 문턱: 측정 세션의 면·헛 칸 창
    parts = [windows(s, lab, N) for _, s, lab, _ in wins]
    WL = np.concatenate([p[0] for p in parts])
    WT, WB, WD, WV = (np.concatenate([p[i] for p in parts]) for i in range(1, 5))
    th = derive_threshold(WL, WT, WB, WD, WV, lam, m)
    rep["threshold"] = th
    A, Bd = th["A"], th["B"]
    if A is None or A <= 0:
        rep["stop"] = "맞춘 A 없음(설계 표 8 문턱 기준)"
        out.write_text(json.dumps(rep, ensure_ascii=False, indent=1), encoding="utf-8")
        return rep
    lmax = A + 3 * abs(lam[FREE, 3, 0])
    rep["rule"] = {"A": A, "B": Bd, "Lmax": float(lmax), "waldAlphaBound": float(np.exp(-A))}
    rules = {"VOTES": Votes(m), "W": Votes(m, cfg0["depth"]["mapConfidenceWeights"]), "LLR": Llr(lam, A, Bd, lmax)}
    # 오프라인 헛 칸(E 10): 통로 상자 근사(정답 좌우 |x| ≤ 폭/2, 바닥 위 ≤ 높이, 카메라 수평 길이 안) 안의 헛 점유 칸·장
    cor = cfg0["corridor"]
    fa: dict = {r: {} for r in rules}
    for sid, s, lab, pt in wins:
        if sid not in E_IDS:
            continue
        cand = (lab == 0) & (np.abs(pt[:, 0]) <= cor["widthM"] / 2) & (pt[:, 1] <= cor["heightM"])
        cen = dict(zip(s.ukeys[cand].tolist(), s.centers[cand]))
        for name, rule in rules.items():
            acc = [0]

            def count(fi, occ, acc=acc, s=s, cen=cen):
                if len(occ):
                    c = np.array([cen[k] for k in occ.tolist()])
                    acc[0] += int((np.hypot(c[:, 0] - s.cam[fi, 0], c[:, 2] - s.cam[fi, 2]) <= cor["lengthM"]).sum())
            simulate(s, rule, s.ukeys[cand], count)
            fa[name][sid] = acc[0]
    diffs = np.array([fa["LLR"][k] - fa["VOTES"][k] for k in fa["VOTES"]])
    bs = [diffs[rng.integers(0, len(diffs), len(diffs))].sum() for _ in range(2000)]
    rep["offlineFalse"] = {"perSession": fa, "sum": {r: sum(v.values()) for r, v in fa.items()},
                           "llrMinusVotes": int(diffs.sum()), "ci95": [float(np.percentile(bs, 2.5)), float(np.percentile(bs, 97.5))]}
    # 오프라인 낮은 물체: 물체 칸 cluster.minSamples개가 처음 점유될 때 카메라–물체 앞면 거리(정답 z)
    lowres: dict = {}
    for sid, (s, own, al, o) in low_sessions.items():
        lowres[sid] = {}
        for name, rule in rules.items():
            first = [None]

            def f5(fi, occ, first=first, s=s, al=al, o=o):
                if first[0] is None and len(occ) >= cfg0["cluster"]["minSamples"]:
                    zc = to_truth(al, s.cam[fi, 0], s.cam[fi, 1], s.cam[fi, 2])[2]
                    first[0] = round(float(o["min"][2] - zc), 3)
            simulate(s, rule, s.ukeys[own], f5)
            lowres[sid][name] = first[0]
    rep["offlineLow"] = lowres
    out.write_text(json.dumps(rep, ensure_ascii=False, indent=1), encoding="utf-8")
    return rep


def selftest() -> None:
    p = np.array([[0.0, 0.5, 0.5], [2.0, 0.5, 0.5], [0.5, 0.5, 0.5]])
    assert np.allclose(box_sdist(p, [0, 0, 0], [1, 1, 1]), [0.0, 1.0, -0.5])
    lam = np.zeros((3, 4, 3))
    lam[HIT] = 1.0
    lam[FREE] = -2.0
    T = np.array([[0, 0, 0, 255], [0, 2, 0, 0]], np.uint8)
    Z = np.zeros_like(T)
    assert llr_max(T, Z, Z, lam, -np.inf).tolist() == [3.0, 1.0]  # 1, −1, 0, 1
    assert llr_max(T, Z, Z, lam, -0.5).tolist() == [3.0, 2.0]  # −1 ≤ B에서 지우고 다시 시작: 1, 지움, 1, 2
    m = {"hitGain": 0.2, "decayPerObservation": 0.3, "minHits": 2, "minScore": 0.1}
    assert votes_detect(np.array([[0, 0], [0, 2]], np.uint8), np.ones((2, 2), np.float32), m).tolist() == [True, False]
    tot = np.full((2, 3, 4, 3), 1000)
    tot[:, :, 0, 2] = 10
    mg, short = band_merge(tot)
    assert mg[0] == [[0], [1, 2]] and mg[1] == [[0], [1], [2]] and not short, mg
    c = np.zeros((2, 3, 4, 3))
    c[0, HIT], c[0, FREE], c[1, HIT], c[1, FREE] = 80, 20, 20, 80
    assert np.isclose(lam_table(c, [[[0], [1], [2]]] * 4)[HIT, 0, 0], np.log(80.5 / 101.5) - np.log(20.5 / 101.5))
    print("occupancy selftest ok")


def main(args: list[str]) -> None:
    if not args or args[0] not in ("repro", "derive", "selftest"):
        sys.exit(__doc__)
    if args[0] == "repro":
        out = [repro(Path(a)) for a in args[1:]]
        print(json.dumps(out, ensure_ascii=False, indent=1))
        if any(r["mismatchedFrames"] for r in out):
            sys.exit(1)
    elif args[0] == "derive":
        r = derive(Path(args[1]), Path(args[2]), Path(args[3]))
        print(json.dumps({k: r[k] for k in ("N", "lowObjects", "threshold", "rule", "offlineFalse", "offlineLow", "stop") if k in r},
                         ensure_ascii=False, indent=1))
    else:
        selftest()


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
