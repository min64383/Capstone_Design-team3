# testdata — PC 분석용 녹화 경량본

기기가 없는 팀원도 **같은 입력**으로 `core`를 돌릴 수 있도록 대표 녹화 세션 몇 개를 git에 둔다(MVP_SPEC §14-6, v0.2.2).
형식은 [`docs/FORMAT.md`](../docs/FORMAT.md) 그대로라서 `SessionReader(File("testdata/sessions/<세션ID>"))`로 바로 읽는다.

- **빠진 것:** `arcore.mp4`(기기 재생 모드 전용, 세션 용량의 2/3), 분석 결과(`spike_check.txt`). 재생 모드용 전체 세션은 팀 공유 드라이브(README §7.2)
- **들어 있는 것:** `meta.json`, `frames.csv`, `device.csv`, `depth/`, `raw_depth/`, `depth_conf/`, `rgb/`(장면 확인·정답 주석용), 있으면 `annotations/`
- 자동 테스트의 합격 기준은 계속 합성 장면이다. 여기 세션은 실제 데이터 확인·분석·파라미터 조정용이고, 골든 벡터에도 넣지 않는다(README §4.8)
- 레포는 private이지만 `rgb/`에는 촬영 장소가 보인다. 외부에 공개할 때는 이 폴더를 빼야 한다

## 세션

| 세션 ID | 형식 | 길이 | 크기 | 장면 | 정답·주의 |
|---|---|---|---|---|---|
| `20260928_084542_S02` | v0 | 10.9 s | 12.1 MB | 의자: 시작 표시에서 보행선 2 m | 의자 좌우 위치·크기 미측정 |
| `20260928_101025_S01` | v0 | 7.5 s | 5.9 MB | 벽 정면 1.00 m (줄자, 렌즈 기준) | F4 척도 확인용 |
| `20260928_102615_S01` | v1 | 10.0 s | 7.5 MB | G1 v1 형식 확인 (폰 고정) | 첫 v1 세션 |

설명은 `docs/FORMAT.md` "세션 기록"과 같다. **파지 오프셋은 `meta.json`에 0으로 들어 있으니** 실측값(눈 중앙이 카메라 기준 위 0.50 m, 뒤 0.30 m, 102615는 폰 고정이라 해당 없음)은 FORMAT.md를 따른다.

## 세션 추가

git 이력에 영구히 남으므로 꼭 필요한 세션만 올린다(세션당 30 MB 이하 권장, 10분 T01 같은 긴 녹화는 올리지 않는다).

```powershell
# data/sessions/ 에 있는 세션을 경량본으로 복사 (-NoRgb 로 rgb 제외)
powershell -ExecutionPolicy Bypass -File tools\setup\export-testdata.ps1 -Id <세션ID>
```

복사한 뒤 위 표에 한 줄 추가하고 PR로 올린다. 세션 폴더 이름은 바꾸지 않는다.
