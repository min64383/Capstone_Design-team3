package hearspace.core.types

import hearspace.core.geometry.Vec3

// 설정 (MVP_SPEC §12). 기본값은 코드에 두지 않는다. 유일한 원본은 app/src/main/assets/config/default.json이다.

/** 전체 설정. [ConfigLoader]로만 만든다. */
data class Config(
    val head: HeadConfig,
    val heading: HeadingConfig,
    val corridor: CorridorConfig,
    val depth: DepthConfig,
    val map: MapConfig,
    val floor: FloorConfig,
    val cluster: ClusterConfig,
    val segment: SegmentConfig,
    val falsePositiveFilter: FalsePositiveFilterConfig,
    val track: TrackConfig,
    val repPoint: RepPointConfig,
    val policy: PolicyConfig,
    val state: StateConfig,
    val audio: AudioConfig,
    val haptics: HapticsConfig,
    val record: RecordConfig,
    val align: AlignConfig,
    val sonify: SonifyConfig,
)

/** 머리 좌표계 설정. */
data class HeadConfig(
    /** 카메라 → 머리 원점 오프셋(월드 수평 기준, 미터). 기준 파지에서 실측. */
    val offsetFromCameraM: Vec3,
)

/** 진행 방향 추정 설정. */
data class HeadingConfig(val windowS: Float, val minTravelM: Float)

/** 통로 치수와 가장자리 구조물 판정. */
data class CorridorConfig(
    val widthM: Float,
    val heightM: Float,
    val lengthM: Float,
    val behindM: Float,
    /** 물체의 모든 점이 |좌우| ≥ 이 값인 한쪽 가장자리에 있고 */
    val edgeInnerM: Float,
    /** 진행 방향으로 이 길이 이상 이어지면 나란한 가장자리 구조물(벽·담장·난간)로 보고 안내하지 않는다(v0.2.5). */
    val edgeMinLengthM: Float,
)

/** 깊이 입력·역투영 설정. */
data class DepthConfig(
    /** 역투영 픽셀 간격(1 = 모든 픽셀). */
    val subsample: Int,
    /** 느린 경로 입력 깊이 종류(F6, M3 비교로 결정). */
    val source: DepthSource,
    /** 원시 깊이 신뢰도(0~255)가 이보다 낮은 픽셀은 무효로 본다. 일반 깊이에는 적용하지 않는다. */
    val minConfidence: Int,
    /**
     * 그림자 거르기(IMPROVE_SPEC §6.1, M12): 비바닥 점 주변 이 반경(깊이 픽셀) 안에 깊이가 `1 − shadowRatio`배보다
     * 가까운 비바닥 점이 있으면, 그 점은 앞 물체의 그림자(평활 깊이가 앞·뒤 사이를 메운 가짜 면)로 보고 맵에 넣지 않는다. 0이면 끔.
     */
    val shadowRadiusPx: Int,
    val shadowRatio: Float,
)

/** 로컬 복셀 맵 설정. */
data class MapConfig(
    val voxelSizeM: Float,
    /** 관측 1회(깊이 1장)당 score 증가량. */
    val hitGain: Float,
    val minHits: Int,
    val minScore: Float,
    val freeMarginM: Float,
    val decayPerObservation: Float,
    val passedMarginM: Float,
    val maxUnseenS: Float,
    val radiusM: Float,
)

/** 바닥 추정 설정. */
data class FloorConfig(
    /** 바닥을 한 번 찾은 뒤에는 직전 바닥 ± 이 범위에서만 찾는다(첫 추정은 카메라보다 낮은 점 전체). */
    val searchBandM: Float,
    /** 바닥 점 판정: |y − floorY| < toleranceM + tolerancePerM × 수평거리. */
    val toleranceM: Float,
    /** 수평거리 1 m당 바닥 허용 오차 증가(실제 깊이는 멀수록 바닥이 위로 퍼져 보인다, M3 실측). */
    val tolerancePerM: Float,
    /** 높이 히스토그램 칸 크기. */
    val binM: Float,
    /** 바닥 높이 지수 평활 계수(새 추정의 비중). */
    val emaAlpha: Float,
    /** 추정에 필요한 최소 후보 점 수. */
    val minPoints: Int,
    /** 바닥보다 이만큼 이상 낮은 점은 내려가는 단차 후보(개수만 기록). */
    val belowMarginM: Float,
    /** 직전 바닥 근처 후보가 모자란 깊이가 이만큼 이어지면 바닥을 잊고 다시 찾는다(v0.2.7). */
    val lostFrames: Int,
)

/** 군집·높이 분류 설정. */
data class ClusterConfig(val epsM: Float, val minSamples: Int, val headMinM: Float, val bodyMinM: Float)

/** 분할 방법(IMPROVE_SPEC §6). NONE = 기준선: 통로 안 복셀을 나누지 않고 한꺼번에 군집한다. */
enum class SegmentMethod { NONE, PLANES }

/**
 * 분할 설정 (IMPROVE_SPEC §6 C2, M13). PLANES: 점유 복셀을 위에서 본 직선(수직 평면)으로 찾아, 이어진 길이와 높이 폭이
 * 기준 이상인 부분을 구조물(벽·문)로 본다. 물체와 구조물은 따로 군집해 서로 합쳐지지 않는다(통로 안이면 둘 다 안내).
 */
data class SegmentConfig(
    val method: SegmentMethod,
    /** 구조물로 볼 평면의 최소 길이(위에서 본 직선을 따라). */
    val planeMinLengthM: Float,
    /** 구조물로 볼 평면의 최소 높이 폭. */
    val planeMinHeightM: Float,
    /** 직선에서 이 거리 안의 복셀 기둥을 그 평면으로 본다. 위에서 본 벽은 높이마다 위치가 달라 두껍다(M13 실측 20~45 cm). */
    val planeInlierM: Float,
    /** 직선을 따라 이보다 큰 빈틈이 있으면 다른 조각으로 나눠 판정한다. */
    val planeMaxGapM: Float,
    /** 직선 방향 탐색 간격(허프 변환). */
    val planeAngleStepDeg: Float,
    /** 깊이 한 장에서 살펴보는 직선 수 상한(구조물이 아니었던 직선도 센다). */
    val maxPlanes: Int,
)

/** 실측에서 반복된 '통로 전체를 채우는 깊이 sheet' 오인식 실험 필터. 기본은 꺼 둔다. */
data class FalsePositiveFilterConfig(
    val enabled: Boolean,
    val minVoxels: Int,
    val minLateralSpanM: Float,
    val minAlongSpanM: Float,
    /** depth sheet의 가장 가까운 앞쪽 경계. 이보다 가까운 군집은 안전상 필터하지 않는다. */
    val minAlongMinM: Float,
    val minHeightSpanM: Float,
    val maxHeightMinM: Float,
    val minHeightMaxM: Float,
)

/** 추적 설정. */
data class TrackConfig(
    val matchRadiusM: Float,
    val emaAlpha: Float,
    /** 새 군집을 실제 물체로 내보내기 전에 연속해서 관측해야 하는 횟수. */
    val minConfirmObservations: Int,
    /** 군집이 잠깐 빠져도 같은 id를 복구할 수 있도록 내부 track을 유지하는 느린 경로 갱신 횟수. */
    val maxMissedUpdates: Int,
    /** depth sheet 모양([FalsePositiveFilterConfig] 조건)인 군집의 확인 횟수. 지우지 않고 더 오래 확인한다. */
    val suspiciousConfirmObservations: Int,
    /** 확인 횟수로 세는 관측의 최소 confidence(군집 복셀 score 평균). 0이면 모두 센다. */
    val minConfirmConfidence: Float,
    /** 확인 중 이전 중심점(EMA)에서 이보다 멀리 튄 관측은 연속 횟수를 1부터 다시 센다. `matchRadiusM`이면 끈 것과 같다. */
    val maxConfirmCentroidJumpM: Float,
)

/** 대표점 설정. */
data class RepPointConfig(val strategy: RepStrategy)

/** 거리 구간·음원 선택 설정. */
data class PolicyConfig(
    val stopM: Float,
    val warnMaxM: Float,
    val silentMaxM: Float,
    val hysteresisM: Float,
    val maxSources: Int,
    val maxInfoAgeMs: Float,
    val prioritizeStop: Boolean = false,
)

/** 안내 상태 기계 설정. */
data class StateConfig(
    val recoverFrames: Int,
    val recoverScoreScale: Float,
    val unknownRepeatS: Float,
    /** 연속 자세 사이 이동 속도가 이보다 크면 자세 불연속(월드 좌표 재정렬)으로 본다. */
    val maxSpeedMps: Float,
    /** 연속 자세 사이 회전 각속도가 이보다 크면 자세 불연속으로 본다. */
    val maxAngularSpeedDps: Float,
)

/** 오디오 렌더링 설정. */
data class AudioConfig(
    val sampleRate: Int,
    val blockSize: Int,
    val masterGainDb: Float,

    /** 단순 비프 프로토타입의 음높이. */
    val toneHz: Float,

    /** 한 번 울릴 때 실제 소리가 나는 시간. */
    val beepOnMs: Float,

    /** 가까운 장애물의 비프 반복 주기. */
    val nearPeriodMs: Float,

    /** 먼 장애물의 비프 반복 주기. */
    val farPeriodMs: Float,

    /** 뒤쪽(|방위각| > 90°) 장애물의 음높이. */
    val rearToneHz: Float,

    /** 완전히 옆(±90°)일 때 두 귀 시간차. */
    val maxItdMs: Float,

    // --- HRTF 바이노럴 렌더러 (M6, §7.6). 위 비프 키는 SimpleBeepRenderer 전용 ---

    /** FLOOR_PULSE 버스트 길이(분홍 잡음 + 포락선). */
    val pulseMs: Float,
    /** WARN 구간 반복 주기: 거리 warnMaxM에서 이 값. */
    val warnFarPeriodMs: Float,
    /** WARN 구간 반복 주기: 거리 stopM에서 이 값(사이는 선형). */
    val warnNearPeriodMs: Float,
    /** STOP 구간 반복 주기. */
    val stopPeriodMs: Float,
    /** STOP 구간 음량 상승. */
    val stopGainDb: Float,
    /** HEAD_TONE 버스트의 음높이(고역 강조, 높이는 음색으로 구분). */
    val headToneHz: Float,
    /** 상태 알림음(비공간) 음량. */
    val alertGainDb: Float,
    /** 출력 리미터 상한(선형 진폭, 0~1). */
    val limiterCeiling: Float,
    /** `AudioTrack` 버퍼 크기(블록 수). 작을수록 출력 지연이 짧고 끊김 위험이 크다(M7 S10: 4 → 끊김, 8 → 약 52 ms·끊김 0). */
    val bufferBlocks: Int,
)

/** 상태 전이별 진동 길이(ms, §9.3, 가설 — 사용자 평가에서 조정). */
data class HapticsConfig(
    /** 준비 완료: 짧게 1회. */
    val readyMs: Int,
    /** 일시정지: 짧게 2회(각 [pauseMs], 사이 [pauseGapMs]). */
    val pauseMs: Int,
    val pauseGapMs: Int,
    /** 확인 불가: 길게 1회. */
    val unknownMs: Int,
    /** 종료: 길게 1회. */
    val exitMs: Int,
)

/** 녹화 저장 간격 설정. */
data class RecordConfig(
    val depthEveryN: Int,
    val rgbEveryN: Int,
    /** device.csv(발열·배터리) 기록 주기. */
    val deviceLogIntervalS: Float,
)

/** 분석 도구용 정답 정렬 설정. */
data class AlignConfig(val fitLengthM: Float)

/** 설정 파일의 키 누락·미지의 키·타입 불일치·값 범위 오류. [path]는 `map.voxelSizeM` 같은 점 표기. */
class ConfigException(val path: String, message: String) : IllegalArgumentException("config '$path': $message")


/** 기존 펄스와 접근 연속음 중 선택한다. */
enum class SonifyMode { PULSE, RISK_CONTINUOUS }

/** 접근 연속음 설정. 수치 원본은 default.json이다. */
data class SonifyConfig(
    val mode: SonifyMode,
    val historyS: Float,
    val minHistoryS: Float,
    val approachOnMps: Float,
    val approachOffMps: Float,
    val onceS: Float,
    val nearM: Float,
    val farM: Float,
    val minHz: Float,
    val maxHz: Float,
    val minGain: Float,
    val maxGain: Float,
    val ttcHorizonS: Float,
    val ttcWeight: Float,
    val minSecondHarmonic: Float,
    val maxSecondHarmonic: Float,
    val thirdHarmonic: Float,
    val headPitchRatio: Float,
    val attackS: Float,
    val releaseS: Float,
    val pitchSmoothS: Float,
    val prototypeGain: Float,
    val duckDb: Float,
)
