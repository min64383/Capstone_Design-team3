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
        """{"floor":{"mode":"ELEVATION"}}""",
    )

    private fun ground(yAt: (Int) -> Float): FloatArray {
        val out = ArrayList<Float>()
        for (z in 0..9) for (x in -3..3) {
            out += x * 0.08f
            out += yAt(z)
            out += -(z * 0.20f + 0.10f)
        }
        return out.toFloatArray()
    }

    private fun analyze(points: FloatArray, voxels: List<VoxelView> = emptyList(), floorY: Float = 0f) = IndoorGeometryAnalyzer.analyze(
        pointsW = points,
        occupied = voxels,
        userPosW = Vec3.ZERO,
        headingW = Vec3(0f, 0f, -1f),
        floorY = floorY,
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

    private fun voxel(x: Float, z: Float, y: Float = 0.5f, wall: Boolean = false) =
        VoxelView((x * 20).toInt(), (y * 20).toInt(), (z * 20).toInt(), Vec3(x, y, -z), 10, 1f, 0L, 0f, if (wall) 10 else 0)

    @Test
    fun `single up step and both edges of platform survive`() {
        assertTrue(analyze(ground { if (it < 4) 0f else 0.2f }).findings.any { it.type == IndoorGeometryType.STEP_UP })
        val platform = analyze(ground { if (it in 3..6) 0.2f else 0f })
        assertTrue(platform.findings.any { it.type == IndoorGeometryType.STEP_UP })
        assertTrue(platform.findings.any { it.type == IndoorGeometryType.STEP_DOWN })
    }

    @Test
    fun `down slope is detected`() {
        assertTrue(analyze(ground { -it * 0.025f }).findings.any { it.type == IndoorGeometryType.SLOPE_DOWN })
    }

    @Test
    fun `truncated ground is unknown and missing depth alone is not floor loss`() {
        val r = analyze(ground { 0f }.take(5 * 7 * 3).toFloatArray())
        assertEquals(IndoorGeometryType.UNKNOWN, r.primary)
        assertTrue(r.findings.none { it.type == IndoorGeometryType.FLAT || it.type == IndoorGeometryType.FLOOR_LOSS })
        assertEquals(IndoorGeometryType.UNKNOWN, analyze(floatArrayOf()).primary)
    }

    @Test
    fun `supported internal ground gap is only a floor loss candidate`() {
        val points = ground { 0f }.toList().chunked(21).filterIndexed { i, _ -> i !in 3..5 }.flatten().toFloatArray()
        assertTrue(analyze(points).findings.any { it.type == IndoorGeometryType.FLOOR_LOSS })
    }

    @Test
    fun `one connected central obstacle is occupied not a narrow passage`() {
        val obstacle = (-3..3).map { voxel(it * 0.05f, 1f) }
        val r = analyze(ground { 0f }, obstacle)
        assertEquals(IndoorGeometryType.OCCUPIED, r.primary)
        assertTrue(r.findings.none { it.type == IndoorGeometryType.NARROW_PASSAGE })
    }

    @Test
    fun `independent side boundaries form narrow passage`() {
        val walls = listOf(-0.3f, 0.3f).flatMap { x -> (0..9).map { voxel(x, it * 0.2f + 0.1f, wall = true) } }
        assertTrue(analyze(ground { 0f }, walls).findings.any { it.type == IndoorGeometryType.NARROW_PASSAGE })
    }

    @Test
    fun `side walls outside profile ROI cannot become overhang`() {
        val r = analyze(ground { 0f }, (0..9).map { voxel(0.6f, it * 0.1f, 1.5f) })
        assertTrue(r.findings.none { it.type == IndoorGeometryType.OVERHANG })
    }

    @Test
    fun `contiguous opening has one finding`() {
        val walls = listOf(-0.38f, 0.38f).flatMap { x -> (0..9).map { voxel(x, it * 0.2f + 0.1f, wall = true) } }
        assertEquals(1, analyze(ground { 0f }, walls).findings.count { it.type == IndoorGeometryType.OPENING })
    }

    @Test
    fun `histogram ties use floor height and are invariant to world translation`() {
        fun tied(offset: Float) = ground { offset } + ground { offset + 0.2f }
        val atZero = analyze(tied(0f))
        val translated = analyze(tied(-1f), floorY = -1f)
        assertTrue(translated.groundProfile.all { kotlin.math.abs(it.heightM!! + 1f) < 1e-5f })
        assertEquals(atZero.primary, translated.primary)
    }

    @Test
    fun `geometry ignores unreliable distant ground and occupancy`() {
        val near = ground { 0f }
        val far = floatArrayOf(-0.1f, 0.4f, -2.5f, 0f, 0.4f, -2.5f, 0.1f, 0.4f, -2.5f)
        val r = analyze(near + far, (0..9).map { voxel(0f, 2.5f, 1.5f) })
        assertEquals(IndoorGeometryType.FLAT, r.primary)
        assertTrue(r.groundProfile.all { it.alongEndM <= config.geometry.maxDistanceM })
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
