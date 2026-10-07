package hearspace.core.frontend

import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 주사선 하나로 본 경계 판정(IMPROVE_SPEC §6.1.1 M13.1 ①, 13번 계획서 그림 2). */
class EdgeDetectorTest {
    private val cfg = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText()).frontend

    /** [depth]의 경계 픽셀 위치. 높이는 모두 카메라 높이(수평면 예외가 걸리지 않게). */
    private fun marked(depth: List<Float>, heights: List<Float> = List(depth.size) { 0f }): List<Int> {
        val out = ArrayList<Int>()
        EdgeDetector.scan(depth.toFloatArray(), heights.toFloatArray(), depth.size, cfg) { out += it }
        return out.sorted()
    }

    private fun flat(n: Int, z: Float) = List(n) { z }

    @Test
    fun `short ramp between object and wall is a boundary, the flat ends are not`() {
        // 앞 물체 2.4 m 20픽셀, 4픽셀 경사(평활 반경 2의 상자 평균: 1/5~4/5 섞임), 뒤 벽 4.4 m 20픽셀
        val ramp = (1..4).map { 2.4f + 2.0f * it / 5f }
        assertEquals(listOf(20, 21, 22, 23), marked(flat(20, 2.4f) + ramp + flat(20, 4.4f)))
    }

    @Test
    fun `oblique wall is one long ramp and is kept`() {
        assertEquals(emptyList<Int>(), marked(List(60) { 1.0f + 0.05f * it }))
    }

    @Test
    fun `a clean step has no pixel in between`() {
        assertEquals(emptyList<Int>(), marked(flat(20, 2.4f) + flat(20, 4.4f)))
    }

    @Test
    fun `a pole narrower than the smoothing is smeared but kept`() {
        // 배경 3 m 사이 V자(기둥 1픽셀이 평활로 번짐): 양쪽이 같은 배경이라 불연속이 아님
        assertEquals(emptyList<Int>(), marked(flat(20, 3f) + listOf(2.8f, 2.6f, 2.5f, 2.6f, 2.8f) + flat(20, 3f)))
    }

    @Test
    fun `a small step below the step ratio is not a boundary`() {
        val ramp = (1..4).map { 2.4f + 0.15f * it / 5f }
        assertEquals(emptyList<Int>(), marked(flat(20, 2.4f) + ramp + flat(20, 2.55f)))
    }

    @Test
    fun `a level ramp well below the camera is a real top surface and is kept`() {
        // 상자 윗면(카메라 아래 0.5 m 같은 높이)을 비스듬히 본 짧은 경사(픽셀당 약 12%)
        val depth = flat(20, 2.0f) + listOf(2.3f, 2.6f, 2.9f) + flat(20, 4.0f)
        val level = List(20) { -0.6f } + listOf(-0.5f, -0.5f, -0.5f) + List(20) { -0.2f }
        assertEquals(emptyList<Int>(), marked(depth, level))
        // 같은 경사가 카메라 높이에 있으면(막의 높이가 같아 보이는 경우) 경계
        assertEquals(listOf(20, 21, 22), marked(depth, List(depth.size) { 0f }))
        // 8° 내려다보는 광선을 따라 늘어선 짧은 막: 높이 차는 0.03~0.04 m뿐이지만 깊이 변화 × tan 8°라 수평면이 아님(M13.1a 실측 E01h)
        val membrane = flat(20, 2.2f) + listOf(2.4f, 2.7f) + flat(20, 3.4f)
        val tilt = membrane.map { -0.5f - 0.14f * (it - 2.2f) }
        assertEquals(listOf(20, 21), marked(membrane, tilt))
    }

    @Test
    fun `real smoothed depth - the ramp after a gently pulled background is a boundary`() {
        // M13.1a 실측 E01h 151747 한 프레임 39행: 뒤 벽(약 3.8 m)이 캐리어 쪽으로 완만히 끌려오다(3.42 m) 짧은 경사로 캐리어(약 2.17 m)
        val row = listOf(3.74f, 3.77f, 3.80f, 3.80f, 3.77f, 3.76f, 3.76f, 3.69f, 3.68f, 3.62f, 3.56f, 3.51f, 3.47f, 3.42f,
            2.75f, 2.40f, 2.29f, 2.16f, 2.14f, 2.16f, 2.16f, 2.17f, 2.17f, 2.18f, 2.18f, 2.18f)
        val m = marked(row)
        assertTrue(m.containsAll(listOf(14, 15)) && m.all { it in 14..16 }, "marked $m")
    }

    @Test
    fun `invalid pixels split the line and nothing is marked across them`() {
        val ramp = (1..4).map { 2.4f + 2.0f * it / 5f }
        assertTrue(marked(flat(20, 2.4f) + listOf(0f) + ramp + flat(20, 4.4f)).isEmpty())
    }
}
