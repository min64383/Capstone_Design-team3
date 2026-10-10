package hearspace.core.replay

import hearspace.core.geometry.Mat4
import hearspace.core.session.FrameRow
import hearspace.core.session.PoseGl
import hearspace.core.types.DepthFrame
import hearspace.core.types.Intrinsics
import hearspace.core.types.TrackingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** RGB 짝짓기(M19 설계 표 7의 S5): 깊이를 전달한 프레임의 RGB를 붙이고, 그 뒤 프레임의 RGB는 절대 붙이지 않는다. */
class RgbFramesTest {
    private val k = Intrinsics(100f, 100f, 2f, 2f, 4, 4)
    private val frameNs = 33_333_333L

    private fun row(i: Long, rgb: String? = null) = FrameRow(
        i, i * frameNs, i * frameNs, TrackingState.TRACKING, "NONE", PoseGl(0f, 0f, 0f, 0f, 0f, 0f, 1f), null,
        null, null, null, null, null, rgb,
    )

    /** 밝기 [l] 한 가지로 칠한 4×4 회색 JPEG. */
    private fun jpeg(f: File, l: Int) {
        f.parentFile.mkdirs()
        val img = BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until 4) for (x in 0 until 4) img.raster.setSample(x, y, 0, l)
        ImageIO.write(img, "jpg", f)
    }

    /** 깊이 시각이 카메라 시각보다 0.3 ms 앞선다(실측 ±1 ms 흔들림): 시각으로 "깊이 이하"를 찾으면 같은 프레임이 빠졌다. */
    private fun depthOf(frame: Long) = DepthFrame(frame * frameNs - 300_000L, ShortArray(16) { 1000 }, null, k, Mat4.IDENTITY, "synthetic")

    private fun luma(d: DepthFrame) = d.guide?.luma?.get(5)?.toInt()?.and(0xFF)

    @Test
    fun `MP4 frames are paired by the frame that delivered the depth, never a later one`(@TempDir dir: File) {
        File(dir, "rgb_mp4").mkdirs()
        File(dir, "rgb_mp4/index.csv").writeText("frameIndex,tNs,mp4Sample,dtMs\n3,${3 * frameNs},0,0.1\n4,${4 * frameNs},1,-0.2\n")
        jpeg(File(dir, "rgb_mp4/000003.jpg"), 50)
        jpeg(File(dir, "rgb_mp4/000004.jpg"), 200)
        val rows = (0L..5L).map { row(it) }
        val rgb = RgbFrames(dir, rows, k, 20_000_000L, RgbSource.MP4)
        // 같은 프레임(3)의 RGB. 깊이 시각이 RGB보다 0.3 ms 앞서도 붙는다
        assertEquals(50, luma(rgb.attach(depthOf(3), 3))!!, 3.0)
        assertEquals(200, luma(rgb.attach(depthOf(4), 4))!!, 3.0)
        // 프레임 2에는 RGB가 없고 다음 프레임(3)의 RGB는 미래라 붙이지 않는다
        assertNull(rgb.attach(depthOf(2), 2).guide)
        // 프레임 5: 이전 RGB(4)는 33 ms 전이라 20 ms 허용치를 넘는다
        assertNull(rgb.attach(depthOf(5), 5).guide)
    }

    @Test
    fun `recorded JPEGs follow the same rule and a longer age allows an earlier frame`(@TempDir dir: File) {
        jpeg(File(dir, "rgb/000003.jpg"), 120)
        val rows = (0L..5L).map { row(it, if (it == 3L) "rgb/000003.jpg" else null) }
        assertEquals(120, luma(RgbFrames(dir, rows, k, 20_000_000L).attach(depthOf(3), 3))!!, 3.0)
        assertNull(RgbFrames(dir, rows, k, 20_000_000L).attach(depthOf(4), 4).guide)
        assertEquals(120, luma(RgbFrames(dir, rows, k, 100_000_000L).attach(depthOf(4), 4))!!, 3.0)
    }

    private fun assertEquals(expected: Int, actual: Int, tol: Double) = assertEquals(expected.toDouble(), actual.toDouble(), tol)
}
