"""M69 반복 일치: 같은 녹화를 여러 번 돌린 말 기록이 서로 같은지 본다(라이브의 흔들림에 대한 강건성).

입력: <root>/<방식>/<회>/<세션>/speech.csv (PC 흔들기 run_pc.sh, 기기 재생 run_device.py 출력).
- 말 묶음: 같은 종류(문장에서 쉼표 앞, 예 "블록 끝, 5미터" → "블록 끝")이고 시각이 ±window 안인 말을 회차를 건너 한 묶음으로.
- 모든 회차에 있는 묶음 = 안정, 일부 회차에만 = 흔들림. 일치율 = 안정 묶음에 든 말 / 전체 말.
- 회차마다 m69_judge.py 판정(거짓 갈림·꺾임, 맞은 자리, 계단 거꾸로)도 센다.
시각 = 세션 frames.csv 첫 tNs부터의 초.

사용: python tools/analysis/m69_repeat.py <root> [--window 1.0] [--truth tools/analysis/m69_truth.json] [--sessions data/sessions]
"""
import argparse
import csv
import os
import re
import subprocess
import sys


def load(path, t0):
    if not os.path.isfile(path):
        return None
    return [((int(r['tNs']) - t0) / 1e9, r['text']) for r in csv.DictReader(open(path, encoding='utf-8'))]


def kind(text):
    return text.split(',')[0].strip()


def clusters(runs, window):
    """runs: {회: [(s, text)]} → [(종류, 대표 시각, {회: 문장})]. 같은 종류·±window를 이어 붙인다(단일 연결)."""
    items = sorted((s, kind(x), r, x) for r, said in runs.items() for s, x in said)
    out = []
    for s, k, r, x in items:
        c = next((c for c in reversed(out) if c['kind'] == k and s - c['last'] <= window), None)
        if c is None:
            c = dict(kind=k, first=s, last=s, runs={})
            out.append(c)
        c['last'] = s
        c['runs'].setdefault(r, x)
    return out


def judge(truth, out_dir, sroot):
    p = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), 'm69_judge.py'), truth, out_dir, sroot],
                       capture_output=True, text=True, encoding='utf-8')
    m = re.search(r'거짓 갈림·꺾임 (\d+).*맞은 자리 (\d+)/(\d+).*계단 거꾸로 (\d+)', p.stdout)
    return tuple(int(g) for g in m.groups()) if m else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('root')
    ap.add_argument('--window', type=float, default=1.0)
    ap.add_argument('--truth', default='tools/analysis/m69_truth.json')
    ap.add_argument('--sessions', default='data/sessions')
    a = ap.parse_args()
    for method in sorted(os.listdir(a.root)):
        mdir = os.path.join(a.root, method)
        if not os.path.isdir(mdir):
            continue
        run_ids = sorted(os.listdir(mdir), key=lambda x: int(re.sub(r'\D', '', x) or 0))
        sids = sorted({s for r in run_ids for s in os.listdir(os.path.join(mdir, r))})
        print(f"===== {method}: {len(run_ids)}회")
        tot_all = tot_stable = 0
        for sid in sids:
            t0 = int(next(csv.DictReader(open(os.path.join(a.sessions, sid, 'frames.csv'))))['tNs'])
            runs = {r: v for r in run_ids if (v := load(os.path.join(mdir, r, sid, 'speech.csv'), t0)) is not None}
            cs = clusters(runs, a.window)
            n_all = sum(len(c['runs']) for c in cs)
            n_stable = sum(len(c['runs']) for c in cs if len(c['runs']) == len(runs))
            tot_all += n_all
            tot_stable += n_stable
            print(f"-- {sid}: {len(runs)}회, 말 {n_all}, 모든 회차에 같은 말 {n_stable}")
            for c in cs:
                tag = '안정' if len(c['runs']) == len(runs) else '흔들림'
                print(f"   {tag:3s} {c['kind']:14s} {c['first']:5.1f}~{c['last']:5.1f} s  {len(c['runs'])}/{len(runs)}회")
        rate = tot_stable / tot_all if tot_all else float('nan')
        js = [judge(a.truth, os.path.join(mdir, r), a.sessions) for r in run_ids]
        js = [j for j in js if j]
        print(f"   일치율 {tot_stable}/{tot_all} = {rate:.2f}")
        if js:
            print("   회차별 판정(거짓 갈림·꺾임, 맞은 자리, 계단 거꾸로): " + ', '.join(f"{f}/{h}/{w}" for f, h, e, w in js) + f" (정답 자리 {js[0][2]})")


if __name__ == '__main__':
    main()
