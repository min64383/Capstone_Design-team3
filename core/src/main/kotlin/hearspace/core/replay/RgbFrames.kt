package hearspace.core.replay

import hearspace.core.session.FrameRow
import hearspace.core.types.DepthFrame
import hearspace.core.types.GuideImage
import hearspace.core.types.Intrinsics
import java.io.File
import javax.imageio.ImageIO

/** 재생에 붙일 RGB의 원천: 앱이 녹화한 JPEG(`rgb/`, `record.rgbEveryN`장마다) 또는 MP4에서 꺼낸 매 프레임 밝기(`rgb_mp4/`, M19). */
enum class RgbSource { RECORDED, MP4 }

/**
 * 녹화 세션의 RGB(JPEG)를 깊이 장에 붙인다(M13.7 RGB 안내 보정, **PC 재생 전용**: JDK ImageIO를 쓰며 안드로이드에는 없다.
 * 앱은 ARCore 카메라 영상의 밝기를 직접 넘겨야 한다).
 *
 * 짝짓기(M19): 깊이를 전달한 프레임([attach]의 frameIndex)의 RGB를 쓴다. 앱에서 깊이 영상과 카메라 영상은 같은 ARCore 프레임에서 함께
 * 받으므로 미래 프레임이 아니다. 깊이 시각과 카메라 시각은 ±1 ms로 흔들려, 시각으로 "깊이 이하"를 찾으면 같은 프레임 RGB가 빠졌다.
 * 그 프레임에 RGB가 없으면 이전 프레임 중 가장 최근 것을 그 프레임 시각에서 [maxAgeNs] 안일 때만 쓴다(과거만).
 * [K]는 `meta.json`의 `camera.imageIntrinsics`, JPEG 크기가 다르면 비율로 맞춘다. RGB 파일이 없으면 보정 없이 그대로 넘긴다.
 * [RgbSource.MP4]는 `tools/analysis/mp4_rgb.py`가 쓴 `rgb_mp4/index.csv`(frameIndex, tNs, …)를 읽는다.
 */
class RgbFrames(
    private val dir: File,
    rows: List<FrameRow>,
    private val K: Intrinsics,
    private val maxAgeNs: Long,
    source: RgbSource = RgbSource.RECORDED,
) {
    private class Entry(val frameIndex: Long, val tNs: Long, val file: String)

    private val frameTNs = rows.associate { it.frameIndex to it.tNs }
    private val rgb: List<Entry> = when (source) {
        RgbSource.RECORDED -> rows.filter { it.rgbFile != null }.map { Entry(it.frameIndex, it.tNs, it.rgbFile!!) }
        RgbSource.MP4 -> {
            val index = File(dir, "$MP4_DIR/index.csv")
            require(index.isFile) { "rgbFrom=mp4 needs ${index.path} (python tools/analysis/mp4_rgb.py <session>)" }
            index.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
                val c = line.split(',')
                Entry(c[0].toLong(), c[1].toLong(), "$MP4_DIR/%06d.jpg".format(c[0].toLong()))
            }
        }
    }.sortedBy { it.frameIndex }

    /** 프레임 [frameIndex]가 전달한 [depth]에 RGB를 붙인 장(쓸 RGB가 없으면 그대로). */
    fun attach(depth: DepthFrame, frameIndex: Long): DepthFrame {
        var lo = 0
        var hi = rgb.size - 1
        var best = -1
        while (lo <= hi) { // 그 프레임 이하의 마지막 RGB
            val mid = (lo + hi) ushr 1
            if (rgb[mid].frameIndex <= frameIndex) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return depth
        val e = rgb[best]
        val now = frameTNs[frameIndex] ?: return depth
        if (now - e.tNs > maxAgeNs) return depth
        val file = File(dir, e.file)
        if (!file.isFile) return depth // 경량본(testdata)은 RGB를 빼고 올린 세션이 있다
        val img = ImageIO.read(file) ?: return depth
        val luma = ByteArray(img.width * img.height)
        val gray = img.raster.numBands == 1
        for (y in 0 until img.height) for (x in 0 until img.width) {
            // 회색조 JPEG(mp4_rgb.py의 Y 평면)는 화소 값을 그대로 읽는다: getRGB는 회색 색공간(선형)을 sRGB로 바꿔 132 → 190이 됐다
            val l = if (gray) {
                img.raster.getSample(x, y, 0).toFloat()
            } else {
                val c = img.getRGB(x, y)
                0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
            luma[y * img.width + x] = l.toInt().coerceIn(0, 255).toByte()
        }
        val sx = img.width.toFloat() / K.width
        val sy = img.height.toFloat() / K.height
        val k = Intrinsics(K.fx * sx, K.fy * sy, K.cx * sx, K.cy * sy, img.width, img.height)
        return depth.copy(guide = GuideImage(e.tNs, luma, k))
    }

    companion object {
        const val MP4_DIR = "rgb_mp4"
    }
}
