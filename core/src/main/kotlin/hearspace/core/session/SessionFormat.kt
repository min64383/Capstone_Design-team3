package hearspace.core.session

import hearspace.core.types.TrackingState

// 녹화 세션 폴더 형식 (MVP_SPEC §8, docs/FORMAT.md). 녹화(app)와 읽기(SessionReader)가 공유한다.
// v1(G1 확정)을 쓰고, v0(M1 스파이크 녹화)도 읽는다.

/** 세션 폴더 안의 파일·디렉터리 이름. */
object SessionFormat {
    /** 현재 쓰는 형식 버전. */
    const val VERSION = "v1"

    /** v0: M1 스파이크 형식(화면 기준 자세 열 포함, 깊이 K 없음). 읽기만 지원. */
    const val VERSION_V0 = "v0"

    /** 읽을 수 있는 버전. */
    val READABLE = setOf(VERSION_V0, VERSION)

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
    /** `Camera.getDisplayOrientedPose()`: 화면 방향 기준. v0에만 있음(F2 확인 후 v1에서 제거), v1은 null. */
    val displayPose: PoseGl?,
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
    private val COMMON_HEAD = listOf(
        "frameIndex", "tNs", "sysElapsedNs", "tracking", "trackingFailure",
        "tx", "ty", "tz", "qx", "qy", "qz", "qw",
    )
    private val DISPLAY_POSE = listOf("dtx", "dty", "dtz", "dqx", "dqy", "dqz", "dqw")
    private val COMMON_TAIL = listOf("depthTNs", "depthFile", "rawDepthTNs", "rawDepthFile", "confFile", "rgbFile")

    /** v1 열 이름(순서 고정). */
    val HEADER_V1 = COMMON_HEAD + COMMON_TAIL

    /** v0 열 이름(화면 기준 자세 포함). */
    val HEADER_V0 = COMMON_HEAD + DISPLAY_POSE + COMMON_TAIL

    /** [version]의 열 이름. */
    fun header(version: String = SessionFormat.VERSION): List<String> = when (version) {
        SessionFormat.VERSION -> HEADER_V1
        SessionFormat.VERSION_V0 -> HEADER_V0
        else -> throw IllegalArgumentException("unknown format version $version")
    }

    /** 헤더 한 줄(개행 없음). */
    fun headerLine(version: String = SessionFormat.VERSION): String = header(version).joinToString(",")

    /** 헤더 줄에서 버전을 알아낸다. 알 수 없으면 [IllegalArgumentException]. */
    fun versionOf(line: String): String =
        SessionFormat.READABLE.firstOrNull { headerLine(it) == line.trim() }
            ?: throw IllegalArgumentException("unknown frames.csv header")

    /** [row]를 [version] 형식의 CSV 한 줄로(개행 없음). v0이면 [FrameRow.displayPose]가 있어야 한다. */
    fun format(row: FrameRow, version: String = SessionFormat.VERSION): String = buildString {
        fun cell(v: Any?) {
            if (isNotEmpty()) append(',')
            if (v != null) append(v)
        }
        fun pose(p: PoseGl) {
            cell(p.tx); cell(p.ty); cell(p.tz); cell(p.qx); cell(p.qy); cell(p.qz); cell(p.qw)
        }
        cell(row.frameIndex); cell(row.tNs); cell(row.sysElapsedNs); cell(row.tracking.name); cell(row.trackingFailure)
        pose(row.pose)
        when (version) {
            SessionFormat.VERSION_V0 -> pose(requireNotNull(row.displayPose) { "v0 row needs displayPose" })
            SessionFormat.VERSION -> Unit
            else -> throw IllegalArgumentException("unknown format version $version")
        }
        cell(row.depthTNs); cell(row.depthFile); cell(row.rawDepthTNs); cell(row.rawDepthFile); cell(row.confFile); cell(row.rgbFile)
    }

    /** [version] 형식의 CSV 한 줄을 읽는다. 열 수가 다르거나 값이 잘못되면 [IllegalArgumentException]. */
    fun parse(line: String, version: String = SessionFormat.VERSION): FrameRow {
        val names = header(version)
        val c = line.split(',')
        require(c.size == names.size) { "expected ${names.size} columns, got ${c.size}" }
        val at = names.withIndex().associate { (i, n) -> n to i }
        fun raw(n: String) = c[at.getValue(n)]
        fun f(n: String) = raw(n).toFloatOrNull() ?: throw IllegalArgumentException("column $n: '${raw(n)}'")
        fun l(n: String) = raw(n).toLongOrNull() ?: throw IllegalArgumentException("column $n: '${raw(n)}'")
        fun lOpt(n: String) = if (raw(n).isEmpty()) null else l(n)
        fun sOpt(n: String) = raw(n).ifEmpty { null }
        fun pose(p: String) = PoseGl(f("${p}tx"), f("${p}ty"), f("${p}tz"), f("${p}qx"), f("${p}qy"), f("${p}qz"), f("${p}qw"))
        val tracking = TrackingState.entries.firstOrNull { it.name == raw("tracking") }
            ?: throw IllegalArgumentException("column tracking: '${raw("tracking")}'")
        return FrameRow(
            frameIndex = l("frameIndex"),
            tNs = l("tNs"),
            sysElapsedNs = l("sysElapsedNs"),
            tracking = tracking,
            trackingFailure = raw("trackingFailure"),
            pose = pose(""),
            displayPose = if (version == SessionFormat.VERSION_V0) pose("d") else null,
            depthTNs = lOpt("depthTNs"),
            depthFile = sOpt("depthFile"),
            rawDepthTNs = lOpt("rawDepthTNs"),
            rawDepthFile = sOpt("rawDepthFile"),
            confFile = sOpt("confFile"),
            rgbFile = sOpt("rgbFile"),
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
