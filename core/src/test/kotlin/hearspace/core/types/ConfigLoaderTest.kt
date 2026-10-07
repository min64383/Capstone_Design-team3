package hearspace.core.types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import hearspace.core.geometry.Vec3
import java.io.File

class ConfigLoaderTest {

    private val defaultJson: String by lazy {
        val path = System.getProperty("hearspace.defaultConfig")
            ?: error("system property hearspace.defaultConfig not set (run via Gradle)")
        File(path).readText()
    }

    private fun load(override: String? = null) = ConfigLoader.load(defaultJson, override)

    private fun assertConfigError(expectedPath: String, override: String) {
        val e = assertThrows<ConfigException> { load(override) }
        assertEquals(expectedPath, e.path, e.message)
    }

    @Test
    fun `default json matches spec section 12 table`() {
        val c = load()
        assertEquals(Vec3(0f, 0.5f, -0.39f), c.head.offsetFromCameraM)
        assertEquals(HeadingConfig(1.0f, 0.15f), c.heading)
        assertEquals(CorridorConfig(0.8f, 2.0f, 3.5f, 0.2f, 0.25f, 0.8f), c.corridor)
        assertEquals(DepthConfig(2, DepthSource.SMOOTHED, 0), c.depth)
        assertEquals(MapConfig(0.05f, 0.2f, 6, 0.1f, 0.15f, 0.3f, 1.0f, 10f, 5.0f), c.map)
        assertEquals(FloorConfig(0.5f, 0.05f, 0.08f, 0.02f, 0.2f, 200, 0.1f, 10), c.floor)
        assertEquals(ClusterConfig(0.15f, 5, 1.2f, 0.5f), c.cluster)
        assertEquals(
            FalsePositiveFilterConfig(false, 500, 0.60f, 0.80f, 1.00f, 1.40f, 0.20f, 1.70f),
            c.falsePositiveFilter,
        )
        assertEquals(TrackConfig(0.3f, 0.3f, 2, 3, 2, 0f, 0.3f), c.track)
        assertEquals(RepStrategy.CORRIDOR_NEAREST, c.repPoint.strategy)
        assertEquals(PolicyConfig(1.0f, 2.5f, 3.0f, 0.15f, 1, 300f), c.policy)
        assertEquals(StateConfig(10, 0.5f, 3.0f, 15.0f, 600f), c.state)
        assertEquals(
            AudioConfig(
                sampleRate = 48000,
                blockSize = 256,
                masterGainDb = -12f,
                toneHz = 880f,
                beepOnMs = 70f,
                nearPeriodMs = 180f,
                farPeriodMs = 900f,
                rearToneHz = 440f,
                maxItdMs = 0.66f,
                pulseMs = 30f,
                warnFarPeriodMs = 800f,
                warnNearPeriodMs = 250f,
                stopPeriodMs = 100f,
                stopGainDb = 6f,
                headToneHz = 3000f,
                alertGainDb = -6f,
                limiterCeiling = 0.9f,
                bufferBlocks = 8,
            ),
            c.audio,
        )
        assertEquals(HapticsConfig(40, 40, 80, 400, 600), c.haptics)
        assertEquals(RecordConfig(1, 3, 1.0f), c.record)
        assertEquals(AlignConfig(2.0f, 0.5f, 3.0f), c.align)
    }

    @Test
    fun `partial override changes only the given keys`() {
        val base = load()
        val c = load("""{ "corridor": { "widthM": 1.0 }, "repPoint": { "strategy": "NEAREST" } }""")
        assertEquals(base.corridor.copy(widthM = 1.0f), c.corridor)
        assertEquals(RepStrategy.NEAREST, c.repPoint.strategy)
        assertEquals(base.copy(corridor = c.corridor, repPoint = c.repPoint), c)
    }

    @Test
    fun `missing key is rejected`() {
        val json = defaultJson.replace(""""minHits": 6,""", "")
        val e = assertThrows<ConfigException> { ConfigLoader.load(json) }
        assertEquals("map.minHits", e.path)
    }

    @Test
    fun `missing section is rejected`() {
        val json = defaultJson.replace(""""align": { "fitLengthM": 2.0, "minTravelM": 0.5, "headingWindowS": 3.0 }""", """"unused": {}""")
        assertThrows<ConfigException> { ConfigLoader.load(json) }
    }

    @Test
    fun `unknown keys are rejected`() {
        assertConfigError("map.voxelSize", """{ "map": { "voxelSize": 0.1 } }""")
        assertConfigError("typo", """{ "typo": 1 }""")
    }

    @Test
    fun `type mismatches are rejected`() {
        assertConfigError("map.voxelSizeM", """{ "map": { "voxelSizeM": "0.05" } }""")
        assertConfigError("depth.subsample", """{ "depth": { "subsample": 1.5 } }""")
        assertConfigError("head.offsetFromCameraM", """{ "head": { "offsetFromCameraM": [0, 0] } }""")
        assertConfigError("repPoint.strategy", """{ "repPoint": { "strategy": "MIDDLE" } }""")
        assertConfigError("corridor", """{ "corridor": 1 }""")
    }

    @Test
    fun `out of range values are rejected`() {
        assertConfigError("map.voxelSizeM", """{ "map": { "voxelSizeM": 0 } }""")
        assertConfigError("track.emaAlpha", """{ "track": { "emaAlpha": 1.5 } }""")
        assertConfigError("track.minConfirmObservations", """{ "track": { "minConfirmObservations": 0 } }""")
        assertConfigError("track.maxMissedUpdates", """{ "track": { "maxMissedUpdates": -1 } }""")
        assertConfigError("track.minConfirmConfidence", """{ "track": { "minConfirmConfidence": 1.5 } }""")
        assertConfigError("track", """{ "track": { "suspiciousConfirmObservations": 1 } }""")
        assertConfigError("track", """{ "track": { "maxConfirmCentroidJumpM": 0.4 } }""")
        assertConfigError("falsePositiveFilter.minVoxels", """{ "falsePositiveFilter": { "minVoxels": 0 } }""")
        assertConfigError("policy.maxSources", """{ "policy": { "maxSources": 0 } }""")
        assertConfigError("policy", """{ "policy": { "warnMaxM": 3.5 } }""")
        assertConfigError("cluster", """{ "cluster": { "bodyMinM": 1.3 } }""")
        assertConfigError("depth.minConfidence", """{ "depth": { "minConfidence": 256 } }""")
        assertConfigError("depth.source", """{ "depth": { "source": "TOF" } }""")
        assertConfigError("floor", """{ "floor": { "belowMarginM": 0.04 } }""")
        assertConfigError("corridor", """{ "corridor": { "edgeInnerM": 0.4 } }""")
    }

    @Test
    fun `malformed json is reported as config error`() {
        val e = assertThrows<ConfigException> { load("""{ "map": """) }
        assertTrue(e.path == "<override>")
    }

    @Test
    fun `sonify defaults preserve baseline and risk preset opts in`() {
        assertEquals(SonifyMode.PULSE, load().sonify.mode)
        assertEquals(false, load().policy.prioritizeStop)
        val c = load("""{"sonify":{"mode":"RISK_CONTINUOUS"},"policy":{"maxSources":2,"prioritizeStop":true}}""")
        assertEquals(SonifyMode.RISK_CONTINUOUS, c.sonify.mode)
        assertEquals(2, c.policy.maxSources)
        assertEquals(true, c.policy.prioritizeStop)
    }

    @Test
    fun `invalid risk configuration is rejected`() {
        assertConfigError("sonify.mode", """{"sonify":{"mode":"UNKNOWN"}}""")
        assertConfigError("sonify", """{"sonify":{"minHistoryS":0.8}}""")
        assertConfigError("sonify", """{"sonify":{"approachOffMps":0.2}}""")
        assertConfigError("sonify", """{"sonify":{"maxHz":20000}}""")
        assertConfigError("sonify.duckDb", """{"sonify":{"duckDb":3}}""")
        assertConfigError("sonify.prototypeGain", """{"sonify":{"prototypeGain":2}}""")
    }
}
