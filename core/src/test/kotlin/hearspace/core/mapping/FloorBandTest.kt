package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M35 영역별 바닥면(`floor.decompose` REGION_PLANE): 지도 칸에 맞춘 바닥면으로 바닥 칸을 뺀다.
 * 합성: 1° 기운 바닥(2 m × 2 m, 칸 5 cm), 전역 바닥 추정이 실제보다 5 cm 낮음(M35 설계 표 1의 추정 편향), 벽, 높이 0.25 m 낮은 상자.
 */
class FloorBandTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val voxel = 0.05f

    private fun cell(x: Float, y: Float, z: Float) = VoxelView(
        kotlin.math.floor(x / voxel).toInt(), kotlin.math.floor(y / voxel).toInt(), kotlin.math.floor(z / voxel).toInt(),
        Vec3(x, y, z), 3, 1f, 0L, 0f,
    )

    private val slope = kotlin.math.tan(Math.toRadians(1.0)).toFloat()
    private fun floorAt(x: Float) = slope * x

    private val floor = (0 until 40).flatMap { i -> (0 until 40).map { k -> cell(0.025f + i * voxel, floorAt(0.025f + i * voxel), 0.025f + k * voxel) } }
    private val wall = (0 until 40).flatMap { k -> (1..30).map { j -> cell(1.975f, floorAt(1.975f) + j * voxel, 0.025f + k * voxel) } }
    private val box = (10 until 16).flatMap { i -> (10 until 16).flatMap { k -> (1..5).map { j ->
        cell(0.025f + i * voxel, floorAt(0.025f + i * voxel) + j * voxel, 0.025f + k * voxel) } } }
    private val camera = Vec3(1f, 1.3f, -1f)

    @Test
    fun `region plane removes the floor that a biased global floor leaves, and keeps wall and box`() {
        val all = floor + wall + box
        val floorY = -0.05f
        val band = ConfigLoader.load(base, """{ "map": { "mode": "TRISTATE" } }""")
        val region = ConfigLoader.load(base, """{ "map": { "mode": "TRISTATE" }, "floor": { "decompose": "REGION_PLANE" } }""")
        val leftBand = withoutFloorBand(all, floorY, camera, band).toSet()
        val leftRegion = withoutFloorBand(all, floorY, camera, region).toSet()
        val floorLeftBand = floor.count { it in leftBand }
        val floorLeftRegion = floor.count { it in leftRegion }
        assertTrue(floorLeftBand > 100) { "biased global floor should leave floor cells (reproduce), left $floorLeftBand" }
        assertEquals(0, floorLeftRegion) { "region plane should remove all floor cells" }
        val highWall = wall.filter { it.centerW.y - floorAt(it.centerW.x) > 0.3f } // 띠는 거리에 따라 넓어진다(3.3 m에서 2σ ≈ 0.22 m)
        assertTrue(highWall.all { it in leftRegion }) { "high wall kept ${highWall.count { it in leftRegion }}/${highWall.size}" }
        val boxTop = box.filter { it.centerW.y - floorAt(it.centerW.x) > 0.1f } // 상자 자리(2 m)의 띠 2σ ≈ 0.08 m
        assertTrue(boxTop.isNotEmpty() && boxTop.all { it in leftRegion }) { "box top kept ${boxTop.count { it in leftRegion }}/${boxTop.size}" }
    }

    @Test
    fun `plane fit recovers the tilt`() {
        val pl = fitPlane(floor.map { it.centerW })!!
        assertEquals(0f, pl[0], 1e-4f)
        assertEquals(slope, pl[1], 1e-4f)
        assertEquals(0f, pl[2], 1e-4f)
    }
}
