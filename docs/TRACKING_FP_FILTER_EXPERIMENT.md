# Tracking / False Positive 보완 실험

## 목적

현재 공간 인식 파이프라인에서 두 가지 문제를 분리해서 다룬다.

1. Depth/군집이 한 번 빠질 때 물체 ID가 즉시 끊기는 문제
2. 빈 복도에서 통로 전체를 채우는 depth sheet 형태의 군집이 장애물로 남는 문제

## Tracking v2

- 새 detection은 `track.minConfirmObservations`회 연속 관측 후에만 `Obstacle`로 출력
- detection이 잠깐 사라져도 `track.maxMissedUpdates`회 동안 내부 ID 유지
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

## 로그

M7 실시간/재생 파이프라인의 `RunLogger`가 `cluster_debug.csv`를 추가로 기록한다.

주요 열:

- `nVoxels`
- `lateralSpanM`
- `alongMinM`, `alongSpanM`
- `heightMinM`, `heightMaxM`, `heightSpanM`
- voxel score/hits
- `filtered`, `filterReason`

이 로그로 빈 복도 false positive와 실제 장애물 cluster를 같은 기준으로 비교한다.
