"""M53 설계 측정 요약: foot.py 결과(data/sweeps/m53/design)와 M59 조합 재생 기록(data/sweeps/m59/t/a4)으로 설계 문서의 표를 만든다.
발 접점 나옴 비율, 거리 오차(클릭 정답 앞면 = 클릭 − 0.15 m 기준) — 정답 바닥·앱 바닥·카메라 높이 W초 중앙값 바닥, 화소 잡음 맞춤,
앱 바닥 변화율, 앱 바닥 − floor_rgb. 출력 summary.json. 재생 없음."""
import json, sys, io
from pathlib import Path
import numpy as np
import pandas as pd

ROOT = Path('C:/Users/chawu/dev/coursework/capstone')
D = ROOT / 'data/sweeps/m53/design'
RUN = ROOT / 'data/sweeps/m59/t/a4'
T7 = ['150159', '150300', '150338', '150439', '150457', '150957', '151045']
F_PX = 1517.1217 * 256 / 1920  # 의미 영상 긴 축 초점 거리(텍스처 K 비례, px)
FRONT = 0.15  # 정답 앞면 = 클릭 − 0.15 m(명세 §9.5, M52)
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
ses = lambda s: ROOT / f'data/sessions/20261102_{s}_S01'


def click_floor(s):
    ev = json.loads((ses(s) / 'annotations/events.json').read_text(encoding='utf-8'))
    return float(np.median([c['floorY'] for o in ev['objects'] if o['kind'] == 'moving' for c in o['clicks']]))


def app_floor(s, w_s):
    """재생의 앱 바닥(slow_path.csv). w_s > 0이면 카메라 높이(카메라 y − 앱 바닥)의 지난 w_s초 중앙값으로 다시 만든 바닥(과거만)."""
    sp = pd.read_csv(RUN / f'20261102_{s}_S01/slow_path.csv').dropna(subset=['floorY']).sort_values('tCaptureNs')
    t, f = sp.tCaptureNs.to_numpy(), sp.floorY.to_numpy()
    if w_s <= 0:
        return t, f
    fr = pd.read_csv(ses(s) / 'frames.csv').drop_duplicates('tNs').sort_values('tNs')
    cy = np.interp(t, fr.tNs, fr.ty)
    h = cy - f
    return t, np.array([cy[i] - np.median(h[(t >= t[i] - w_s * 1e9) & (t <= t[i])]) for i in range(len(t))])


def rate_p95(t, f):
    """1 s 창 직선 맞춤 기울기 |d floor/dt|의 95% 분위수(m/s)."""
    ts, r = t / 1e9, []
    for i in range(len(ts)):
        w = (ts >= ts[i] - 1) & (ts <= ts[i])
        if w.sum() >= 10 and np.ptp(ts[w]) > 0.8:
            r.append(abs(np.polyfit(ts[w], f[w], 1)[0]))
    return float(np.percentile(r, 95))


rows = pd.read_csv(D / 'foot_rows.csv')
truth = pd.read_csv(D / 'truth_rows.csv')
m = rows[(~rows.cut) & rows.truth.notna()]
m = m.loc[m.groupby(['s', 't', 'truth']).err2d.idxmin()]
m = m[m.err2d <= 0.6].copy()  # 정답 사람에 맞게 짝지은 마스크(0.6 m 밖은 다른 사람·잘린 발)
m['s'] = m.s.astype(str)
out = {'nMatched': len(m)}

# 나옴 비율: 정답 발 점이 영상 안(가장자리 2 px 밖)인 장 중 맞게 짝지은 마스크가 있는 장
key = set(zip(m.s, m.t, m.truth))
truth['s'] = truth.s.astype(str)
tv = truth[truth.inimg].copy()
tv['got'] = [(a, b, c) in key for a, b, c in zip(tv.s, tv.t, tv.truth)]
bins_a = [1.5, 2, 2.5, 3, 3.5, 4, 4.5, 8]
tv['bin'] = pd.cut(tv.dt, bins_a)
out['availability'] = {str(k): dict(rate=round(float(g.got.mean()), 3), n=len(g)) for k, g in tv.groupby('bin', observed=True)}

# 화소 잡음 맞춤(정답 바닥 = 공유 바닥이라 바닥 몫 없음): sd² = σ0² + (σpx·g)², g = (h² + d²)/(h·f)
m['e_truth'] = m.dist - m.dt + FRONT
m['g'] = (m.hcam ** 2 + m.dt ** 2) / (m.hcam * F_PX)
bins_e = [2, 2.5, 3, 3.5, 4, 4.5, 8]
m['bin'] = pd.cut(m.dt, bins_e)
b = m.groupby('bin', observed=True).agg(sd=('e_truth', 'std'), g=('g', 'mean'), n=('e_truth', 'size'))
A = np.c_[np.ones(len(b)), b.g ** 2] * np.sqrt(b.n.to_numpy())[:, None]
c = np.linalg.lstsq(A, (b.sd ** 2).to_numpy() * np.sqrt(b.n.to_numpy()), rcond=None)[0]
out['noiseFit'] = dict(sigma0M=round(float(np.sqrt(max(c[0], 0))), 3), sigmaPx=round(float(np.sqrt(max(c[1], 0))), 2), fPx=round(F_PX, 1))

# 바닥별 거리 오차: 발 접점 거리는 바닥 높이에 비례(d ∝ 카메라 높이)하므로 정답 바닥 거리를 비례로 바꾼다
def with_floor(w_s):
    parts = []
    for s, g in m.groupby('s'):
        t, fl = app_floor(s, w_s)
        fc = click_floor(s)
        fa = fl[np.clip(np.searchsorted(t, g.t.to_numpy(), side='right') - 1, 0, None)]
        camy = g.hcam.to_numpy() + fc
        parts.append(g.assign(e=g.dist * (camy - fa) / (camy - fc) - g.dt + FRONT))
    return pd.concat(parts)


def stats(x):
    return dict(med=round(float(x.median()), 3), sd=round(float(x.std()), 3), p95abs=round(float(x.abs().quantile(.95)), 3), n=len(x))


err = {'truthFloor': {str(k): stats(g.e_truth) for k, g in m.groupby('bin', observed=True)}}
per_session = {}
for w in (0, 2, 4, 8):
    a = with_floor(w)
    err[f'app_W{w}'] = {str(k): stats(g.e) for k, g in a.groupby('bin', observed=True)}
    mid = a[(a.dt > 2.5) & (a.dt <= 4.5)]
    err[f'app_W{w}']['2.5-4.5'] = stats(mid.e)
    per_session[f'W{w}'] = {s: stats(g.e) for s, g in mid.groupby('s')}
err['truthFloor']['2.5-4.5'] = stats(m[(m.dt > 2.5) & (m.dt <= 4.5)].e_truth)
out['error'] = err

# 세션마다 축척(측정 ÷ 정답 앞면 중앙값)을 뺀 상대 오차: 일정한 축척은 TTC에 안 들어가므로 남는 몫만 본다(2.5~4.5 m, 머리 끝 안 잘린 장)
a = with_floor(8).rename(columns={'e': 'e8'})
a['app8'] = a.e8 + a.dt - FRONT
a = a.merge(with_floor(0)[['s', 't', 'truth', 'e']], on=['s', 't', 'truth'])
a['app0'] = a.e + a.dt - FRONT
a = a[(a.dt > 2.5) & (a.dt <= 4.5) & (~a.cutTop) & a.distH.notna()]
scale_removed = {'n': len(a)}
for col, lab in (('dist', 'foot_truthFloor'), ('app0', 'foot_app'), ('app8', 'foot_app_W8'), ('distH', 'height_1.70m')):
    rel, jit, scale = [], [], {}
    for s, g in a.groupby('s'):
        ratio = g[col] / (g.dt - FRONT)
        k = float(np.median(ratio)); scale[s] = round(k, 3)
        g = g.assign(rel=ratio / k - 1).sort_values('t')
        rel += g.rel.tolist()
        dt = np.diff(g.t.to_numpy()) / 1e9
        jit += list(np.diff(g.rel.to_numpy())[dt < 0.15])  # 이웃 장(150 ms 안) 차이
    rel = np.array(rel)
    scale_removed[lab] = dict(relSdPct=round(100 * rel.std(ddof=1), 1), relP95Pct=round(100 * float(np.percentile(np.abs(rel), 95)), 1),
                              jitterPct=round(100 * float(np.std(jit, ddof=1)) / np.sqrt(2), 1), scale=scale)
out['scaleRemoved2.5-4.5'] = scale_removed
out['errorPerSession2.5-4.5'] = per_session

# 앱 바닥 변화율, 앱 바닥 − floor_rgb(깊이와 독립인 걷는 세션)
out['floorRateP95'] = {s: {f'W{w}': round(rate_p95(*app_floor(s, w)), 3) for w in (0, 2, 4, 8)} for s in T7}


def lag_sd(t, f, lag_s=0.5):
    """바닥 높이의 lag_s초 사이 변화(f(t) − f(t − lag))의 σ(m). 칼만이 기억하는 시간 동안의 바닥 흔들림(σ_h, M53 0단계)."""
    j = np.searchsorted(t, t - lag_s * 1e9, side='right') - 1
    ok = j >= 0
    return float(np.std(f[ok] - f[j[ok]], ddof=1))


sh = {s: round(lag_sd(*app_floor(s, 8)), 4) for s in T7}
out['floorLagSd0.5s_W8'] = dict(perSession=sh, median=round(float(np.median(list(sh.values()))), 4),
                                pooled=round(float(np.sqrt(np.mean(np.square(list(sh.values()))))), 4))
rgb = {}
for s in T7:
    fy = json.loads((ses(s) / 'floor_rgb.json').read_text(encoding='utf-8')).get('floorY')
    if fy is None:
        continue
    _, f = app_floor(s, 0)
    d = f - fy
    rgb[s] = dict(med=round(float(np.median(d)), 3), sd=round(float(d.std()), 3))
out['appMinusRgbFloor'] = rgb

# 카메라 기하: 카메라 높이(정답 바닥 기준), 광축 숙임, 의미 영상 땅 쪽 끝 광선이 바닥에 닿는 거리(d_min), 의미 영상 간격
sys.path.insert(0, str(ROOT / 'tools/analysis'))
from motion_stats import FLIP, _sem_k
from spike_check import quat_to_rot
geo = {}
for s in T7:
    meta = json.loads((ses(s) / 'meta.json').read_text(encoding='utf-8'))
    fy = json.loads((ses(s) / 'floor_rgb.json').read_text(encoding='utf-8')).get('floorY')
    if fy is None:
        fy = json.loads((ses(s) / 'annotations/events.json').read_text(encoding='utf-8'))['floorYFallback']
    idx = pd.read_csv(ses(s) / 'semantic/index.csv')
    k = _sem_k(meta, int(idx.width[0]), int(idx.height[0]))
    fr = pd.read_csv(ses(s) / 'frames.csv').drop_duplicates('tNs')
    fr = fr[fr.tracking == 'TRACKING'].iloc[::10]
    R = quat_to_rot(fr[['qx', 'qy', 'qz', 'qw']].to_numpy()) @ FLIP
    h = fr.ty.to_numpy() - fy
    pitch = np.degrees(np.arcsin(R[:, 1, 2]))
    edges = [(k['cx'], k['height']), (k['cx'], 0), (k['width'], k['cy']), (0, k['cy'])]  # 네 변 가운데
    dmin = []
    for Ri, hi in zip(R, h):
        dep = max(-(Ri @ np.array([(u - k['cx']) / k['fx'], (v - k['cy']) / k['fy'], 1]))[1] /
                  np.linalg.norm((Ri @ np.array([(u - k['cx']) / k['fx'], (v - k['cy']) / k['fy'], 1]))[[0, 2]]) for u, v in edges)
        dmin.append(hi / dep if dep > 0 else np.inf)
    dt = np.diff(idx.imageTNs.to_numpy()) / 1e6
    geo[s] = dict(hM=round(float(np.median(h)), 2), pitchDeg=round(float(np.median(pitch)), 1), dMinM=round(float(np.median(dmin)), 2),
                  semDtMs=round(float(np.median(dt))), semDtP90Ms=round(float(np.percentile(dt, 90))), semSize=f"{k['width']}x{k['height']}")
out['geometry'] = geo
(D / 'summary.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')
print(json.dumps(out, ensure_ascii=False, indent=1))
