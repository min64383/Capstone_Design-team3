package hearspace.core.replay

import hearspace.core.session.Png16
import hearspace.core.session.SessionFormat
import hearspace.core.session.SessionReader
import hearspace.core.types.DepthFrame
import hearspace.core.types.Intrinsics
import hearspace.core.types.LabelImage
import java.io.File
import kotlin.math.abs

/**
 * 재생에서 깊이 장에 의미 영상과 원시 깊이 장을 붙인다(M49, PC 재생 전용 읽기. 앱은 ARCore에서 직접 넘긴다, M51).
 * 둘 다 그 깊이를 전달한 프레임까지 받은 가장 최근 것이다(과거만): 의미 영상은 `semantic/index.csv`의 받은 프레임 시각 ≤ 그 프레임 시각,
 * 원시 깊이는 [SessionReader.rawDepthAt]. 의미 영상의 자세는 그 영상 촬영 시각에 가장 가까운, 이미 받은 프레임의 자세다.
 * 의미 영상이 없는 세션은 원시 깊이만 붙인다.
 */
/** [remap]: 라벨 화소 값 바꾸기(평가 전용 `-PlabelRemap=5:8`, M55 시험: 보도 → 물체). 비면 그대로. */
class VisionFrames(private val dir: File, private val reader: SessionReader, private val remap: Map<Int, Int> = emptyMap()) {
    private class Entry(val frameTNs: Long, val imageTNs: Long, val width: Int, val height: Int, val file: String)

    private val entries: List<Entry> = File(dir, SessionFormat.SEMANTIC_INDEX).takeIf { it.isFile }?.readLines()?.drop(1)
        ?.filter { it.isNotBlank() }?.map { line ->
            val c = line.split(',')
            Entry(c[0].toLong(), c[1].toLong(), c[2].toInt(), c[3].toInt(), c[4])
        }?.sortedBy { it.frameTNs }.orEmpty()
    private val rows = reader.rows
    private val frameTNs = rows.associate { it.frameIndex to it.tNs }
    private val poses by lazy { reader.poseFrames() }
    private var cacheImageTNs = Long.MIN_VALUE
    private var cache: LabelImage? = null

    /** 의미 영상이 하나라도 있는 세션인지. */
    val hasLabels: Boolean get() = entries.isNotEmpty()

    /** 프레임 [frameIndex]가 전달한 [depth]에 의미 영상과 원시 깊이를 붙인 장. */
    fun attach(depth: DepthFrame, frameIndex: Long): DepthFrame {
        val now = frameTNs[frameIndex] ?: return depth
        return depth.copy(labels = labelsAt(now), raw = reader.rawDepthAt(frameIndex))
    }

    private fun labelsAt(nowNs: Long): LabelImage? {
        var lo = 0
        var hi = entries.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].frameTNs <= nowNs) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return null
        val e = entries[best]
        if (e.imageTNs == cacheImageTNs) return cache
        cacheImageTNs = e.imageTNs
        val f = File(dir, e.file)
        cache = if (!f.isFile) null else {
            val img = Png16.decode(f.readBytes(), e.width.toLong() * e.height)
            require(img.bitDepth == 8 && img.width == e.width && img.height == e.height) { "${e.file}: semantic must be 8-bit ${e.width}x${e.height}" }
            val pose = poses.filter { it.tCaptureNs <= nowNs }.minByOrNull { abs(it.tCaptureNs - e.imageTNs) } ?: return null
            val px = if (remap.isEmpty()) img.gray8!! else ByteArray(img.gray8!!.size) { i -> (remap[img.gray8[i].toInt() and 0xFF] ?: (img.gray8[i].toInt() and 0xFF)).toByte() }
            LabelImage(e.imageTNs, px, intrinsicsFor(e.width, e.height), pose.worldFromCam)
        }
        return cache
    }

    /**
     * 의미 영상의 내부 파라미터. VERIFY: ARCore 문서는 폭 256, 높이는 화면 비율이라고만 적었다. 가로세로 비가 GPU 텍스처(16:9)에 가까우면
     * 텍스처 K, CPU 영상(4:3)에 가까우면 CPU 영상 K를 비례로 줄인다(깊이 영상 K = 텍스처 K 비례와 같은 방식). 둘 다 2% 넘게 다르면 멈춘다.
     */
    private fun intrinsicsFor(w: Int, h: Int): Intrinsics {
        val cam = reader.meta.camera
        val aspect = w.toFloat() / h
        val options = listOf(cam.textureIntrinsics, cam.imageIntrinsics)
        val k = options.minBy { abs(it.width.toFloat() / it.height - aspect) }
        require(abs(k.width.toFloat() / k.height - aspect) <= 0.02f * aspect) { "semantic ${w}x$h matches neither texture nor CPU image aspect" }
        return SessionReader.scaleTextureK(k, w, h)
    }
}
