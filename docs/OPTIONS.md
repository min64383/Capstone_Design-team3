# 옵션 장부 (M43)

파이프라인 단계마다 남아 있는 옵션과 그 판정 기록이다. 옵션은 재판정 전에 지우지 않는다(C1 판정 방식, DECISIONS 2026-10-27). 재판정은 그 단계를 맡는 마일스톤에서, **앞 단계를 정답으로 바꿔 넣은 재생**과 그 단계의 지표로 한다. 결과는 이 표에 한 줄로 더한다.

단계 순서: 깊이 입력 → 지도 → 요소 분해(바닥) → 군집·추적 → 진행 방향 → 통로 판정 → 안내.

| 단계 | 키 (기본 → 옵션) | 처음 판정 | 지금 기본 | 재판정 |
|---|---|---|---|---|
| 깊이 입력 | `depth.source` SMOOTHED → RAW | M3(F6 비교)·M32(원시 3상태 지도) | SMOOTHED | M37(조건부) |
| 깊이 입력 | `frontend.enabled` 경계 판정 | M13.1a: 기기 확인 전 꺼짐 | false | M37 |
| 깊이 입력 | `frontend.planes` NONE → RANSAC, `floor.source` PLANE | M13.1c: 기본값 아님 | NONE / HISTOGRAM | M35(바닥) |
| 깊이 입력 | `frontend.rgbGuide` NONE → G·EDGE·EDGE_MARGIN | M13.7·M19(G 상자 모양만 개선)·M23(Ra·Rb 확인용 STOP 누락) | NONE | M37 |
| 깊이 입력 | 학습형 깊이 장(재생 인자 `-PdepthFrom=<변형>/<full\|fill>`, 설정 `depth.learnedSigma*`) | M37: ZipDepth 대체 F(하류 기준 미달), MiDaS 버림, SML 무거움 | 없음(재생 전용) | 깊이 입력이 바뀌거나 새 공간(M38) |
| 깊이 입력 | `frontend.segment` NONE → REGION, `map.instances` | M13.3(C3a·C3b) | NONE / false | M35 |
| 지도 | `map.mode` HITS → LOG_ODDS·TSDF·TRISTATE | M13.2·M13.6·M32 | HITS | M37 |
| 지도 | `map.hitWeighting` DISTANCE | v0.3.14 기본값 | DISTANCE | M37 |
| 깊이 입력 | `vision.movableDepth` SMOOTHED → RAW(사람·차량 라벨 화소는 지도에 원시 점, 신뢰도 ≥ 30) | M54: 사람 둘레 고리 칸 243 → 11, 출력 늦음 ≤ 0.33 s, 경고 7.40 → 7.54 s(단독) | SMOOTHED | M56(P를 끈 B+W5가 사람 빈 장·실외 경고 모두 적음(M55), 장애물 라벨 전체를 "P처럼" 시험) |
| 깊이 입력 | `frontend.labelBandPx` 0 → 6(라벨 경계 양쪽 띠, 심 남김) | M54: P와 함께 T 경고 6.54 s·거짓 시작 3, 받침대 첫 STOP −0.045 m, 실내 출력 같음 | 0 | M56(폭 6은 M54 p90 규칙 — 요구에서 다시 유도, ③ 130646 늦춤) |
| 지도 | `map.hitWeighting` CONFIDENCE(원시 신뢰도 구간 무게 `depth.mapConfidenceWeights`) | M54: T 경고 6.01 s이나 실내 131039 누운 캐리어 STOP 놓침(6표 문턱) | DISTANCE | M55: W 문턱 5 유도(`map.weightedMinHits`) → M56(B+W5 바탕) |
| 지도 | `map.decayWeighting` CONFIDENCE(빈 곳 감쇠에 같은 무게) | M54 결과 검토(사용자 제안): 131039 놓침 그대로, 방향은 맞음(사용자) | NONE | M56 |
| 지도 | `vision.voxelLabels`(칸 라벨 표·역할, 프레임 바닥면·군집 법선 기록) | M54: 기록용, 받침대 둘레 건물 역할 2~10%, 바닥면은 앱 바닥보다 못함, 법선 안정 | false | M50(막힌 길)·M53 |
| 요소 분해 | `floor.weighting` CONFIDENCE(바닥 히스토그램 신뢰도 무게) | M54: 바닥 오차 중앙값 0.038 → 0.039 m(미충족), 실내 131003 STOP 놓침 | NONE | — |
| 지도 | `map.weightedMinHits` 6 → 5(W일 때 점유 문턱) | M55: 낮은 물체 STOP 요구에서 유도(6·5.5는 131003·135701 첫 STOP 한도 넘음). P+B+W5 실내 놓침 2·오경보 41 → 26, 3은 T 경고 10.52 s | 6 | M56(B+W5 바탕) |
| 지도 | `map.weightedFrameMinHits`, `map.weightedUndecidedAsFrame`, `vision.floorishLabels`(라벨 역할별 점유 문턱) | M55: T 경고 10.26~10.52 s | 6 / false | — |
| 요소 분해 | `floor.model` LEVEL → PLANE_RAW(`floor.planeMinConfidence`·`planeMaxTiltDeg`·`planeIterations`·`planeWindowS`) | M55: 150159 바닥 군집 37% → 0이나 131039 캐리어 STOP 놓침, 150457 바닥 군집 0 → 12%, 실내 바닥 오경보 3 → 7 | LEVEL | — |
| 요소 분해 | `floor.histogramMinDistM`·`histogramMaxDistM`(가까운 거리 바닥 히스토그램) | M55: 바닥 군집 0이나 바닥을 잃은 장 실외 44%·실내 14% | 0 / 0 | — |
| 요소 분해 | `floor.weighting` DISTANCE | M55: 150159 바닥 군집 37% → 24%, 거짓 시작 4 → 5 | NONE | — |
| 군집·추적 | `track.holdLabelPending`(라벨 판정 중인 후보를 정지 지도로 넘기지 않음) | M55: 150159 사람 2 m 안 빈 장 48% 그대로 | false | — (M56 움직임 판정이 넘김을 다시 설계) |
| 깊이 입력 | `vision.obstacleDepth` SMOOTHED → RAW(`vision.obstacleLabels`·`obstacleRawWeight`: 장애물 라벨 띠 안쪽 원시, 원시 없는 화소는 평활로 채움) | M55: T 경고 6.64 → 8.01 s·거짓 시작 4 → 5, 실내 130646 늦춤 풀림 | SMOOTHED | M56(평활을 버리는 "P처럼" 판과 비교) |
| 깊이 입력 | `frontend.bandLabels`(띠의 장애물 묶음) M54 묶음 → 하늘·물 뺌 | M55: T 결과 P+B+W5와 같음 | M54 묶음 | M56(B+W5에서 씀) |
| 평가 | 재생 `-PcellTrace`(복셀 관측 기록), `-PlabelRemap`(라벨 화소 값 바꾸기) | M55: 기록으로 돌린 지금 규칙이 31세션 14,448장 재생과 같음 / 보도 라벨 바꾸기 시험 | 꺼짐 | — |
| 깊이 입력 | `frontend.bandDepthEdgeSigmas`(깊이 경계 근처에만 띠) | M56: T 경고 6.71~7.07 s·거짓 시작 4~8, 6 px에서 131039 누운 캐리어 놓침 | 0 | — |
| 깊이 입력 | `frontend.bandWidthM`·`bandMinPx`(거리 비례 띠), `frontend.bandNearM`·`bandFarPx`(계단 띠: 1.5 m 안 6·밖 3 px) | M56: 거리 비례 T 6.81 s·6; 계단 + W6(W 다시 유도) T 6.41 s·거짓 시작 4, 먼 막 약 40% 남김 | 0 | — |
| 깊이 입력 | `vision.obstacleFill` SMOOTHED → NONE(장애물 라벨은 평활을 버리고 원시만, `vision.obstacleDepth` RAW와 함께) | M56: T 경고 8.05 s·거짓 시작 6, 실외 첫 STOP 0.05~0.27 m 이름 | SMOOTHED | 정지 장애물 원시 점 신뢰도별 오차를 잰 뒤 |
| 지도 | `map.weightedMinHits` 5 → 6(고정 6 px 띠, 신뢰도 무게) | M56: T 5.84 s·2이나 실내 늦음 131003 0.049·135701 0.050 m(한도에 걸침) | B+W5는 5 | — |
| 군집·추적 | `vision.voxelLabels`(칸 과반 라벨) + `cluster.sameLabelEpsM` 0.30·`cluster.sameLabels`(나무·구조물·물체·차량·사람끼리 더 먼 거리까지 이웃) | M56: 모든 기준 충족(150159 갈림 7 → 0, T 6.32 s·3); 모든 라벨에 주면 T 6.66 s | 끔(앱 기본값), M56 조합에서 켬 | M57 바탕 |
| 평가 | 재생 `-PlabelDump`(장마다 점유 칸 과반 라벨·군집 번호, voxel_labels.csv.gz) | M56 구현 전 점검 | — | — |
| 지도 | `map.decayBehind` | M24: 꺼진 옵션(GO는 기본값 후보) | false | M37 |
| 지도 | `map.freeEvidenceDecay`, `cluster.coreEvidenceMultiplier` (PR #58 E·C) | v0.3.19: 꺼진 옵션 | 0 / 1.0 | M35 |
| 지도 | `map.tri*`(RM, M33), `map.triViewRho` 등(RV, M39), `map.prunePassed` | M33 P·M39 P: 다음 바탕, 기본값 HITS 유지 | 끔 | M37 |
| 요소 분해 | `floor.holdWhenLost` | M18 기본값 | true | M35 |
| 요소 분해 | `floor.minBelowCameraM` 0.8 | M12.1·v0.3.14 기본값 | 0.8 | M35 |
| 요소 분해 | `cluster.separateWalls` (C2) | M13.5 뒤 꺼진 옵션 | false | M35 |
| 요소 분해 | `floor.decompose` BAND → REGION_PLANE (`floor.regionM` 2) | M35: 원시 지도 바닥 높이 오경보 늘어 F | BAND | M35 다시 설계 시(바닥 가능도 검사 넣고) |
| 군집·추적 | `track.matchByOverlap` (T1) | M13.3 기본값 | true | M35 |
| 군집·추적 | `track.reidLost` (M22 R) | M22 꺼진 옵션 | false | M35 |
| 군집·추적 | `track.smallClusterVoxels` (M25 S) | M25 꺼진 옵션 | 0 | M35 |
| 군집·추적 | `track.motion` NONE → KALMAN(정지·움직임 분해, 월드 칼만, 블록 시각 외삽·TTC STOP) | M48: 깊이 기하 움직임 판정 F(정지 물체 겉보기 속도 p99 4.47 m/s > 사람 0.34~0.68), 판정을 정답으로 바꾸면 합성 관문 SM-5 잡음 깊이만 실패, T 150338 진입 전 STOP(시작 속도 과대) | NONE | M49(비전 라벨이 판정)
| 군집·추적 | `track.measureWeight` CELLS → POINTS(칼만 측정점 아래 사분위를 깊이 점 단위로) | M52: T 접근 3세션 측정점 − 몸통 평활 깊이 0.159·0.216·0.115 → 0.025·0.081·0.060 m(움직임 정답 판정) | CELLS | M49(라벨 판정과 함께) |
| 군집·추적 | `track.depthLagMs` 0 → 345(평활 깊이 뒤처짐만큼 측정 시각을 앞당김, SMOOTHED만) | M52: P4 합친 기울기 0.345 s[0.005, 0.883], 점 단위와 함께 칼만 위치 − 새 정답 앞면 +0.063·+0.166·+0.252 → −0.067·+0.029·−0.069 m(시작 게이트까지 켜면 150338 +0.157). 임시: 결과 검토 뒤 원시 깊이로 재면 보정 없이 −0.054·+0.078·+0.076 m라 쓰지 않을 예정(원시 측정으로 대체) | 0 | M49(원시 측정과 비교 뒤 지움 여부) |
| 군집·추적 | `track.initMaxSpeedMps` 0 → 2.5, `track.initGateSigmas` 3(출력 전 시작 게이트, 넘으면 다시 시작) | M52: T 150338 첫 출력 속도 4.53 → 0.41 m/s(정답 0.49) | 0 | M49 |
| 군집·추적 | `vision.label` NONE → SEMANTICS(track마다 라벨 표, 지도 HITS 상수, 가시성 검사 = 칸이 가장 최근 원시 장에서 보이는 표면일 때만), `vision.movingLabels` [PERSON, VEHICLE], `vision.maxLabelAgeMs` 200 | M49: T 판정 늦음 0.1·−4.1·−0.93 s(150159 2.1 s 미충족), 사람이 아닌 자리의 움직이는 track 받침대 0·150338 2, 정답 없는 경고 7.40 s(M52 6.75)·거짓 시작 5, 150338 STOP 1.62 m·TTC 1.35 s. 합성 SM-1~SM-7 통과 | NONE | M50(S1 영향부터) |
| 군집·추적 | `track.measureFrom` CELLS → RAW_MASK(움직이는 track·후보의 칼만 = 새 원시 장의 라벨 마스크 점, 원시 시각, 첫 원시 측정에서 다시 시작, 창 = 마스크 점 범위), `track.rawMinConfidence` 1 | M49: 측정점 − 몸통 원시 0.015·0.020·0.017 m, 칼만 − 정답 앞면 −0.057·+0.070·+0.078 m(시각 보정 없이 충족), 첫 출력 속도 오차 1.40·0.60·0.03 m/s(미충족) | CELLS | M50 |
| 군집·추적 | `vision.maxSpeedMps` {PERSON 2.5, VEHICLE 6.9}(RAW_MASK 시작 게이트를 마스크 점 다수 라벨로) | M49: 6.9 하나면 원시 진입 첫 부분 측정 뜀 통과(150338 첫 속도 2.66 → 0.72 m/s, 정답 0.75) | (값) | M50 |
| 군집·추적 | `falsePositiveFilter.*`, `geometry.*` | 팀 코드, 판정 기록 없음 | 끔 | M35(팀과 상의) |
| 진행 방향 | `heading.windowS` 1.0 → 2.0 | M18 기본값(문헌 값) | 2.0 | — |
| 진행 방향 | `heading.cameraSigmaDeg` 0 → 2.3 (가중 결합) | M42 | 0 | 기본값 후보 아님(사용자, 2026-10-31): 팀원 회전 개선 뒤 비교 |
| 통로 판정 | `corridor.edgeOutsideM` 0 → 0.15 (M22 W) | M22 꺼진 옵션 | 0 | M44 |
| 통로 판정 | `corridor.cutEnd` (M25 E) | M25 꺼진 옵션 | false | M44 |
| 통로 판정 | `corridor.wallAlign` (M42 C) | M42 이전 판 확인 재생: 오경보 늘어 채택 안 함 | false | M44 |
| 통로 판정 | `corridor.judge` BOX → WALKABLE | M44: 옆 벽 오경보 안 줄고 합성 STOP 검출 −0.14로 F | BOX | M38 녹화에서 진행 방향 결합과 함께(M36) |
| 통로 판정 | `corridor.judge` BOX → BOUNDARY (나란한 경계) | M44 재설계: 기준 충족(P) | BOX | M36 기본값 후보 |
| 통로 판정 | `corridor.judge` BOX → LABEL(건물 칸만 나란한 경계) | M54 SL-7: 얕게 다가오는 건물 벽 첫 STOP 2.0 s 늦음(M44 결함, BOUNDARY에도) | BOX | 경계 결함 고친 뒤(M50) |
| 안내 | `repPoint.strategy` CORRIDOR_NEAREST → CORRIDOR_BAND | M21 기본값(평활 깊이에서 방향 불합격 7/15 → 4/15) | CORRIDOR_BAND | M42 |
| 안내 | `repPoint.stableTies` false → true | M21 보완(2026-10-30): 같은 거리 후보의 입력 순서 의존 재현 | false | 높이·피치 청취와 회전 재현 후, 기본값 후보 아님 |
| 안내 | `policy.prioritizeStop`, `policy.maxSources` | 팀 소리 후보(IMPROVE_SPEC §15) | false / 1 | M42, 소리는 M36 |

## 재판정 기록

(마일스톤마다 한 줄씩 더한다: 날짜 · 마일스톤 · 키 · 앞 단계 정답 조건 · 결과 · 처리)
- 2026-10-27 · M42 · `heading.cameraSigmaDeg` 2.3 · 앞 단계 정답 D(깊이) · 5° 넘는 블록 걷기 시작·돌아선 뒤 0.233 → 0.158, 걷지 않음 0.198 → 0.096, 정상 걷기 0.036 → 0.039 · 꺼진 옵션, 기본값은 M36
- 2026-10-27 · M42 · `repPoint.strategy` CORRIDOR_BAND vs CORRIDOR_NEAREST · D + H(E 6세션)에서는 방위 지표가 정의되지 않고 거리·오경보 같음. 평활 깊이 물체 세션에서 BAND 방위 오차 p50 2.07° vs 3.81° · 보류(물체 세션 D + H 재생 안 함), 기본 유지
- 2026-10-27 · M42 · `policy.prioritizeStop` · 무작위 합성 s = 0, maxSources 1에서 안내 출력이 다른 블록 0 / 224,305 · 기본 설정에서 효과 없음, maxSources 2는 M36(팀)
- 2026-10-27 · M35 · `floor.decompose` REGION_PLANE · 앞 단계 정답 없음(원시 지도 RV, E 6세션) · 바닥 높이 오경보 10 m당 2.08 → 2.97, 낮은 군집 깜빡임 분당 92.1 → 102.2, 물체 세션 STOP 누락 0 → 1(130834) · F, 꺼진 옵션으로 둠
- 2026-10-27 · M35 · `frontend.planes`·`floor.holdWhenLost`·`floor.minBelowCameraM`·`cluster.separateWalls`·군집·추적 옵션 · 재판정 안 함(M35를 영역별 바닥면 하나로 줄임) · 재판정 마일스톤 그대로 M35(다시 설계 시)
- 2026-10-27 · M44 · `corridor.judge` WALKABLE · 앞 단계 정답 D(깊이), 진행 방향은 앱 · 옆 벽 오경보 시작 17 → 17, 합성 마주침 STOP 검출 −0.14, 물체 세션 누락 증가 0 · F, 꺼진 옵션
- 2026-10-27 · M44 · `corridor.edgeOutsideM`(M22 W)·`corridor.cutEnd`(M25 E)·`corridor.wallAlign`(M42 C) · 재판정 안 함(M44를 단면 판정 하나로 줄임) · 재판정 마일스톤 M36
- 2026-10-27 · M44 · `corridor.judge` BOUNDARY · 앞 단계 정답 D(깊이), 진행 방향은 결합(F) · 옆 벽 오경보 시작 14 → 1, 합성 마주침 STOP 검출 +0.000·+0.010, 물체 세션 누락 증가 0 · P, 꺼진 옵션, 기본값은 M36
- 2026-10-31 · M37 · 학습형 깊이 ZipDepth 대체(`depthFrom=zip_ga/full`) · 앞 단계 정답 없음(원시 RV + 결합 + 나란한 경계, E 6세션·물체 10세션) · 지도 헛 칸 0.315 → 0.280(나음), 표면 오차·완전성 비슷. 오경보 합 10 → 9이나 152354 1 → 2, 물체 STOP 누락 131039 0 → 1 · F, 재생 전용으로 남김
- 2026-10-31 · M37 · MiDaS small 대체·채움, MiDaS + SML · 같은 조건 · MiDaS 대체: 헛 칸 +0.024·완전성 −0.055(나쁨), 채움: 평활 채움보다 헛 칸 +0.006(나쁨). SML은 기기 283.7 ms > 226 · 버림(도구·측정값은 남김)
- 2026-10-31 · M37 · `frontend.enabled`(경계 판정) · 원시 RV 바탕(같은 하류) · 오경보 합 10 → 10, 152743 2 → 3, 물체 세션 결과 같음 · F, 꺼진 옵션
- 2026-10-31 · M37 · `frontend.rgbGuide` WEIGHTED_MEDIAN·EDGE·EDGE_MARGIN(`rgbFrom=mp4`) · 원시 RV 바탕 · 오경보 합 10 → 11(152354 1 → 2, 152743 2 → 3), 물체 세션 결과 같음. 3상태 지도에서는 바닥 추정기가 돌지 않아 EDGE·EDGE_MARGIN이 WEIGHTED_MEDIAN과 같은 출력 · F, 꺼진 옵션
- 2026-10-31 · M37 · 공정 비교(순수 대체 `fullonly` 대 같은 장 원시 `rawonly`, 같은 원시 σ) · E 6세션 · ZipDepth 헛 칸 0.316 → 0.244·표면 오차 0.174 → 0.139 m(나음), 오경보 10 → 16 · F 그대로. MiDaS·SML 완전성 0.349 → 0.195·0.191 · 버림 그대로
- 2026-11-06 · M54 · `vision.movableDepth` RAW + `frontend.labelBandPx` 6 · 앞 단계 정답 없음(M49 끝 설정 위, T 7·O01 9·실내 21) · T 정답 없는 경고 7.40 → 6.54 s·거짓 시작 5 → 3, 150338 STOP 1.62 → 1.66 m, 받침대 첫 STOP 1.318 → 1.273 m, O01·실내 출력 같음 · 꺼진 옵션, 기본값 후보(M51 H·기기)
- 2026-11-06 · M54 · `map.hitWeighting` CONFIDENCE, `map.decayWeighting` CONFIDENCE, `floor.weighting` CONFIDENCE · 같은 조건 · T 경고 6.01 s(W)이나 실내 STOP 놓침 2 → 4(131039 W·감쇠 무게로도 그대로, 131003 F) · 꺼진 옵션, 점유 판정은 M55
- 2026-11-06 · M54 · `corridor.judge` LABEL · 합성 SL-7 · 첫 STOP BOX 3.17 s 대 5.18 s · 꺼진 옵션, BOUNDARY·LABEL 기본값 금지(경계 결함 고칠 때까지)
- 2026-11-06 · M55 · 증거 합 점유 규칙(오프라인, 복셀 관측 기록) · E 10 + K01 8 · 헛 점유 1.87M → 2.57M 칸·장(95% [+0.29M, +1.17M]), 요구 유도 A 11.5에서도 +6% · 채택 안 함(장마다 독립 전제가 틀림), 문턱 6은 낮은 물체 STOP 요구의 상한
- 2026-11-07 · M55 · `map.weightedMinHits` 5 + `vision.movableDepth` RAW + `frontend.labelBandPx` 6(P+B+W5) · 실내 21 라벨 저장 뒤, T 7·O01 9 · 실내 놓침 2·오경보 41 → 26·130646 한도 넘음, T 6.64 s·거짓 시작 4 · 조합 후보
- 2026-11-07 · M55 · `floor.model` PLANE_RAW·`floor.histogramMinDistM`/`MaxDistM`·`floor.weighting` DISTANCE · P+B+W5 위 · 위 표 · 꺼진 옵션, ①은 150159 녹화 깊이 결함으로 닫음(사용자)
- 2026-11-07 · M55 · `vision.obstacleDepth` RAW(평활 채움) · P·O 끈 B+W5와 비교 · T 6.32 → 8.01 s(첫 구현은 원시에 띠를 안 깎아 10.68 s) · 꺼진 옵션. 장애물 라벨 전체를 P처럼(평활 버림) 다루는 판은 시험 안 함 → M56
- 2026-11-07 · M55 · B+W5(`vision.movableDepth` SMOOTHED, 띠 6 하늘·물 뺌, `map.weightedMinHits` 5) · 실내 21·T 7·O01 9 · 실내 놓침 2·오경보 26·130646 한도 넘음, T 6.32 s·거짓 시작 3(기준 충족), 150338 1.62 m·TTC 1.35 s, 사람 2 m 안 빈 장 22%·3 m 안 출력 둘 이상 150300 11%, O01 같음 · M56 판정 바탕
- 2026-11-07 · M56 · `frontend.bandDepthEdgeSigmas`·`bandWidthM`·`bandNearM`(경계 띠 변형 셋) · B+W5 위, 계단 띠는 W 문턱을 같은 규칙으로 다시 유도, 늦음 = 첫 STOP 시각 차 × 걷는 속도(자세 튐 뺌) · 위 표 · 꺼진 옵션, 앞단은 고정 6 px + W5(사용자 결정)
- 2026-11-08 · M56 · `vision.obstacleFill` NONE · B+W5 위, 원시 무게 1 · T 8.05 s·거짓 시작 6, 흔적 47 → 50 · 꺼진 옵션
- 2026-11-08 · M56 · `vision.voxelLabels` + `cluster.sameLabelEpsM` 0.30 + `cluster.sameLabels` · B+W5 위(`map.weightedFrameMinHits` 5) · 위 표, 새 옵션을 끈 출력 바이트 같음 · M56 조합(뷰어 변형 39), M57 바탕
- 2026-11-08 · M57 · `track.motionMap` OWNED + `gateLagS` 0.345 + `shadowDepthM` 2.93(복셀 지도 하나) · M56 조합 위, M49 라벨 판정·정답 판정 따로 · 150300 한 사람 갈림 8 → 0, 흔적 45 → 16(정답 판정 9), T 6.32 → 5.78 s·3, 안전·실내·O01 같음 · M57 조합에 넣음
- 2026-11-08 · M57 · `track.motionEvidence` FREE_SPACE(빈 곳 위반 라벨 길·기하 길, 값 data/sweeps/m57/s2/judge2.json) · 한 지도 위(변형 j1) · 정지 거짓 판정 0(라벨 판정 1), 접근 150300 0.63 / 마감 0.54 s(라벨 판정 0.80)·150439 0.13 s(라벨 판정 판정 안 됨), 흔적 11, 2 m 안 빈 장 15 → 7%, T 5.81 s·3, 150338 1.79 m·TTC 2.04 s · M57 조합(뷰어 변형 41)
- 2026-11-08 · M57 · `track.objectMinHits` 5 + `track.candidateOutput`(후보 문턱 지도와 같게·판정 전 출력) · j1 위(j2) · 실내 오경보 시작 26 → 34, T 5.81 → 6.55 s·거짓 시작 3 → 5, 150159 한 사람 갈림 1 → 22장, 2 m 안 빈 장 7 → 6% · 꺼진 옵션(채택 안 함)
- 2026-11-08 · M57 · `track.measureLabels` false(측정 점 라벨 거름 대신 정지 칸 점 빼기) · j2 위(j3) · 정지 거짓 판정 0 → 1(150457), 150338 STOP 1.94 → 1.06 m, T 6.60 s·6 · 꺼진 옵션(채택 안 함)
