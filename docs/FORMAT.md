# 녹화 세션 형식과 스파이크 결과

MVP_SPEC §8. 형식 **v0**은 M1 스파이크용 초안이고, F1~F8 결과로 G1에서 **v1**을 확정한다.
형식 정의 코드는 `core/session/SessionFormat.kt`, `SessionMeta.kt`, `Png16.kt`에 있고, 앱과 PC에서 같은 코드를 쓴다.

## 형식 v0

```
<getExternalFilesDir>/sessions/<yyyyMMdd_HHmmss>_<장면ID>/
├── meta.json        기기·ARCore 버전·깊이 지원·내부 파라미터·규약·파지 오프셋·통계(종료 시)
├── arcore.mp4       ARCore Recording API 출력 (재생 모드 입력)
├── frames.csv       ARCore 프레임마다 1행 (저장이 밀려도 행은 빠지지 않음)
├── device.csv       발열·배터리 (record.deviceLogIntervalS 간격)
├── depth/NNNNNN.png       일반 깊이 uint16 mm, 16비트 흑백 PNG, 0 = 무효
├── raw_depth/NNNNNN.png   원시 깊이 uint16 mm (v0 추가, F6 비교용)
├── depth_conf/NNNNNN.png  원시 깊이 신뢰도 uint8 (원시 깊이와 같은 프레임)
└── rgb/NNNNNN.jpg         CPU 이미지(센서 방향, 회전 안 함) JPEG
```

`NNNNNN` = `frameIndex`(녹화 시작 후 ARCore 프레임 순번, 6자리 0 채움).

### frames.csv 열

| 열 | 내용 |
|---|---|
| `frameIndex` | 0부터 |
| `tNs` | `Frame.getTimestamp()` (시간 기준은 ARCore가 정의하지 않음 → F5) |
| `sysElapsedNs` | GL 스레드가 프레임을 받은 시각의 `SystemClock.elapsedRealtimeNanos()` (v0 추가) |
| `tracking`, `trackingFailure` | `TRACKING/PAUSED/STOPPED`, `TrackingFailureReason` 이름 |
| `tx..qw` | `Camera.getPose()` 원본(물리 카메라, ARCore GL 규약) |
| `dtx..dqw` | `Camera.getDisplayOrientedPose()` 원본 (v0 추가, F2 확인용) |
| `depthTNs`, `depthFile` | 이 프레임에서 얻은 일반 깊이의 시각(저장 여부와 무관), 저장했으면 파일 |
| `rawDepthTNs`, `rawDepthFile`, `confFile` | 원시 깊이 시각과 파일, 신뢰도 파일 |
| `rgbFile` | 저장했으면 파일 |

- 일반 깊이는 **새 시각의 이미지만** 저장한다(같은 깊이의 재투영 반복 제외). `record.depthEveryN`은 새 깊이 N개마다 1개.
- 원시 깊이는 새 시각마다 저장한다. RGB는 `record.rgbEveryN` 프레임마다.
- 파일 작업은 슬롯 1개로 저장 스레드에 넘긴다. 슬롯이 차 있으면 그 프레임의 파일은 건너뛰고 행에 파일 이름을 쓰지 않는다. **행에 적힌 파일은 모두 존재한다**(쓰기 오류는 `stats.nWriteErrors`).

### PC로 가져오기

```powershell
adb pull /storage/emulated/0/Android/data/walkassist.app/files/sessions/<세션ID> data/sessions/
.venv\Scripts\python.exe tools/analysis/spike_check.py data/sessions/<세션ID>
```

## 스모크 테스트에서 발견·수정한 것 (2026-09-26)

- 어두운 방(추적 `INSUFFICIENT_LIGHT`)에서 10초 녹화 3회: 저장 경로, `adb pull`, `spike_check.py`까지 동작 확인. 깊이 경로는 추적이 안 되어 **아직 기기에서 검증하지 못함**.
- 녹화 중 fps가 8~17로 떨어짐 → GL 스레드 계측(`gl timing` 로그) 결과 CPU 이미지 NV21 복사가 픽셀 단위 `ByteBuffer.get`으로 최대 150~190 ms. 행 단위 일괄 복사로 바꿔 녹화 처리 평균 2.5 ms, 29.5 fps 유지.
- MP4 크기 약 2 MB/s(10초 20 MB) → 10분이면 약 1.2 GB. F7에서 확인.

## 스파이크 결과 (M1)

기기: SM-G977N (Galaxy S10 5G, Exynos 9820, Android 12)

| # | 항목 | 결과 | 근거 세션 |
|---|---|---|---|
| F1 | Depth 모드 지원 | **합격**: `isDepthModeSupported` AUTOMATIC=true, RAW_DEPTH_ONLY=true → AUTOMATIC 사용. ARCore APK 1.56.262080393 (공식 목록: "Depth API 지원, ToF 센서") | 20260926_045547_S01 (스모크) |
| F2 | 자세 규약·종류 | (측정 대기) | |
| F3 | CPU 이미지 해상도·방향, 내부 파라미터 | (예비) CPU 640x480 가로(세로 파지여도 센서 방향), fx≈fy≈494.4, cx 317.2, cy 234.2. GPU 텍스처 1920x1080. fps 30~30. 저장 JPEG 크기 = K 크기 일치. 방향은 S01·S02로 확인 | 스모크 |
| F4 | 깊이 해상도·비율, 깊이 내부 파라미터 환산 | (측정 대기) | |
| F5 | 깊이 타임스탬프·갱신 빈도 | (예비) 프레임 약 30 Hz. `sysElapsedNs − tNs`가 녹화하지 않을 때도 약 175~195 ms로 일정 → 시간 기준 차이인지 실제 지연인지 미확정. 깊이는 어두워 추적 실패로 미측정 | 스모크 |
| F6 | 일반 vs 원시 깊이 | (측정 대기) | |
| F7 | 저장 처리량·발열 (10분) | (측정 대기) | |
| F8 | 재생 모드 동작 | M7 | |
| F9 | 에뮬레이터 재생 (선택) | 미착수 | |

### 촬영 절차

1. **S01·S02**: 부록 A 공통 조건대로 녹화(시작 전 2~3초 정지). 각 1회 이상.
2. **F2 회전 확인**: 제자리에서 폰을 세로로 든 채 ① 정면 ② 오른쪽으로 90° 몸 회전 ③ 폰을 앞으로 기울임, 각 3초 정지하며 녹화(장면 ID는 S01로 두고 메모).
3. **F4 평면 거리**: 벽에서 줄자로 1.00 m, 2.00 m 떨어져 카메라가 벽에 수직이 되게 각 5초 녹화. `spike_check.py --roi 0.1`의 중앙 깊이와 비교.
4. **F7**: T01로 10분 연속 녹화.
