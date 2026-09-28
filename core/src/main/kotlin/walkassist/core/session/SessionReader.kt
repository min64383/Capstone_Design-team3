package walkassist.core.session

import walkassist.core.geometry.Conventions
import walkassist.core.geometry.Mat4
import walkassist.core.geometry.Quaternion
import walkassist.core.geometry.Vec3
import walkassist.core.types.DepthFrame
import walkassist.core.types.Intrinsics
import walkassist.core.types.PoseFrame
import walkassist.core.types.TrackingState
import java.io.File
import kotlin.math.abs

/** 느린 경로 입력으로 쓸 깊이 종류(F6). 기본값은 M3에서 비교 후 정한다. */
enum class DepthSource { SMOOTHED, RAW }

/** 세션을 도착 순서대로 재생할 때의 사건. [arrivalTNs]는 앱이 그 데이터를 받은 ARCore 프레임 시각. */
sealed interface SessionEvent {
    val arrivalTNs: Long
}

/** ARCore 프레임 1개의 자세(모든 행). */
data class PoseEvent(val frameIndex: Long, val pose: PoseFrame) : SessionEvent {
    override val arrivalTNs: Long get() = pose.tCaptureNs
}

/** 새 깊이 1개. [frameIndex]는 깊이가 처음 나타난 프레임(도착 시점). */
data class DepthEvent(val frameIndex: Long, override val arrivalTNs: Long, val depth: DepthFrame) : SessionEvent

/**
 * 녹화 세션 폴더(형식 v1, v0도 읽음 — docs/FORMAT.md) → `PoseFrame`/`DepthFrame` (§7.9).
 *
 * - 자세: ARCore GL 원본을 C_cv로 변환한다(§5, 변환은 여기와 app `FrameAdapter`에서만).
 * - 깊이의 `tCaptureNs`는 깊이 이미지 자체의 시각이다(§2.2-4). 같은 시각이 반복되면(깊이 정지) 새 사건을 내지 않는다.
 * - 깊이 시각의 자세: 깊이가 도착한 프레임까지 **이미 받은** 자세 중 시각이 가장 가까운 것(보간 없음, 미래 없음).
 *   ARCore 깊이 시각은 프레임 시각과 일치하지 않는다(일반 깊이는 도착 프레임보다 약 0.3 ms 앞, 원시는 프레임 사이).
 * - 추적 중이 아닌 프레임의 자세가 골라지면 그 깊이는 쓰지 않는다.
 */
class SessionReader(private val dir: File) {

    /** `meta.json`. */
    val meta: SessionMeta = SessionMeta.fromJson(File(dir, SessionFormat.META_FILE).readText())

    /** `frames.csv`의 모든 행(파일 순서). */
    val rows: List<FrameRow> = readRows()

    /** 읽는 중 건너뛴 항목의 사유(파일 없음, 추적 아님 등). [events]를 끝까지 돈 뒤 채워진다. */
    val warnings: MutableList<String> = mutableListOf()

    private fun readRows(): List<FrameRow> {
        require(meta.formatVersion in SessionFormat.READABLE) { "unsupported format ${meta.formatVersion}" }
        val lines = File(dir, SessionFormat.FRAMES_FILE).readLines()
        require(lines.isNotEmpty() && lines[0] == FramesCsv.headerLine(meta.formatVersion)) {
            "frames.csv header does not match format ${meta.formatVersion}"
        }
        return lines.drop(1).filter { it.isNotBlank() }.mapIndexed { i, line ->
            try {
                FramesCsv.parse(line, meta.formatVersion)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("frames.csv line ${i + 2}: ${e.message}", e)
            }
        }
    }

    /** 모든 프레임의 자세(C_cv). */
    fun poseFrames(): List<PoseFrame> = rows.map { toPoseFrame(it) }

    /**
     * 깊이 내부 파라미터. v1은 `meta.depth.intrinsics`를 쓰고(크기가 맞을 때),
     * v0은 같은 규칙(텍스처 K × 깊이 크기 / 텍스처 크기, F4 확정)으로 환산한다.
     */
    fun depthIntrinsics(width: Int, height: Int): Intrinsics {
        meta.depth.intrinsics?.let { if (it.width == width && it.height == height) return it }
        return scaleTextureK(meta.camera.textureIntrinsics, width, height)
    }

    /** 자세·깊이 사건을 도착 순서로. 깊이 이미지는 필요할 때 읽는다(긴 세션 메모리 절약). */
    fun events(source: DepthSource): Sequence<SessionEvent> = sequence {
        warnings.clear()
        var lastDepthTNs = Long.MIN_VALUE
        val poses = poseFrames()
        for ((i, row) in rows.withIndex()) {
            yield(PoseEvent(row.frameIndex, poses[i]))
            val (tNs, file) = when (source) {
                DepthSource.SMOOTHED -> row.depthTNs to row.depthFile
                DepthSource.RAW -> row.rawDepthTNs to row.rawDepthFile
            }
            if (tNs == null || file == null || tNs <= lastDepthTNs) continue
            val f = File(dir, file)
            if (!f.isFile) {
                warnings += "frame ${row.frameIndex}: missing $file"
                continue
            }
            val poseIdx = nearestPastPose(i, tNs)
            if (rows[poseIdx].tracking != TrackingState.TRACKING) {
                warnings += "frame ${row.frameIndex}: depth pose not tracking (${rows[poseIdx].tracking})"
                continue
            }
            val img = Png16.decode(f.readBytes())
            require(img.bitDepth == 16) { "$file: expected 16-bit depth" }
            val conf = if (source == DepthSource.RAW) row.confFile?.let { readConfidence(it, img.width, img.height) } else null
            lastDepthTNs = tNs
            yield(
                DepthEvent(
                    row.frameIndex,
                    row.tNs,
                    DepthFrame(
                        tCaptureNs = tNs,
                        depthMm = img.gray16!!,
                        confidence = conf,
                        K = depthIntrinsics(img.width, img.height),
                        worldFromCam = poses[poseIdx].worldFromCam,
                        source = if (source == DepthSource.RAW) "arcore_raw_depth" else "arcore_depth",
                    ),
                ),
            )
        }
    }

    /** rows[0..arrival] 중 [tNs]에 가장 가까운 행. 시각이 단조 증가한다고 보고 뒤에서부터 찾는다. */
    private fun nearestPastPose(arrival: Int, tNs: Long): Int {
        var best = arrival
        var j = arrival - 1
        while (j >= 0 && abs(rows[j].tNs - tNs) <= abs(rows[best].tNs - tNs)) {
            best = j
            j--
        }
        return best
    }

    private fun readConfidence(file: String, w: Int, h: Int): ByteArray? {
        val f = File(dir, file)
        if (!f.isFile) {
            warnings += "missing $file"
            return null
        }
        val img = Png16.decode(f.readBytes())
        require(img.bitDepth == 8 && img.width == w && img.height == h) { "$file: confidence must be 8-bit ${w}x$h" }
        return img.gray8
    }

    companion object {
        /** 텍스처 K를 깊이 크기로 환산(F4). 앱 녹화기와 v0 읽기가 같은 규칙을 쓴다. */
        fun scaleTextureK(t: Intrinsics, width: Int, height: Int): Intrinsics {
            val sx = width.toFloat() / t.width
            val sy = height.toFloat() / t.height
            return Intrinsics(t.fx * sx, t.fy * sy, t.cx * sx, t.cy * sy, width, height)
        }

        /** ARCore GL 원본 자세 → C_cv 규약 `worldFromCam`. */
        fun toWorldFromCv(p: PoseGl): Mat4 =
            Conventions.glToCv(Quaternion(p.qx, p.qy, p.qz, p.qw).toMat4(Vec3(p.tx, p.ty, p.tz)))

        /** frames.csv 행 → PoseFrame. */
        fun toPoseFrame(row: FrameRow) = PoseFrame(row.tNs, row.tracking, toWorldFromCv(row.pose))
    }
}
