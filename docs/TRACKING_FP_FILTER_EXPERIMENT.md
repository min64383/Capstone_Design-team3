# Tracking / False Positive 보완 실험

## 목적

현재 공간 인식 파이프라인에서 두 가지 문제를 분리해서 다룬다.

1. Depth/군집이 한 번 빠질 때 물체 ID가 즉시 끊기는 문제
2. 빈 복도에서 통로 전체를 채우는 depth sheet 형태의 군집이 장애물로 남는 문제

## Tracking v2

- 새 detection은 `track.minConfirmObservations`회 연속 관측 후에만 `Obstacle`로 출력
- detection이 잠깐 사라져도 `track.maxMissedUpdates`회 동안 내부 ID 유지
- 한 번 확인된 track은 다시 잡히면 바로 출력(리뷰 반영: 재확인 대기로 음원이 끊기던 문제)
- missed 상태에서는 오래된 위치를 `Obstacle`로 출력하지 않음
- 매칭 방식은 기존의 CENTROID 거리 + `matchRadiusM` 유지

기본값:

```json
"track": {
  "matchRadiusM": 0.3,
  "emaAlpha": 0.3,
  "minConfirmObservations": 2,
  "maxMissedUpdates": 3
}
```

## False Positive 필터

빈 복도 실측에서 반복된 큰 depth sheet 후보를 찾는 실험 필터다.

조건을 모두 만족할 때만 제거 후보로 본다.

- voxel 수 >= 500
- 좌우 span >= 0.60 m
- 진행 방향 span >= 0.80 m
- 가장 가까운 경계 >= 1.00 m
- 높이 span >= 1.40 m
- 바닥 근처(`heightMin <= 0.20 m`)부터 머리 높이(`heightMax >= 1.70 m`)까지 이어짐

근거리 실제 장애물을 휴리스틱으로 지우지 않도록 `alongMin < 1.0 m`이면 필터하지 않는다.

현재 기본값은 `enabled: false`. 실제 장애물 종류를 더 수집한 뒤 활성화 여부를 결정한다.

### 오프라인 재생 측정 (2026-09-30, 리뷰)

`./gradlew :core:replay`로 같은 녹화를 같은 순서로 재생(재현 가능).

| 녹화 | 조건 | 안내 중 경고 비율 | 첫 WARN / 첫 STOP |
|---|---|---|---|
| 빈 복도 190833_S01 | 병합 전 main | 0.61 | 5.09 / 7.32 s |
| | 추적 v2, 필터 꺼짐 | 0.58 | 5.13 / 7.35 s |
| | 추적 v2, 필터 켜짐 | 0.46 | 5.13 / 7.35 s |
| 캐리어 190936_S01, S02 084542 | 세 조건 | 1.00 | 거의 같음 |

- 빈 복도의 경고는 일부가 왼쪽 벽 옆 실제 작은 상자, 일부가 끝 문 앞 약 1.1 m 두께의 깊이 덩어리(광택 바닥 반사로 추정)다.
- **안전 주의**: 필터 조건(통로 폭 전체·앞뒤 0.8 m 이상·바닥~1.7 m·1 m 밖)은 닫힌 문, 붙박이장, 실외 차량·담장 같은 실제 장애물과도 맞는다. 켜면 이런 물체는 1 m 안에 들어올 때까지 WARN 없이 조용하다 바로 STOP이 된다. 정답 녹화로 누락을 측정하기 전에는 켜지 않는다(명세 §16: 실내 전용 가정 금지).

## 확인 조건 강화 (2026-10-05, PR #30 재작업)

팀원 PR #30(오래된 main 기준이라 충돌)의 새 부분만 현재 추적 v2 위에 옮겼다. 확인 전 track에만 적용되고, 확인된 track은 그대로다.

- `track.minConfirmConfidence`: confidence(군집 복셀 score 평균)가 이보다 낮은 관측은 연속 횟수를 0으로 만든다(세지 않음)
- `track.maxConfirmCentroidJumpM`: 이전 중심점(EMA)에서 이보다 튄 관측은 연속 횟수를 1부터 다시 센다(id는 유지)
- `track.suspiciousConfirmObservations`: 위 False Positive 필터 조건과 같은 모양(`FalsePositiveFilter.matches`, 필터가 꺼져도 판정)이었던 track은 이 횟수가 필요하다. 지우지 않고 더 오래 확인한다

기본값 `0` / `0.3`(= `matchRadiusM`) / `2`(= `minConfirmObservations`)는 끈 것과 같다. 오프라인 재생 10세션에서 `obstacles.csv`·`guidance.csv`·`cluster_debug.csv`가 main과 바이트 단위로 같았다.

### 오프라인 재생 비교 (`sweeps/track_confirm.json`, 정답 있는 10세션)

`pr30_all` = PR 값(`minConfirmObservations` 3, `suspiciousConfirmObservations` 4, `minConfirmConfidence` 0.15, `maxConfirmCentroidJumpM` 0.2).

| 장면 | 변형 | 경고 비율 | 오경보 비율 | 첫 경고 거리(m) | 첫 STOP 거리(m) | STOP 누락 |
|---|---|---|---|---|---|---|
| S01 빈 복도(1) | default | 0.581 | 0.779 | door 2.62 | door 1.26 | 0 |
| | pr30_all | 0.560 | 0.759 | door 2.62 | door 1.26 | 0 |
| | confirm_3 | 0.566 | 0.769 | 같음 | 같음 | 0 |
| | suspicious_4 | 0.575 | 0.777 | 같음 | 같음 | 0 |
| S02(4) | default | 0.989 | 0.068 | paper_box 1.57, suitcase 2.38 | paper_box 1.04, suitcase 1.07 | 1 |
| | pr30_all | 0.973 | 0.061 | 같음 | **paper_box 0.989**, suitcase 1.07 | 1 |
| S03(3) | default / pr30_all | 0.978 / 0.955 | 0.080 / 0.080 | 같음 | 같음 | 0 |
| S07(2) | default / pr30_all | 0.481 / 0.467 | 0.142 / 0.131 | suitcase_lying 2.06 / 2.04 | 같음 | 0 |

- 효과는 대부분 `minConfirmObservations` 2 → 3에서 나온다. 빈 복도 경고 비율 −2%p, 오경보 비율 −2%p 정도로 작다.
- 대가: S02 종이박스 첫 STOP이 1.04 → 0.99 m(`policy.stopM` 1.0 안쪽)로 늦어지고, S07 첫 경고가 2 cm 늦다. 새 STOP 누락은 없다.
- `suspicious_4`·`confidence_0.15`·`jump_0.2` 단독 효과는 거의 없다(이 세트에서 depth sheet 모양 군집이 확인 전에 드묾).
- 기본값은 기준선 유지. 바꾸려면 M12 평가 세트로 다시 비교한다(IMPROVE_SPEC §15-3).

## 로그

앱(`RunLogger`)과 PC 오프라인 재생(`OfflineReplay`)이 같은 형식의 `cluster_debug.csv`(`RunLog.CLUSTER_DEBUG_FILE`)를 기록한다.

주요 열:

- `nVoxels`
- `lateralSpanM`
- `alongMinM`, `alongSpanM`
- `heightMinM`, `heightMaxM`, `heightSpanM`
- voxel score/hits
- `filtered`, `filterReason`

이 로그로 빈 복도 false positive와 실제 장애물 cluster를 같은 기준으로 비교한다.
