# 옵션 장부 (M43)

파이프라인 단계마다 남아 있는 옵션과 그 판정 기록이다. 옵션은 재판정 전에 지우지 않는다(C1 판정 방식, DECISIONS 2026-10-27). 재판정은 그 단계를 맡는 마일스톤에서, **앞 단계를 정답으로 바꿔 넣은 재생**과 그 단계의 지표로 한다. 결과는 이 표에 한 줄로 더한다.

단계 순서: 깊이 입력 → 지도 → 요소 분해(바닥) → 군집·추적 → 진행 방향 → 통로 판정 → 안내.

| 단계 | 키 (기본 → 옵션) | 처음 판정 | 지금 기본 | 재판정 |
|---|---|---|---|---|
| 깊이 입력 | `depth.source` SMOOTHED → RAW | M3(F6 비교)·M32(원시 3상태 지도) | SMOOTHED | M37(조건부) |
| 깊이 입력 | `frontend.enabled` 경계 판정 | M13.1a: 기기 확인 전 꺼짐 | false | M37 |
| 깊이 입력 | `frontend.planes` NONE → RANSAC, `floor.source` PLANE | M13.1c: 기본값 아님 | NONE / HISTOGRAM | M35(바닥) |
| 깊이 입력 | `frontend.rgbGuide` NONE → G·EDGE·EDGE_MARGIN | M13.7·M19(G 상자 모양만 개선)·M23(Ra·Rb 확인용 STOP 누락) | NONE | M37 |
| 깊이 입력 | `frontend.segment` NONE → REGION, `map.instances` | M13.3(C3a·C3b) | NONE / false | M35 |
| 지도 | `map.mode` HITS → LOG_ODDS·TSDF·TRISTATE | M13.2·M13.6·M32 | HITS | M37 |
| 지도 | `map.hitWeighting` DISTANCE | v0.3.14 기본값 | DISTANCE | M37 |
| 지도 | `map.decayBehind` | M24: 꺼진 옵션(GO는 기본값 후보) | false | M37 |
| 지도 | `map.freeEvidenceDecay`, `cluster.coreEvidenceMultiplier` (PR #58 E·C) | v0.3.19: 꺼진 옵션 | 0 / 1.0 | M35 |
| 지도 | `map.tri*`(RM, M33), `map.triViewRho` 등(RV, M39), `map.prunePassed` | M33 P·M39 P: 다음 바탕, 기본값 HITS 유지 | 끔 | M37 |
| 요소 분해 | `floor.holdWhenLost` | M18 기본값 | true | M35 |
| 요소 분해 | `floor.minBelowCameraM` 0.8 | M12.1·v0.3.14 기본값 | 0.8 | M35 |
| 요소 분해 | `cluster.separateWalls` (C2) | M13.5 뒤 꺼진 옵션 | false | M35 |
| 군집·추적 | `track.matchByOverlap` (T1) | M13.3 기본값 | true | M35 |
| 군집·추적 | `track.reidLost` (M22 R) | M22 꺼진 옵션 | false | M35 |
| 군집·추적 | `track.smallClusterVoxels` (M25 S) | M25 꺼진 옵션 | 0 | M35 |
| 군집·추적 | `falsePositiveFilter.*`, `geometry.*` | 팀 코드, 판정 기록 없음 | 끔 | M35(팀과 상의) |
| 진행 방향 | `heading.windowS` 1.0 → 2.0 | M18 기본값(문헌 값) | 2.0 | — |
| 진행 방향 | `heading.cameraSigmaDeg` 0 → 2.3 (가중 결합) | M42 | 0 | M42 결과 |
| 통로 판정 | `corridor.edgeOutsideM` 0 → 0.15 (M22 W) | M22 꺼진 옵션 | 0 | M44 |
| 통로 판정 | `corridor.cutEnd` (M25 E) | M25 꺼진 옵션 | false | M44 |
| 통로 판정 | `corridor.wallAlign` (M42 C) | M42 이전 판 확인 재생: 오경보 늘어 채택 안 함 | false | M44 |
| 안내 | `repPoint.strategy` CORRIDOR_NEAREST → CORRIDOR_BAND | M21 기본값(평활 깊이에서 방향 불합격 7/15 → 4/15) | CORRIDOR_BAND | M42 |
| 안내 | `policy.prioritizeStop`, `policy.maxSources` | 팀 소리 후보(IMPROVE_SPEC §15) | false / 1 | M42, 소리는 M36 |

## 재판정 기록

(마일스톤마다 한 줄씩 더한다: 날짜 · 마일스톤 · 키 · 앞 단계 정답 조건 · 결과 · 처리)
- 2026-10-27 · M42 · `heading.cameraSigmaDeg` 2.3 · 앞 단계 정답 D(깊이) · 5° 넘는 블록 걷기 시작·돌아선 뒤 0.233 → 0.158, 걷지 않음 0.198 → 0.096, 정상 걷기 0.036 → 0.039 · 꺼진 옵션, 기본값은 M36
- 2026-10-27 · M42 · `repPoint.strategy` CORRIDOR_BAND vs CORRIDOR_NEAREST · D + H(E 6세션)에서는 방위 지표가 정의되지 않고 거리·오경보 같음. 평활 깊이 물체 세션에서 BAND 방위 오차 p50 2.07° vs 3.81° · 보류(물체 세션 D + H 재생 안 함), 기본 유지
- 2026-10-27 · M42 · `policy.prioritizeStop` · 무작위 합성 s = 0, maxSources 1에서 안내 출력이 다른 블록 0 / 224,305 · 기본 설정에서 효과 없음, maxSources 2는 M36(팀)
