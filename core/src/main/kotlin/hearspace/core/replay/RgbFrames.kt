package hearspace.core.replay

import hearspace.core.session.FrameRow
import hearspace.core.types.DepthFrame
import hearspace.core.types.GuideImage
import hearspace.core.types.Intrinsics
import java.io.File
import javax.imageio.ImageIO

/**
 * 녹화 세션의 RGB(JPEG)를 깊이 장에 붙인다(M13.7 RGB 안내 보정, **PC 재생 전용**: JDK ImageIO를 쓰며 안드로이드에는 없다.
 * 앱은 ARCore 카메라 영상의 밝기를 직접 넘겨야 한다). 깊이 시각 이하이고 [maxAgeNs] 안의 가장 최근 RGB만(미래 프레임 없음).
 * [K]는 `meta.json`의 `camera.imageIntrinsics`, JPEG 크기가 다르면 비율로 맞춘다. RGB 파일이 없으면 보정 없이 그대로 넘긴다.
 */
class RgbFrames(private val dir: File, rows: List<FrameRow>, private val K: Intrinsics, private val maxAgeNs: Long) {
    private val rgb = rows.filter { it.rgbFile != null }.sortedBy { it.tNs }

    /** [depth]에 RGB를 붙인 장(쓸 RGB가 없으면 그대로). */
    fun attach(depth: DepthFrame): DepthFrame {
        var lo = 0
        var hi = rgb.size - 1
        var best = -1
        while (lo <= hi) { // 깊이 시각 이하의 마지막 RGB
            val mid = (lo + hi) ushr 1
            if (rgb[mid].tNs <= depth.tCaptureNs) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return depth
        val row = rgb[best]
        if (depth.tCaptureNs - row.tNs > maxAgeNs) return depth
        val file = File(dir, row.rgbFile!!)
        if (!file.isFile) return depth // 경량본(testdata)은 RGB를 빼고 올린 세션이 있다
        val img = ImageIO.read(file) ?: return depth
        val luma = ByteArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = img.getRGB(x, y)
            val l = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            luma[y * img.width + x] = l.toInt().coerceIn(0, 255).toByte()
        }
        val sx = img.width.toFloat() / K.width
        val sy = img.height.toFloat() / K.height
        val k = Intrinsics(K.fx * sx, K.fy * sy, K.cx * sx, K.cy * sy, img.width, img.height)
        return depth.copy(guide = GuideImage(row.tNs, luma, k))
    }
}
