package hearspace.core.frontend

import hearspace.core.geometry.Vec3
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuideImage
import hearspace.core.types.Intrinsics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** RGB 안내 깊이 보정(IMPROVE_SPEC §6.1.1 M13.7) 단위 시험. 깊이와 RGB가 같은 해상도·K인 작은 영상, 막 후보는 경계 마스크. */
class RgbGuideTest {
    private val cfg = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText()).frontend
    private val w = 21
    private val h = 7
    private val k = Intrinsics(20f, 20f, 10f, 3f, w, h)

    /** 카메라가 수평으로 앞을 볼 때 C_cv에서 월드 위쪽 = −Y. */
    private fun refine(d: ShortArray, g: GuideImage) = RgbGuide.snap(d, k, g, cfg, EdgeDetector.boundaryMask(d, k, Vec3(0f, -1f, 0f), cfg)).depthMm

    private fun image(depth: (Int) -> Int, luma: (Int) -> Int): Pair<ShortArray, GuideImage> {
        val d = ShortArray(w * h) { depth(it % w).toShort() }
        val l = ByteArray(w * h) { luma(it % w).toByte() }
        return d to GuideImage(0, l, k)
    }

    @Test
    fun `membrane pixels snap to the side with the same colour`() {
        // 0~8열: 앞 물체 1.0 m(밝기 200), 12~20열: 뒤 벽 3.0 m(밝기 60), 9~11열: 평활 깊이의 막 1.5·2.0·2.5 m.
        // RGB는 선명해서 9·10열은 물체 색, 11열은 벽 색이다
        val membrane = mapOf(9 to 1500, 10 to 2000, 11 to 2500)
        val (d, g) = image({ c -> membrane[c] ?: if (c < 9) 1000 else 3000 }, { c -> if (c <= 10) 200 else 60 })
        val out = refine(d, g)
        val row = 3 * w
        assertEquals(1000, out[row + 9].toInt())
        assertEquals(1000, out[row + 10].toInt())
        assertEquals(3000, out[row + 11].toInt())
        for (c in listOf(0, 8, 12, 20)) assertEquals(d[row + c], out[row + c], "column $c unchanged")
    }

    @Test
    fun `a one-pixel thin near object without colour contrast is kept`() {
        // 대비가 없으면 가중 중앙값은 보통 중앙값이라, 가장 앞 무리(얇은 기둥)는 건드리지 않아야 한다
        val (d, g) = image({ c -> if (c == 10) 1000 else 3000 }, { 128 })
        val out = refine(d, g)
        for (r in 0 until h) assertEquals(1000, out[r * w + 10].toInt(), "row $r")
    }

    @Test
    fun `invalid depth is not filled`() {
        val (d, g) = image({ c -> if (c == 10) 0 else if (c < 10) 1000 else 3000 }, { c -> if (c < 10) 200 else 60 })
        val out = refine(d, g)
        for (r in 0 until h) assertEquals(0, out[r * w + 10].toInt())
    }
}
