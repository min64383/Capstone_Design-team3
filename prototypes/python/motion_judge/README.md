# motion_judge — 빈 곳 위반 움직임 증거(M56)

- 상태: 실험 중(0단계 측정). 이식 대상 core/…/tracking/Tracker.kt, MotionTracking.kt(설계 승인 뒤).
- 명세: docs/IMPROVE_SPEC.md v0.4.18 §13 M56, milepost M56 설계 3.3절.
- `free_space.py`: 판정 알고리즘(numpy만, Kotlin 반복문으로 옮김 주석).
- `stage0.py`: 0단계 측정 실행기. 세션·자세·라벨·정답 로더는 tools/analysis에서 가져온다(측정용).

```
python stage0.py sigma <재생 폴더>... --out data/sweeps/m56/stage0/sigma.json
python stage0.py viol  <재생 폴더>... --table data/sweeps/m56/stage0/sigma.json --out data/sweeps/m56/stage0/viol.csv
```

재생 폴더는 B+W5 재생(data/sweeps/m55/in_BW5, out_BW5/o01, out_BW5/t). 결과는 data/ 아래(git 밖).
