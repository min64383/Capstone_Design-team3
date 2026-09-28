package walkassist.core.types

import walkassist.core.geometry.Vec3

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
    val track: TrackConfig,
    val repPoint: RepPointConfig,
    val policy: PolicyConfig,
    val state: StateConfig,
    val audio: AudioConfig,
    val record: RecordConfig,
    val align: AlignConfig,
)

/** 머리 좌표계 설정. */
data class HeadConfig(
    /** 카메라 → 머리 원점 오프셋(월드 수평 기준, 미터). 기준 파지에서 실측. */
    val offsetFromCameraM: Vec3,
)

/** 진행 방향 추정 설정. */
data class HeadingConfig(val windowS: Float, val minTravelM: Float)

/** 통로 치수. */
data class CorridorConfig(val widthM: Float, val heightM: Float, val lengthM: Float, val behindM: Float)

/** 깊이 역투영 설정. */
data class DepthConfig(
    /** 역투영 픽셀 간격(1 = 모든 픽셀). */
    val subsample: Int,
)

/** 로컬 복셀 맵 설정. */
data class MapConfig(
    val voxelSizeM: Float,
    val minHits: Int,
    val minScore: Float,
    val freeMarginM: Float,
    val decayPerObservation: Float,
    val passedMarginM: Float,
    val maxUnseenS: Float,
    val radiusM: Float,
)

/** 바닥 추정 설정. */
data class FloorConfig(val searchBandM: Float, val toleranceM: Float)

/** 군집·높이 분류 설정. */
data class ClusterConfig(val epsM: Float, val minSamples: Int, val headMinM: Float, val bodyMinM: Float)

/** 추적 설정. */
data class TrackConfig(val matchRadiusM: Float, val emaAlpha: Float)

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
data class AudioConfig(val sampleRate: Int, val blockSize: Int, val masterGainDb: Float)

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
