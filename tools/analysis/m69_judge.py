"""M69 판정(milepost demo-relay/M69-design 표 8): 정답 파일(m69_truth.json 형식)과 PC 재생 말 기록(speech.csv)을 견준다.

- 갈림·꺾임: 정답 사건마다 창 [fromS, toS] 안에 같은 문구가 있으면 맞음. 창 밖이거나 다른 문구의 갈림·꺾임은 거짓.
- 계단: stairs = up 세션에서 "내려가는 계단", down 세션에서 "올라가는 계단"은 거꾸로. down 세션에서 "내려가는 계단"을 말한 시각·거리도 적는다.
시각 = 세션 frames.csv 첫 행 tNs부터의 초, 프레임 = frames.csv의 frameIndex.

사용: python tools/analysis/m69_judge.py <정답 json> <재생 출력 폴더(세션별 하위 폴더)> [세션 폴더 루트 = data/sessions]
"""
import bisect
import csv
import json
import os
import sys


def main():
    truth = json.load(open(sys.argv[1], encoding='utf-8'))
    out, sroot = sys.argv[2], (sys.argv[3] if len(sys.argv) > 3 else 'data/sessions')
    fork_turn = set(truth['forkTurnTexts'])
    totals = dict(false=0, hit=0, events=0, wrong_stairs=0)
    for sid, t in truth['sessions'].items():
        frames = list(csv.DictReader(open(os.path.join(sroot, sid, 'frames.csv'))))
        ft = [int(r['tNs']) for r in frames]
        t0 = ft[0]
        said = []
        for r in csv.DictReader(open(os.path.join(out, sid, 'speech.csv'), encoding='utf-8')):
            i = max(0, bisect.bisect_right(ft, int(r['tNs'])) - 1)
            said.append(((int(r['tNs']) - t0) / 1e9, int(frames[i]['frameIndex']), r['text']))
        used = set()
        lines = []
        for e in t['events']:
            totals['events'] += 1
            m = next((k for k, (s, f, x) in enumerate(said) if k not in used and x == e['text'] and e['fromS'] <= s <= e['toS']), None)
            if m is not None:
                used.add(m)
                totals['hit'] += 1
                lines.append(f"  맞음  {e['text']} @ {said[m][0]:.1f} s ({said[m][1]} f), 창 {e['fromS']}~{e['toS']} s")
            else:
                lines.append(f"  놓침  {e['text']}, 창 {e['fromS']}~{e['toS']} s")
        for k, (s, f, x) in enumerate(said):
            if k in used:
                continue
            if x in fork_turn:
                totals['false'] += 1
                lines.append(f"  거짓  {x} @ {s:.1f} s ({f} f)")
            if t.get('stairs') and x.startswith(truth['wrongStairs'][t['stairs']]):
                totals['wrong_stairs'] += 1
                lines.append(f"  계단 거꾸로  {x} @ {s:.1f} s ({f} f)")
            if t.get('stairs') == 'down' and x.startswith('내려가는 계단'):
                lines.append(f"  (기록) {x} @ {s:.1f} s ({f} f)")
            if t.get('stairs') == 'up' and x == '올라가는 계단':
                lines.append(f"  (기록) {x} @ {s:.1f} s ({f} f)")
        print(f"== {sid}: {t.get('user', '')}")
        print('\n'.join(lines) if lines else '  (말 없음)')
    print(f"\n① 거짓 갈림·꺾임 {totals['false']} (목표 0)  ② 맞은 자리 {totals['hit']}/{totals['events']}  ③ 계단 거꾸로 {totals['wrong_stairs']} (목표 0)")


if __name__ == '__main__':
    main()
