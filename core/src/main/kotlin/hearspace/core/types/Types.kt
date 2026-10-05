package hearspace.core.types

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3

// 데이터 계약 (MVP_SPEC §6). 좌표계는 월드(W, +Y 위)와 카메라(C_cv: +X 오른쪽, +Y 아래, +Z 앞)만 쓴다.

/** ARCore 추적 상태를 core 타입으로 옮긴 것. */
enum class TrackingState { TRACKING, PAUSED, STOPPED }

/** 대표점 계산 방식 (§7.4). */
enum class RepStrategy { CENTROID, NEAREST, CORRIDOR_NEAREST }

/** 통로 안 부분의 높이 분류 (§7.4). */
enum class HeightClass { FLOOR, BODY, HEAD }

/**
 * 분할 꼬리표(IMPROVE_SPEC §4.1, M13). 바닥 점은 맵에 들어오지 않아 FLOOR가 없다.
 * 구조물(벽·문 같은 큰 수직 평면)도 통로 안이면 물체와 똑같이 안내한다(안전 원칙, §8.3). 소리를 다르게 낼지는 미결정(§14).
 */
enum class VoxelLabel { OBJECT, STRUCTURE }

/** 느린 경로 입력으로 쓸 ARCore 깊이 종류(F6): 일반(평활·채움, 30 Hz) 또는 원시(+신뢰도, 10~30 Hz). */
enum class DepthSource { SMOOTHED, RAW }

/** 로컬 맵의 건강 상태. */
enum class MapHealth { OK, DEGRADED }

/** 진행 방향 거리 구간 (§7.5). */
enum class Band { STOP, WARN, SILENT }

/** 장애물 음원의 음색 (§7.6). */
enum class SoundKind { FLOOR_PULSE, HEAD_TONE }

/** 안내 상태 기계의 상태 (§7.5). */
enum class GuidanceState { NORMAL, DEGRADED, UNKNOWN, PAUSED }

/** 비공간 알림음 (§7.6: 시작, 준비 완료, 정지, 확인 불가, 준비 대기 중 반복음 — M9). */
enum class AlertKind { START, READY, PAUSE, UNKNOWN, WAITING }

/** 핀홀 내부 파라미터(픽셀). C_cv 규약으로 역투영할 때 쓴다. */
data class Intrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
)

/** 빠른 경로 입력. ARCore 프레임마다 하나. */
data class PoseFrame(
    val tCaptureNs: Long,
    val tracking: TrackingState,
    /** C_cv → W 변환. */
    val worldFromCam: Mat4,
)

/**
 * 느린 경로 입력. 배열은 생성 후 수정하지 않는다(원시 배열은 성능 때문에 그대로 쓴다).
 * 동등성은 배열 내용으로 비교한다.
 */
data class DepthFrame(
    val tCaptureNs: Long,
    /** 행 우선 `K.width × K.height`, 0 = 무효. */
    val depthMm: ShortArray,
    /** 0~255, 없으면 null. */
    val confidence: ByteArray?,
    val K: Intrinsics,
    /** 깊이 촬영 시각의 C_cv → W 변환. */
    val worldFromCam: Mat4,
    /** "arcore_depth" | "arcore_raw_depth" | "synthetic" */
    val source: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepthFrame) return false
        return tCaptureNs == other.tCaptureNs &&
            depthMm.contentEquals(other.depthMm) &&
            (confidence?.contentEquals(other.confidence) ?: (other.confidence == null)) &&
            K == other.K &&
            worldFromCam == other.worldFromCam &&
            source == other.source
    }

    override fun hashCode(): Int {
        var h = tCaptureNs.hashCode()
        h = 31 * h + depthMm.contentHashCode()
        h = 31 * h + (confidence?.contentHashCode() ?: 0)
        h = 31 * h + K.hashCode()
        h = 31 * h + worldFromCam.hashCode()
        h = 31 * h + source.hashCode()
        return h
    }
}

/** 추적 중인 장애물 하나. */
data class Obstacle(
    val id: Int,
    /** 설정에서 선택한 방식의 대표점(월드). */
    val repPointW: Vec3,
    /** 세 방식 모두의 대표점(디버그·비교용). */
    val repCandidatesW: Map<RepStrategy, Vec3>,
    val aabbMinW: Vec3,
    val aabbMaxW: Vec3,
    val heightClass: HeightClass,
    val inCorridor: Boolean,
    val confidence: Float,
    val lastSeenNs: Long,
    val nObservations: Int,
    val label: VoxelLabel = VoxelLabel.OBJECT,
)

/** 느린 경로의 출력. */
data class ObstacleSnapshot(
    /** 이 스냅샷을 만든 깊이의 촬영 시각. 정보 나이의 기준이다. */
    val tCaptureNs: Long,
    val obstacles: List<Obstacle>,
    val floorY: Float?,
    val mapHealth: MapHealth,
)

/** 음원 하나에 대한 렌더링 명령. */
data class AudioCmd(
    val obstacleId: Int,
    /** 머리 기준 방위각, 오른쪽 +. */
    val azimuthDeg: Float,
    val distanceM: Float,
    val band: Band,
    val sound: SoundKind,
    val infoAgeMs: Float,
    /** 진행 통로 안의 물체인지. 기존 생성 코드와 호환되도록 기본값 true. */
    val inCorridor: Boolean = true,
)

/** 오디오 블록마다 계산하는 빠른 경로의 출력. */
data class GuidanceOutput(
    val tBlockNs: Long,
    val state: GuidanceState,
    val commands: List<AudioCmd>,
    val headingDeg: Float,
    /** 상태 전이 시에만 값이 있다(1회 알림). */
    val alert: AlertKind?,
)

