package walkassist.core.session

import walkassist.core.types.TrackingState

// 녹화 세션 폴더 형식 v0 (MVP_SPEC §8.1 초안 + M1 스파이크용 추가 열). 녹화(app)와 읽기(SessionReader, M2)가 공유한다.
// 형식 v1은 스파이크 결과로 G1에서 확정한다(docs/FORMAT.md).

/** 세션 폴더 안의 파일·디렉터리 이름. */
object SessionFormat {
    /** 형식 버전. */
    const val VERSION = "v0"

    const val META_FILE = "meta.json"
    const val MP4_FILE = "arcore.mp4"
    const val FRAMES_FILE = "frames.csv"
    const val DEVICE_FILE = "device.csv"
    const val DEPTH_DIR = "depth"
    const val RAW_DEPTH_DIR = "raw_depth"
    const val DEPTH_CONF_DIR = "depth_conf"
    const val RGB_DIR = "rgb"

    /** 세션 폴더 기준 상대 경로. 예: `depth/000123.png`. */
    fun depthFile(frameIndex: Long) = "$DEPTH_DIR/${indexName(frameIndex)}.png"

    /** 원시 깊이 파일 상대 경로. */
    fun rawDepthFile(frameIndex: Long) = "$RAW_DEPTH_DIR/${indexName(frameIndex)}.png"

    /** 원시 깊이 신뢰도 파일 상대 경로. */
    fun confFile(frameIndex: Long) = "$DEPTH_CONF_DIR/${indexName(frameIndex)}.png"

    /** 저해상도 RGB 파일 상대 경로. */
    fun rgbFile(frameIndex: Long) = "$RGB_DIR/${indexName(frameIndex)}.jpg"

    private fun indexName(i: Long): String {
        require(i >= 0) { "negative frame index $i" }
        return i.toString().padStart(6, '0')
    }
}

/** ARCore 규약 원본 자세(C_gl → W). 평행이동(m) + 단위 쿼터니언(x, y, z, w). 변환은 읽는 쪽에서 한다(§5). */
data class PoseGl(
    val tx: Float, val ty: Float, val tz: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
)

/** `frames.csv`의 한 행. ARCore 프레임마다 하나이며 저장이 밀려도 빠지지 않는다. 파일 열은 실제로 저장한 경우에만 값이 있다. */
data class FrameRow(
    /** 녹화 시작 후 ARCore 프레임 순번(0부터). */
    val frameIndex: Long,
    /** `Frame.getTimestamp()`. 시간 기준은 ARCore가 정의하지 않는다(F5에서 확인). */
    val tNs: Long,
    /** GL 스레드가 프레임을 받은 시각의 `SystemClock.elapsedRealtimeNanos()`. 시간 기준 비교용. */
    val sysElapsedNs: Long,
    val tracking: TrackingState,
    /** ARCore `TrackingFailureReason` 이름. */
    val trackingFailure: String,
    /** `Camera.getPose()`: 물리 카메라, ARCore GL 규약. */
    val pose: PoseGl,
    /** `Camera.getDisplayOrientedPose()`: 화면 방향 기준(F2 확인용, v0 한정). */
    val displayPose: PoseGl,
    /** 이 프레임에서 얻은 일반 깊이 이미지의 시각(없으면 null). 저장 여부와 무관하게 기록. */
    val depthTNs: Long?,
    val depthFile: String?,
    /** 이 프레임에서 얻은 원시 깊이 이미지의 시각(없으면 null). */
    val rawDepthTNs: Long?,
    val rawDepthFile: String?,
    val confFile: String?,
    val rgbFile: String?,
)

/** `frames.csv` 쓰기·읽기. 빈 칸은 null. 실수는 Kotlin 최단 표기로 써서 왕복 시 값이 변하지 않는다. */
object FramesCsv {
    /** 열 이름(순서 고정). */
    val HEADER = listOf(
        "frameIndex", "tNs", "sysElapsedNs", "tracking", "trackingFailure",
        "tx", "ty", "tz", "qx", "qy", "qz", "qw",
        "dtx", "dty", "dtz", "dqx", "dqy", "dqz", "dqw",
        "depthTNs", "depthFile", "rawDepthTNs", "rawDepthFile", "confFile", "rgbFile",
    )

    /** 헤더 한 줄(개행 없음). */
    fun headerLine(): String = HEADER.joinToString(",")

    /** [row]를 CSV 한 줄로(개행 없음). */
    fun format(row: FrameRow): String = buildString {
        fun cell(v: Any?) {
            if (isNotEmpty()) append(',')
            if (v != null) append(v)
        }
        cell(row.frameIndex); cell(row.tNs); cell(row.sysElapsedNs); cell(row.tracking.name); cell(row.trackingFailure)
        for (p in listOf(row.pose, row.displayPose)) {
            cell(p.tx); cell(p.ty); cell(p.tz); cell(p.qx); cell(p.qy); cell(p.qz); cell(p.qw)
        }
        cell(row.depthTNs); cell(row.depthFile); cell(row.rawDepthTNs); cell(row.rawDepthFile); cell(row.confFile); cell(row.rgbFile)
    }

    /** CSV 한 줄을 읽는다. 열 수가 다르거나 값이 잘못되면 [IllegalArgumentException]. */
    fun parse(line: String): FrameRow {
        val c = line.split(',')
        require(c.size == HEADER.size) { "expected ${HEADER.size} columns, got ${c.size}" }
        fun f(i: Int) = c[i].toFloatOrNull() ?: throw IllegalArgumentException("column ${HEADER[i]}: '${c[i]}'")
        fun l(i: Int) = c[i].toLongOrNull() ?: throw IllegalArgumentException("column ${HEADER[i]}: '${c[i]}'")
        fun lOpt(i: Int) = if (c[i].isEmpty()) null else l(i)
        fun sOpt(i: Int) = c[i].ifEmpty { null }
        fun pose(o: Int) = PoseGl(f(o), f(o + 1), f(o + 2), f(o + 3), f(o + 4), f(o + 5), f(o + 6))
        return FrameRow(
            frameIndex = l(0),
            tNs = l(1),
            sysElapsedNs = l(2),
            tracking = TrackingState.valueOf(c[3]),
            trackingFailure = c[4],
            pose = pose(5),
            displayPose = pose(12),
            depthTNs = lOpt(19),
            depthFile = sOpt(20),
            rawDepthTNs = lOpt(21),
            rawDepthFile = sOpt(22),
            confFile = sOpt(23),
            rgbFile = sOpt(24),
        )
    }
}

/** `device.csv`의 한 행 (§10.1). 값이 없으면 null. */
data class DeviceRow(
    /** `SystemClock.elapsedRealtimeNanos()`. */
    val tNs: Long,
    /** `PowerManager.getCurrentThermalStatus()` 값(API 29+). */
    val thermalStatus: Int?,
    val batteryPct: Int?,
    val audioOutputLatencyMs: Float?,
)

/** `device.csv` 쓰기·읽기. */
object DeviceCsv {
    /** 열 이름(순서 고정, §10.1). */
    val HEADER = listOf("tNs", "thermalStatus", "batteryPct", "audioOutputLatencyMs")

    /** 헤더 한 줄. */
    fun headerLine(): String = HEADER.joinToString(",")

    /** [row]를 CSV 한 줄로. */
    fun format(row: DeviceRow): String =
        listOf(row.tNs, row.thermalStatus, row.batteryPct, row.audioOutputLatencyMs).joinToString(",") { it?.toString() ?: "" }

    /** CSV 한 줄을 읽는다. */
    fun parse(line: String): DeviceRow {
        val c = line.split(',')
        require(c.size == HEADER.size) { "expected ${HEADER.size} columns, got ${c.size}" }
        return DeviceRow(
            tNs = c[0].toLong(),
            thermalStatus = c[1].ifEmpty { null }?.toInt(),
            batteryPct = c[2].ifEmpty { null }?.toInt(),
            audioOutputLatencyMs = c[3].ifEmpty { null }?.toFloat(),
        )
    }
}
