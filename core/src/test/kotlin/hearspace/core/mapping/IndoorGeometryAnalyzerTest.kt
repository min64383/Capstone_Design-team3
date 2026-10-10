package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.tracking.Cluster
import hearspace.core.types.ConfigLoader
import hearspace.core.types.IndoorGeometryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class IndoorGeometryAnalyzerTest {
    private val config = ConfigLoader.load(
        File(System.getProperty("hearspace.defaultConfig")).readText(),
        """{"geometry":{"enabled":true}}""",
    )

    private fun ground(yAt: (Int) -> Float): FloatArray {
        val out = ArrayList<Float>()
        for (z in 1..12) for (x in -3..3) {
            out += x * 0.08f
            out += yAt(z)
            out += -z * 0.20f
        }
        return out.toFloatArray()
    }

    private fun analyze(points: FloatArray) = IndoorGeometryAnalyzer.analyze(
        pointsW = points,
        occupied = emptyList(),
        userPosW = Vec3.ZERO,
        headingW = Vec3(0f, 0f, -1f),
        floorY = 0f,
        corridorWidthM = 0.8f,
        maxAlongM = 3f,
        floorToleranceM = 0.05f,
        cluster = config.cluster,
        cfg = config.geometry,
    )

    @Test
    fun `flat floor is flat`() {
        assertEquals(IndoorGeometryType.FLAT, analyze(ground { 0f }).primary)
    }

    @Test
    fun `single down step is detected`() {
        val r = analyze(ground { if (it <= 6) 0f else -0.20f })
        assertTrue(r.findings.any { it.type == IndoorGeometryType.STEP_DOWN })
    }

    @Test
    fun `up slope is detected without a sharp step`() {
        val r = analyze(ground { it * 0.025f })
        assertTrue(r.findings.any { it.type == IndoorGeometryType.SLOPE_UP })
    }
}

class WeakBridgeClusterTest {
    @Test
    fun `weak voxels may attach but cannot bridge two strong groups`() {
        val points = listOf(
            Vec3(0f, 0f, 0f), Vec3(0.05f, 0f, 0f), Vec3(0.10f, 0f, 0f), Vec3(0.15f, 0f, 0f), Vec3(0.20f, 0f, 0f),
            Vec3(0.35f, 0f, 0f), Vec3(0.50f, 0f, 0f),
            Vec3(0.65f, 0f, 0f), Vec3(0.70f, 0f, 0f), Vec3(0.75f, 0f, 0f), Vec3(0.80f, 0f, 0f), Vec3(0.85f, 0f, 0f),
        )
        val strong = BooleanArray(points.size) { it !in 5..6 }
        val clusters = Cluster.dbscanXZ(points, 0.16f, 2, strong)
        assertEquals(2, clusters.size)
    }
}

class CurrentEvidenceTest {
    private val base = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())

    @Test
    fun `confirmed free space removes current evidence without relying on historical hits`() {
        val cfg = base.map.copy(
            mode = hearspace.core.types.MapMode.HITS,
            hitWeighting = hearspace.core.types.HitWeighting.NONE,
            freeEvidenceDecay = 1f,
        )
        val m = VoxelMap(cfg)
        val p = Vec3(0f, 0f, -2f)
        repeat(cfg.minHits) { i -> m.beginFrame(); m.insert(p, i.toLong()) }
        assertEquals(1, m.occupied().size)

        val k = hearspace.core.types.Intrinsics(100f, 100f, 50f, 50f, 101, 101)
        val worldFromCam = hearspace.core.geometry.Mat4(floatArrayOf(1f,0f,0f,0f, 0f,-1f,0f,0f, 0f,0f,-1f,0f, 0f,0f,0f,1f))
        m.beginFrame()
        m.decayFree(ShortArray(k.width * k.height) { 3000.toShort() }, k, worldFromCam.rigidInverse())
        assertTrue(m.occupied().isEmpty(), "one confirmed free observation should drop a just-confirmed ghost below the active evidence threshold")
        assertTrue(m.views().single().hits >= cfg.minHits, "historical hits stay available for diagnostics")
    }
}
