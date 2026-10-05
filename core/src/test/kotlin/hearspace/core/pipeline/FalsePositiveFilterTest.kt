package hearspace.core.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import hearspace.core.geometry.Vec3
import hearspace.core.types.FalsePositiveFilterConfig
import hearspace.core.types.HeightClass

class FalsePositiveFilterTest {
    private val cfg = FalsePositiveFilterConfig(
        enabled = true,
        minVoxels = 500,
        minLateralSpanM = 0.60f,
        minAlongSpanM = 0.80f,
        minAlongMinM = 1.00f,
        minHeightSpanM = 1.40f,
        maxHeightMinM = 0.20f,
        minHeightMaxM = 1.70f,
    )

    private fun d(
        n: Int = 900,
        lat0: Float = -0.38f,
        lat1: Float = 0.38f,
        along0: Float = 1.0f,
        along1: Float = 2.2f,
        h0: Float = 0.05f,
        h1: Float = 1.95f,
    ) = ClusterDebug(
        tCaptureNs = 1,
        clusterIndex = 0,
        nVoxels = n,
        heightClass = HeightClass.FLOOR,
        aabbMinW = Vec3(-1f, 0f, -1f),
        aabbMaxW = Vec3(1f, 2f, 1f),
        lateralMinM = lat0,
        lateralMaxM = lat1,
        alongMinM = along0,
        alongMaxM = along1,
        heightMinM = h0,
        heightMaxM = h1,
        centroidW = Vec3(0f, 0f, 0f),
        nearestW = Vec3(0f, 0f, 0f),
        corridorNearestW = Vec3(0f, 0f, 0f),
        scoreMin = 0.8f,
        scoreMean = 0.9f,
        scoreMax = 1f,
        hitsMin = 6,
        hitsMean = 12f,
        hitsMax = 20,
        oldestVoxelAgeMs = 2000f,
        newestVoxelAgeMs = 0f,
    )

    @Test
    fun `full corridor depth sheet is rejected`() {
        assertEquals(FalsePositiveFilter.REASON, FalsePositiveFilter.reason(d(), cfg))
    }

    @Test
    fun `compact obstacle is kept`() {
        assertNull(FalsePositiveFilter.reason(
            d(n = 70, lat0 = -0.2f, lat1 = 0.2f, along0 = 1.1f, along1 = 1.5f, h0 = 0.05f, h1 = 0.7f),
            cfg,
        ))
    }

    @Test
    fun `blocking wall with small depth thickness is kept`() {
        assertNull(FalsePositiveFilter.reason(
            d(n = 900, lat0 = -0.38f, lat1 = 0.38f, along0 = 1.8f, along1 = 2.0f, h0 = 0.05f, h1 = 1.95f),
            cfg,
        ))
    }


    @Test
    fun `near full-height sheet is kept for safety`() {
        assertNull(FalsePositiveFilter.reason(
            d(n = 1800, lat0 = -0.38f, lat1 = 0.38f, along0 = 0.8f, along1 = 3.2f, h0 = 0.05f, h1 = 1.95f),
            cfg,
        ))
    }

    @Test
    fun `disabled filter keeps everything`() {
        assertNull(FalsePositiveFilter.reason(d(), cfg.copy(enabled = false)))
    }

    @Test
    fun `shape is still matched when the filter is disabled, for longer confirmation`() {
        assertTrue(FalsePositiveFilter.matches(d(), cfg.copy(enabled = false)))
        assertFalse(FalsePositiveFilter.matches(d(along0 = 0.8f), cfg.copy(enabled = false)))
    }
}
