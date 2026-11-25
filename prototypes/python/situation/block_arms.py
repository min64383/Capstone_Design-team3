"""M69 팔 세기 프로토타입(설계 3.1~3.3절). PC 재생의 블록 칸 기록(`-PblockCells=true`)을 읽어 판단마다 중심·팔·너머를 정한다.

너머의 깊이 확인은 기록의 노면 점 낮음(block_points.csv, 없으면 라벨만)으로 한다. 최종 구현은 core `BlockArms`(README 참고).

사용: python prototypes/python/situation/block_arms.py <재생 출력 폴더> [--plot 초,초,...] [--t0 <첫 장 tNs>] [--recent]
"""
import argparse
import math
import os
from collections import defaultdict

import pandas as pd

CELL = 0.1
BLOCK, SIDEWALK, ALLEY, ROAD, CROSS, STAIRS, BG = 1, 2, 3, 4, 5, 6, 0
HAZARD = {ROAD, CROSS, ALLEY}  # 배경은 차·건물을 가르지 못해 모름(T01 259 s 블록 위 차)
P = dict(endMin=1.5, endMax=3.0, turnJoin=0.3, centerStep=0.3, armMin=1.0, armGap=0.2, stepDeg=5, maxSpread=60,
         turnMin=22.5, beyondFrom=0.3, beyondTo=1.5, beyondHalf=0.3, seenFrac=0.5, walkFrac=0.8, sideFrom=0.45,
         confirm=2, merge=2.0, sideMin=40, axisTol=10, minPts=10, dropMin=0.5, dropFrac=0.5, floorTol=0.2, floorFrac=0.2)

RECENT = False  # True: 칸 라벨 = 최근 3번 관측의 다수결(recentMajor·recentBlock), False: 지난 5 s 다수결


class Grid:
    def __init__(self, df, pts=None):
        if RECENT and 'recentMajor' in df.columns:
            self.c = {(int(r.ix), int(r.iz)): (int(r.recentMajor), int(r.recentBlock), int(r.nFrames)) for r in df.itertuples()}
        else:
            self.c = {(int(r.ix), int(r.iz)): (int(r.major), int(r.nBlock), int(r.nFrames)) for r in df.itertuples()}
        self.pts = None if pts is None else list(zip(pts.x.values, pts.z.values, pts.cls.values, pts.lowM.values))

    def block(self, x, z):
        v = self.c.get((math.floor(x / CELL), math.floor(z / CELL)))
        return v is not None and v[0] == BLOCK and v[1] >= P['confirm']


def rays(g, cx, cz):
    """72방향마다 1 m에 닿으면 True."""
    out = []
    n = int(360 / P['stepDeg'])
    for k in range(n):
        a = math.radians(k * P['stepDeg'])
        dx, dz = math.cos(a), math.sin(a)
        d, gap, ok = 0.0, 0.0, False
        while True:
            d += 0.05
            if d > P['armMin'] + 1e-6:
                ok = True
                break
            if g.block(cx + dx * d, cz + dz * d):
                gap = 0.0
            else:
                gap += 0.05
                if gap > P['armGap'] + 1e-6:
                    break
        out.append(ok)
    return out


def arms_of(hits):
    """이웃한 True를 묶어 (가운데 각, 폭, 묶음의 각 목록). 360° 전부면 [(None, 360, [])]."""
    n = len(hits)
    if all(hits):
        return [(None, 360.0, [])]
    start = hits.index(False)
    groups, cur = [], []
    for i in range(1, n + 1):
        k = (start + i) % n
        if hits[k]:
            cur.append(k)
        elif cur:
            groups.append(cur)
            cur = []
    res = []
    for gr in groups:
        mid = gr[0] + (len(gr) - 1) / 2
        res.append(((mid * P['stepDeg']) % 360, len(gr) * P['stepDeg'], [(k * P['stepDeg']) % 360 for k in gr]))
    return res


def angdiff(a, b):
    return abs((a - b + 180) % 360 - 180)


def band_classes(g, cx, cz, fx, fz, a0, a1, half):
    """중심에서 방향 (fx,fz)로 [a0,a1], 옆 ±half 띠의 칸: (칸 수, 본 칸의 값 목록)."""
    rx, rz = -fz, fx
    seen, total = [], 0
    keys = set()
    a = a0
    while a <= a1 + 1e-6:
        s = -half
        while s <= half + 1e-6:
            x, z = cx + fx * a + rx * s, cz + fz * a + rz * s
            k = (math.floor(x / CELL), math.floor(z / CELL))
            if k not in keys:
                keys.add(k)
                total += 1
                v = g.c.get(k)
                if v is not None:
                    seen.append(v)
            s += CELL
        a += CELL
    return total, seen


def band_lows(g, cx, cz, fx, fz):
    """너머 띠 안 노면 점의 낮음 목록(기록이 없으면 None)."""
    if g.pts is None:
        return None
    rx, rz = -fz, fx
    out = []
    for x, z, _c, lo in g.pts:
        a = (x - cx) * fx + (z - cz) * fz
        s = (x - cx) * rx + (z - cz) * rz
        if P['beyondFrom'] <= a <= P['beyondTo'] and abs(s) <= P['beyondHalf']:
            out.append(lo)
    return out


def beyond(g, cx, cz, fx, fz):
    """너머: stairs / drop / raised / hazard / floor / unknown, 그리고 띠에 블록 칠이 있는지."""
    total, seen = band_classes(g, cx, cz, fx, fz, P['beyondFrom'], P['beyondTo'], P['beyondHalf'])
    anyblock = any(v[0] == BLOCK or v[1] > 0 for v in seen)
    if not seen:
        return 'unknown', anyblock
    stairs = sum(1 for v in seen if v[0] == STAIRS and v[2] >= P['confirm'])
    if stairs * 2 > len(seen):
        return 'stairs', anyblock
    lows = band_lows(g, cx, cz, fx, fz)
    if lows is not None and len(lows) >= P['minPts']:
        if sum(1 for lo in lows if lo > P['dropMin']) > P['dropFrac'] * len(lows):
            return 'drop', anyblock
        if sum(1 for lo in lows if lo < -P['floorTol']) > 0.5 * len(lows):
            return 'raised', anyblock
    haz = sum(1 for v in seen if v[0] in HAZARD)
    if haz * 2 > len(seen):
        return 'hazard', anyblock
    walk = sum(1 for v in seen if v[0] in (SIDEWALK, BLOCK))
    if len(seen) >= P['seenFrac'] * total and walk >= P['walkFrac'] * len(seen):
        if lows is None:
            return 'floor', anyblock
        if len(lows) >= P['minPts'] and sum(1 for lo in lows if abs(lo) > P['floorTol']) <= P['floorFrac'] * len(lows):
            return 'floor', anyblock
    return 'unknown', anyblock


def side_clear(g, cx, cz, ang):
    """옆 방향 ang(도) ±turnMin, sideFrom~armMin 칸: 절반 이상 관측, 본 칸 과반이 블록 아님."""
    keys, seen = set(), []
    for dd in range(-int(P['turnMin']), int(P['turnMin']) + 1, 5):
        a = math.radians(ang + dd)
        d = P['sideFrom']
        while d <= P['armMin'] + 1e-6:
            k = (math.floor((cx + math.cos(a) * d) / CELL), math.floor((cz + math.sin(a) * d) / CELL))
            if k not in keys:
                keys.add(k)
                v = g.c.get(k)
                if v is not None:
                    seen.append(v)
            d += 0.05
    if len(seen) < 0.5 * len(keys):
        return False
    nb = sum(1 for v in seen if not (v[0] == BLOCK and v[1] >= P['confirm']))
    return nb * 2 > len(seen)


def decide(g, step):
    ux, uz = step.userX, step.userZ
    fx, fz = step.lineDx, step.lineDz
    fdeg = math.degrees(math.atan2(fz, fx))
    tu = (ux - step.lineX) * fx + (uz - step.lineZ) * fz
    out = []
    t = tu + P['endMin']
    tend = min(tu + P['endMax'], step.tMax + P['turnJoin'])
    while t <= tend + 1e-6:
        cx, cz = step.lineX + fx * t, step.lineZ + fz * t
        # 띠 가운데로 옮김: RANSAC 선은 띠 가장자리를 지날 수 있다(안쪽 점 띠 ±0.2 m). 선 앞뒤 ±0.15 m, 옆 ±0.45 m 블록 칸의 옆 평균
        lats = []
        for kx in range(-6, 7):
            for kz in range(-6, 7):
                x, z = cx + kx * CELL, cz + kz * CELL
                along = (x - cx) * fx + (z - cz) * fz
                lat = (x - cx) * -fz + (z - cz) * fx
                if abs(along) <= 0.15 and abs(lat) <= 0.45 and g.block(x, z):
                    lats.append(lat)
        if lats:
            m = sum(lats) / len(lats)
            cx, cz = cx - fz * m, cz + fx * m
            arms = arms_of(rays(g, cx, cz))
            rec = dict(t=t - tu, cx=cx, cz=cz, arms=arms, kind=None, beyond=None)
            if any(w > P['maxSpread'] for _, w, _x in arms):
                rec['kind'] = 'area'
            else:
                # 앞·뒤 팔 = 따라가는 방향 ±axisTol을 실제로 포함하는 묶음. 옆 팔 = 앞·뒤에서 sideMin 넘게 떨어진 묶음.
                # 그 사이 묶음(휜 띠 조각, 모서리 판 조각)은 어느 쪽에도 세지 않는다
                def has(dirdeg, angs):
                    return any(angdiff(x, dirdeg) <= P['axisTol'] for x in angs)
                back = any(has(fdeg + 180, xs) for a, _, xs in arms)
                fwd = any(has(fdeg, xs) for a, _, xs in arms)
                sides = [a for a, _, xs in arms if not has(fdeg, xs) and not has(fdeg + 180, xs)
                         and angdiff(a, fdeg) >= P['sideMin'] and angdiff(a, fdeg + 180) >= P['sideMin']]
                rec.update(back=back, fwd=fwd, sides=sides)
                if not back:
                    rec['kind'] = 'noback'
                elif fwd:
                    rec['kind'] = 'fork' if sides else 'straight'
                else:
                    b, anyblock = beyond(g, cx, cz, fx, fz)
                    rec['beyond'] = b
                    right = (fdeg + 90) % 360  # rightOf(f) = (-fz, fx): 각 +90°
                    left = (fdeg - 90) % 360
                    if b == 'stairs':
                        rec['kind'] = 'stairs'
                    elif b in ('hazard', 'drop', 'raised'):
                        rec['kind'] = 'end'
                    elif b == 'floor':
                        if len(sides) >= 2:
                            rec['kind'] = 'fork'
                        elif len(sides) == 1:
                            is_right = angdiff(sides[0], right) < angdiff(sides[0], left)
                            other = left if is_right else right
                            rec['kind'] = ('turnR' if is_right else 'turnL') if side_clear(g, cx, cz, other) else 'unseen'
                        else:
                            # 인도 위 끝: 양옆 확인 + 너머에 블록 칠이 전혀 없음(옛 끝 규칙, 칠 끊김을 끝으로 보지 않게)
                            rec['kind'] = 'end' if not anyblock and side_clear(g, cx, cz, right) and side_clear(g, cx, cz, left) else 'unseen'
                    else:
                        rec['kind'] = 'unknown'
            out.append(rec)
        t += P['centerStep']
    return out


SPEAK = {'fork': '블록 갈림', 'turnR': '블록 오른쪽으로 꺾임', 'turnL': '블록 왼쪽으로 꺾임', 'end': '블록 끝'}


def main():
    global RECENT
    ap = argparse.ArgumentParser()
    ap.add_argument('run')
    ap.add_argument('--t0', type=int, default=0)
    ap.add_argument('--plot', default='')
    ap.add_argument('--recent', action='store_true', help='칸 라벨을 최근 3번 관측 다수결로')
    a = ap.parse_args()
    RECENT = a.recent
    steps = pd.read_csv(os.path.join(a.run, 'block_steps.csv'))
    cells = pd.read_csv(os.path.join(a.run, 'block_cells.csv'))
    byt = dict(tuple(cells.groupby('tNs')))
    pf = os.path.join(a.run, 'block_points.csv')
    bypts = dict(tuple(pd.read_csv(pf).groupby('tNs'))) if os.path.exists(pf) else None
    t0 = a.t0 or int(steps.tNs.min())
    junctions, said, counts = [], [], defaultdict(int)
    plots = [float(x) for x in a.plot.split(',') if x]
    plot_recs = {}
    for st in steps.itertuples():
        g = Grid(byt.get(st.tNs, cells.iloc[0:0]), None if bypts is None else bypts.get(st.tNs, pd.DataFrame(columns=['x', 'z', 'cls', 'lowM'])))
        recs = decide(g, st)
        ts = (st.tNs - t0) / 1e9
        for r in recs:
            counts[r['kind']] += 1
            if r['kind'] in SPEAK:
                # 한 번 말한 자리를 사용자가 지날 때까지(그 자리가 아직 앞에 있는 동안) 다른 블록 자리 말을 하지 않는다
                ahead = any((x - st.userX) * st.headX + (z - st.userZ) * st.headZ > 0 for x, z in junctions[-1:])
                j = next((i for i, (x, z) in enumerate(junctions) if math.hypot(x - r['cx'], z - r['cz']) <= P['merge']), None)
                if j is None and not ahead:
                    junctions.append((r['cx'], r['cz']))
                    said.append((ts, SPEAK[r['kind']], r['t'], r['beyond'], len(junctions) - 1))
        for p in plots:
            if p not in plot_recs and ts >= p:
                plot_recs[p] = (st, g, recs, ts)
    print('center kinds:', dict(counts))
    print('speech (first per junction):')
    for s in said:
        print(f'  {s[0]:7.1f} s  {s[1]:<16} ahead {s[2]:.1f} m  beyond={s[3]}  #{s[4]}')
    if plot_recs:
        plot(plot_recs, os.path.join(a.run, 'arms_plots.png'))


def plot(plot_recs, path):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.patches import Circle
    n = len(plot_recs)
    fig, axs = plt.subplots(1, n, figsize=(3.2 * n, 4.2))
    axs = [axs] if n == 1 else axs
    colors = {BLOCK: '#d4a017', SIDEWALK: '#cfcfcf', ALLEY: '#9fb7d9', ROAD: '#6d6d6d', CROSS: '#ffffff', STAIRS: '#a35bb5', BG: '#7fae7f'}
    for ax, (p, (st, g, recs, ts)) in zip(axs, sorted(plot_recs.items())):
        fx, fz = st.lineDx, st.lineDz
        rx, rz = -fz, fx

        def tr(x, z):  # 진행 방향 위, 오른쪽 오른쪽
            dx, dz = x - st.userX, z - st.userZ
            return dx * rx + dz * rz, dx * fx + dz * fz
        for (ix, iz), (maj, nb, nf) in g.c.items():
            x, y = tr((ix + 0.5) * CELL, (iz + 0.5) * CELL)
            if -3.5 < x < 3.5 and -1 < y < 6.5:
                c = colors.get(maj, '#eeeeee')
                if maj == BLOCK and nb < P['confirm']:
                    c = '#f3e3a8'
                ax.add_patch(plt.Rectangle((x - 0.05, y - 0.05), 0.1, 0.1, color=c, lw=0))
        ax.plot([0], [0], 'k^', ms=8)
        for r in recs:
            x, y = tr(r['cx'], r['cz'])
            mk = {'fork': 'r*', 'turnR': 'b>', 'turnL': 'b<', 'end': 'ms', 'stairs': 'mD'}.get(r['kind'], 'k.')
            ax.plot([x], [y], mk, ms=9 if r['kind'] in SPEAK or r['kind'] == 'stairs' else 4)
            if r['kind'] in ('fork', 'turnR', 'turnL', 'end'):
                ax.add_patch(Circle((x, y), 1.0, fill=False, ls='--', lw=0.8))
                for ang, _, _x in r['arms']:
                    if ang is None:
                        continue
                    a = math.radians(ang)
                    ex, ey = tr(r['cx'] + math.cos(a), r['cz'] + math.sin(a))
                    ax.annotate('', (ex, ey), (x, y), arrowprops=dict(arrowstyle='->', lw=1.5))
        ax.set_xlim(-3.5, 3.5)
        ax.set_ylim(-1, 6.5)
        ax.set_aspect('equal')
        ax.set_title(f'{ts:.1f} s', fontsize=10)
        ax.tick_params(labelsize=7)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    print('plot:', path)


if __name__ == '__main__':
    main()
