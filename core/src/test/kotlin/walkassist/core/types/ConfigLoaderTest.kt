package walkassist.core.types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import walkassist.core.geometry.Vec3
import java.io.File

class ConfigLoaderTest {

    private val defaultJson: String by lazy {
        val path = System.getProperty("walkassist.defaultConfig")
            ?: error("system property walkassist.defaultConfig not set (run via Gradle)")
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
        assertEquals(Vec3(0f, 0f, 0f), c.head.offsetFromCameraM)
        assertEquals(HeadingConfig(1.0f, 0.15f), c.heading)
        assertEquals(CorridorConfig(0.8f, 2.0f, 3.5f, 0.2f), c.corridor)
        assertEquals(DepthConfig(2), c.depth)
        assertEquals(MapConfig(0.05f, 3, 0.1f, 0.15f, 0.3f, 1.0f, 10f, 5.0f), c.map)
        assertEquals(FloorConfig(0.5f, 0.05f), c.floor)
        assertEquals(ClusterConfig(0.15f, 5, 1.2f, 0.5f), c.cluster)
        assertEquals(TrackConfig(0.3f, 0.3f), c.track)
        assertEquals(RepStrategy.CORRIDOR_NEAREST, c.repPoint.strategy)
        assertEquals(PolicyConfig(1.0f, 2.5f, 3.0f, 0.15f, 1, 300f), c.policy)
        assertEquals(StateConfig(10, 0.5f, 3.0f), c.state)
        assertEquals(AudioConfig(48000, 256, -12f), c.audio)
        assertEquals(RecordConfig(1, 3, 1.0f), c.record)
        assertEquals(AlignConfig(2.0f), c.align)
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
        val json = defaultJson.replace(""""minHits": 3,""", "")
        val e = assertThrows<ConfigException> { ConfigLoader.load(json) }
        assertEquals("map.minHits", e.path)
    }

    @Test
    fun `missing section is rejected`() {
        val json = defaultJson.replace(""""align": { "fitLengthM": 2.0 }""", """"unused": {}""")
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
        assertConfigError("policy.maxSources", """{ "policy": { "maxSources": 0 } }""")
        assertConfigError("policy", """{ "policy": { "warnMaxM": 3.5 } }""")
        assertConfigError("cluster", """{ "cluster": { "bodyMinM": 1.3 } }""")
    }

    @Test
    fun `malformed json is reported as config error`() {
        val e = assertThrows<ConfigException> { load("""{ "map": """) }
        assertTrue(e.path == "<override>")
    }
}
