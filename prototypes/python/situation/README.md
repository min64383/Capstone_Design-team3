# situation — 블록 팔 세기 프로토타입 (M69)

- 상태: **이식 완료** → core `situation/BlockArms.kt`. 이식 뒤에 더한 규칙(사용자 쪽으로 이어진 뒤 팔, M51 선 시작 규칙, 두 장 연속, 앞을 가로지르는 가로줄 계단, 높음 → 끝, 방지턱 = 차도, 너머 가로줄 몫)은 core에만 있다. 이 파일은 블록 칸 지도를 그려 보는 진단용으로 남긴다.
- 대응 절: milepost `demo-relay/M69-design` 3.1~3.3절, docs/IMPROVE_SPEC.md M69 행.
- 입력: PC 재생 `-PblockCells=true` 기록(`block_steps.csv`·`block_cells.csv`·`block_points.csv`, 재생 출력 폴더).
- 실행: `python prototypes/python/situation/block_arms.py <재생 출력 폴더> --t0 <첫 장 tNs> --recent [--plot 초,초,...]` (그림은 출력 폴더의 `arms_plots.png`, git 밖).
