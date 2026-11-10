# foot_contact — 깊이 밖 사람 거리(발 접점) 설계 측정

- 상태: 이식 완료 d856260f(M53). core 파일: `core/.../tracking/FootContact.kt`(발 접점 측정), `FarTracker.kt`(발 접점 track). 골든 벡터 `core/src/test/resources/golden/tracking/foot_contact.json`(golden.py, 합성 의미 영상)
- 명세: IMPROVE_SPEC §13 M53, §9.5(클릭 정답 기하)
- 재생 없음. 입력은 T 세션(`data/sessions/20261102_*_S01`)의 의미 영상·자세·클릭 정답과 M59 조합 재생 기록(`data/sweeps/m59/t/a4`의 `slow_path.csv` 앱 바닥)

| 파일 | 하는 일 |
|---|---|
| `foot.py <세션 시각…>` | 의미 영상마다 사람 라벨 덩어리(4-이웃)의 땅 쪽 끝 화소 광선을 바닥면(클릭 정답 바닥)과 만나게 해 거리를 재고, 같은 시각 클릭 정답과 비교. `data/sweeps/m53/design/foot_rows.csv`·`truth_rows.csv` |
| `golden.py` | 합성 의미 영상(256×144)·세로 폰 자세로 foot.py의 발 접점을 계산해 core 골든 벡터를 쓴다(실제 녹화 넣지 않음) |
| `eval_m53.py <재생 폴더…>` | M53 판정: 접근 판정 시각(깊이·발 접점 중 이른 것 대 마감), 발 접점 정지 거짓 판정, 구현의 거리·속도 오차, 손넘김 거리 |
| `same_bytes.py` | 두 재생 변형 출력이 바이트 단위로 같은지(회귀) |
| `summary.py` | 발 접점 나옴 비율, 거리 오차(정답 앞면 = 클릭 − 0.15 m 기준; 정답 바닥·앱 바닥·카메라 높이 W초 중앙값 바닥), 화소 잡음 맞춤, 앱 바닥 변화율·앱 바닥 − floor_rgb. `summary.json` |

실행: `.venv/Scripts/python.exe prototypes/python/foot_contact/foot.py 150159 150300 150338 150439 150457` 뒤 `summary.py`.
