package hearspace.core.frontend

import hearspace.core.geometry.Vec3
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuideImage
import hearspace.core.types.Intrinsics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** RGB 경계로 막 옮기기(M23) 단위 시험. RGB는 깊이의 4배 해상도(깊이 열 u의 중심 = RGB 열 4u + 1.5), 모든 행이 같은 작은 영상. */
class RgbEdgeTest {
    private val cfg = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText()).frontend
    private val w = 41
    private val h = 7
    private val k = Intrinsics(20f, 20f, 10f, 3f, w, h)
    private val g = Intrinsics(80f, 80f, 4 * 10f + 1.5f, 4 * 3f + 1.5f, 4 * w, 4 * h)

    /** 깊이(열 → mm)와 밝기(RGB 열 → 0~255). */
    private fun refine(depth: (Int) -> Int, luma: (Int) -> Int, margin: Boolean = false, floor: Boolean = false): Pair<ShortArray, RgbEdge.Result> {
        val d = ShortArray(w * h) { depth(it % w).toShort() }
        val l = ByteArray(g.width * g.height) { luma(it % g.width).toByte() }
        return d to RgbEdge.refine(d, k, GuideImage(0, l, g), Vec3(0f, -1f, 0f), cfg, margin) { _, _ -> floor }
    }

    // 0~8열: 앞 물체 1.0 m, 9~11열: 막 1.4·1.8·2.2 m, 12열부터 뒤 면
    private val ramp = mapOf(9 to 1400, 10 to 1800, 11 to 2200)
    // RGB 경계는 RGB 열 42와 43 사이 = 깊이 열 10.25: 10열은 물체, 11열은 배경
    private val lumaEdge = { x: Int -> if (x <= 42) 200 else 60 }
    private val row = 3 * w

    @Test
    fun `membrane pixels move to the surface on their side of the RGB edge`() {
        val (_, r) = refine({ c -> ramp[c] ?: if (c < 9) 1000 else 3000 }, lumaEdge)
        assertEquals(1000, r.depthMm[row + 9].toInt())
        assertEquals(1000, r.depthMm[row + 10].toInt())
        assertEquals(3000, r.depthMm[row + 11].toInt())
        assertTrue(r.nRgb > 0)
    }

    @Test
    fun `no RGB contrast and floor boundaries are left to the weighted median`() {
        val (d, flat) = refine({ c -> ramp[c] ?: if (c < 9) 1000 else 3000 }, { 128 })
        assertFalse(flat.moved.any { it })
        assertTrue(flat.mask[row + 10], "membrane is still a candidate")
        val (_, floor) = refine({ c -> ramp[c] ?: if (c < 9) 1000 else 3000 }, lumaEdge, floor = true)
        assertFalse(floor.moved.any { it })
        assertEquals(floor.nEdges, floor.nFloor)
        assertEquals(d[row + 10], floor.depthMm[row + 10])
    }

    // 배경 당김: 12~23열이 3.0 m에서 앞으로 끌려옴(12열 2.6 m, 화소당 약 1.2%로 완만), 24열부터 3.0 m
    private val pulled = { c: Int -> ramp[c] ?: if (c < 9) 1000 else if (c < 24) 3000 - 400 * (24 - c) / 12 else 3000 }

    @Test
    fun `margin variant moves the background pull to the background plane and stops where it returns`() {
        val (d, ra) = refine(pulled, lumaEdge)
        val (_, rb) = refine(pulled, lumaEdge, margin = true)
        for (c in 12..21) {
            assertEquals(d[row + c], ra.depthMm[row + c], "Ra leaves column $c")
            assertEquals(3000f, rb.depthMm[row + c].toFloat(), 2f, "Rb column $c")
        }
        assertEquals(3000f, rb.depthMm[row + 11].toFloat(), 2f, "far membrane pixel goes to the background plane, not the pulled line")
        assertEquals(1000, rb.depthMm[row + 10].toInt())
        assertEquals(d[row + 23], rb.depthMm[row + 23], "within edgeFitTolRatio of the background: unchanged")
        for (c in 0..8) assertEquals(1000, rb.depthMm[row + c].toInt(), "object column $c")
    }

    @Test
    fun `margin stops when the object colour reappears`() {
        // 16열(RGB 65~68)부터 다시 물체 밝기: 그 앞까지만 옮긴다
        val (d, rb) = refine(pulled, { x -> if (x <= 42 || x >= 65) 200 else 60 }, margin = true)
        assertEquals(3000f, rb.depthMm[row + 15].toFloat(), 2f)
        assertEquals(d[row + 16], rb.depthMm[row + 16])
    }
}
