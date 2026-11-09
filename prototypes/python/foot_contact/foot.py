"""M53 설계 측정: 의미 영상 사람 마스크의 발 접점(땅 쪽 끝) 광선 ∩ 바닥면 → 클릭 정답과 거리 비교. 재생 없음."""
import json, sys, io
from collections import deque
from pathlib import Path
import numpy as np
import pandas as pd
from PIL import Image

ROOT = Path('C:/Users/chawu/dev/coursework/capstone')
sys.path.insert(0, str(ROOT / 'tools/analysis'))
import event_eval as E
from motion_stats import FLIP, _sem_k
from spike_check import quat_to_rot
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
OUT = ROOT / 'data/sweeps/m53/design'
PERSON = 10
EDGE = 2  # 영상 끝 몇 px 안에 닿은 마스크는 발이 잘렸다고 봄
HEIGHT_M = 1.70  # 키 기반 거리의 사람 키(어림, 비교용)


def components(mask):
    lab = np.zeros(mask.shape, int); n = 0; out = []
    H, W = mask.shape
    for i, j in zip(*np.nonzero(mask)):
        if lab[i, j]: continue
        n += 1; q = deque([(i, j)]); lab[i, j] = n; pix = []
        while q:
            a, b = q.popleft(); pix.append((a, b))
            for da, db in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                x, y = a + da, b + db
                if 0 <= x < H and 0 <= y < W and mask[x, y] and not lab[x, y]:
                    lab[x, y] = n; q.append((x, y))
        out.append(np.array(pix))
    return out


rows = []
truth_rows = []
for s in sys.argv[1:]:
    ses = ROOT / f'data/sessions/20261102_{s}_S01'
    meta = json.loads((ses / 'meta.json').read_text(encoding='utf-8'))
    ev = E.load_events(ses)
    fr = E.load_frames(ses) if hasattr(E, 'load_frames') else pd.read_csv(ses / 'frames.csv').drop_duplicates('tNs')
    fr = fr[fr.tracking == 'TRACKING'].reset_index(drop=True)
    movers = [o for o in ev['objects'] if o['kind'] == 'moving' and o.get('added') != 'check']
    floor_y = np.median([c['floorY'] for o in movers for c in o['clicks']])
    tracks = {o['id']: E.moving_track(o) for o in movers}
    idx = pd.read_csv(ses / 'semantic/index.csv')
    ft = fr.tNs.to_numpy()
    for _, e in idx.iterrows():
        img = np.array(Image.open(ses / e.file)); H, W = img.shape
        k = _sem_k(meta, W, H)
        j = int(np.argmin(np.abs(ft - e.imageTNs))); pr = fr.iloc[j]
        R = quat_to_rot(np.array([[pr.qx, pr.qy, pr.qz, pr.qw]]))[0] @ FLIP
        cam = np.array([pr.tx, pr.ty, pr.tz])
        truths = {i: tr(e.imageTNs / 1e9) for i, tr in tracks.items()}
        for i, got in truths.items():
            if got is None: continue
            q = np.array([got[0][0], floor_y, got[0][1]]); pc = R.T @ (q - cam)
            uu = k['fx'] * pc[0] / pc[2] + k['cx'] if pc[2] > 0.1 else -1
            vv = k['fy'] * pc[1] / pc[2] + k['cy'] if pc[2] > 0.1 else -1
            truth_rows.append(dict(s=s, t=e.imageTNs, truth=i, dt=float(np.hypot(q[0]-cam[0], q[2]-cam[2])), inimg=bool(0 <= uu < W - 1 - EDGE and EDGE < vv < H - 1 - EDGE)))
        for comp in components(img == PERSON):
            if len(comp) < 20: continue
            # 땅 쪽: +u(열) 방향(폰 세로). comp[:,0]=행(v), comp[:,1]=열(u)
            umax = comp[:, 1].max()
            cut = umax >= W - 1 - EDGE or comp[:, 0].min() <= EDGE or comp[:, 0].max() >= H - 1 - EDGE
            low = comp[comp[:, 1] >= umax - 1]
            u, v = umax + 1.0, low[:, 0].mean() + 0.5
            d = R @ np.array([(u - k['cx']) / k['fx'], (v - k['cy']) / k['fy'], 1.0])
            if d[1] >= -1e-6: continue
            t = (floor_y - cam[1]) / d[1]
            p = cam + d * t
            dist = float(np.hypot(*(p - cam)[[0, 2]]))
            best = None
            for i, got in truths.items():
                if got is None: continue
                q = got[0]
                err = np.hypot(p[0] - q[0], p[2] - q[1])
                if best is None or err < best[1]: best = (i, err, float(np.hypot(q[0] - cam[0], q[1] - cam[2])))
            # 키 기반 거리(비교용): 서 있는 키 H 선분의 위·아래 끝 올려본각 차 → d = H / (tan α위 − tan α아래). 바닥 높이가 식에 없다
            umin = comp[:, 1].min()
            top = comp[comp[:, 1] <= umin + 1]
            d_top = R @ np.array([(umin - k['cx']) / k['fx'], (top[:, 0].mean() + 0.5 - k['cy']) / k['fy'], 1.0])
            tan_up = lambda r: r[1] / np.hypot(r[0], r[2])
            dist_h = HEIGHT_M / (tan_up(d_top) - tan_up(d)) if tan_up(d_top) > tan_up(d) else np.nan
            rows.append(dict(s=s, t=e.imageTNs, n=len(comp), cut=cut, cutTop=bool(umin <= EDGE), distH=dist_h, dist=dist,
                             truth=None if best is None else best[0], dt=None if best is None else best[2],
                             err2d=None if best is None else best[1], hcam=cam[1] - floor_y,
                             umax=int(umax)))
df = pd.DataFrame(rows)
OUT.mkdir(parents=True, exist_ok=True); df.to_csv(OUT / 'foot_rows.csv', index=False)
pd.DataFrame(truth_rows).to_csv(OUT / 'truth_rows.csv', index=False)
m = df[(~df.cut) & df.truth.notna()].copy()
m['e'] = m.dist - m.dt
bins = [1.5, 2, 2.5, 3, 3.5, 4, 5, 7]
m['bin'] = pd.cut(m.dt, bins)
print('components', len(df), 'cut', int(df.cut.sum()), 'with truth', len(m))
g = m.groupby(['bin'], observed=True)
print(g.e.describe(percentiles=[.05, .5, .95]).round(3).to_string())
print(m.groupby('s').apply(lambda x: pd.Series(dict(n=len(x), med=x.e.median(), p95abs=x.e.abs().quantile(.95), err2d95=x.err2d.quantile(.95), dmin=x.dt.min(), dmax=x.dt.max()))).round(3).to_string())
nt = df[(~df.cut) & df.truth.isna()]
print('no-truth comps (not cut):', len(nt), nt.groupby('s').dist.describe().round(2).to_string() if len(nt) else '')
