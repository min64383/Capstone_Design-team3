package hearspace.core.synth

import hearspace.core.geometry.*
import hearspace.core.guidance.*
import hearspace.core.types.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** Synthetic supported surfaces and noise; no physical accuracy claims. */
class NearStopTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val mount = """{"heading":{"chestMount":{"yawOffsetDeg":0,"headOffsetBodyM":[0,0.5,-0.15],"minHorizontalNorm":0.1}}}"""
    private val profile = File(File(System.getProperty("hearspace.defaultConfig")).parentFile, "near-stop.experimental.override.json").readText()
    private fun config() = ConfigLoader.load(base, listOf(mount, profile))
    private val t0 = 1_000_000_000_000L
    private val frameNs = 33_333_333L
    private val forward = Vec3(0f, 0f, -1f)
    private fun corridor(c: Config) = Corridor(Vec3(0f, 1.5f, 0f), forward, 0f, c.corridor)
    private fun patch(x: Float = 0f, z: Float = -.8f): List<Vec3> = (0..6).flatMap { ix ->
        (0..14).map { iy -> Vec3(x - .15f + ix * .05f, .2f + iy * .05f, z) }
    }
    private fun confirmed(d: NearStopDetector, c: Config): Obstacle {
        assertTrue(d.update(patch(), t0, corridor(c), emptyList()).isEmpty())
        return d.update(patch(), t0 + frameNs, corridor(c), emptyList()).single()
    }
    @Test fun `default remains off and old configuration missing the key loads`() {
        val c = ConfigLoader.load(base)
        assertNull(c.policy.nearStop)
        assertEquals(c, ConfigLoader.load(base.replace(",\n    \"nearStop\": null", "")))
    }
    @Test fun `unsafe combinations and single frame confirmation are rejected`() {
        assertThrows<ConfigException> { ConfigLoader.load(base, profile) }
        for (extra in listOf(
            """{"policy":{"nearStop":{"confirmFrames":1}}}""",
            """{"policy":{"nearStop":{"maxAgeMs":999}}}""",
            """{"policy":{"prioritizeStop":false}}""",
            """{"map":{"mode":"LOG_ODDS"}}""",
            """{"policy":{"nearStop":{"minVoxels":1}}}""",
            """{"policy":{"nearStop":{"maxGapMs":1e100}}}"""
        )) assertThrows<ConfigException> { ConfigLoader.load(base, listOf(mount, profile, extra)) }
    }
    @Test fun `supported close patch needs two distinct observations and has a stable negative id`() {
        val c = config(); val d = NearStopDetector(c); val o = confirmed(d, c)
        assertTrue(o.earlyStop && o.id < 0 && o.nObservations == 2)
        val next = d.update(patch(), t0 + 2 * frameNs, corridor(c), emptyList()).single()
        assertEquals(o.id, next.id)
        assertEquals(t0 + 2 * frameNs, next.lastSeenNs)
    }
    @Test fun `one frame patch repeated depth and nonincreasing time cannot confirm`() {
        val c = config(); val d = NearStopDetector(c)
        assertTrue(d.update(patch(), t0, corridor(c), emptyList()).isEmpty())
        repeat(5) { assertTrue(d.update(patch(), t0, corridor(c), emptyList()).isEmpty()) }
        assertTrue(d.update(patch(), t0 - 1, corridor(c), emptyList()).isEmpty())
        assertTrue(d.update(emptyList(), t0 + frameNs, corridor(c), emptyList()).isEmpty())
        assertTrue(d.update(patch(), t0 + 2 * frameNs, corridor(c), emptyList()).isEmpty())
    }
    @Test fun `absent observations long gaps and reset remove early warnings immediately`() {
        val c = config(); val d = NearStopDetector(c); confirmed(d, c)
        assertTrue(d.update(emptyList(), t0 + 2 * frameNs, corridor(c), emptyList()).isEmpty())
        assertTrue(d.update(patch(), t0 + 3 * frameNs, corridor(c), emptyList()).isEmpty())
        assertTrue(d.update(patch(), t0 + 1_000_000_000L, corridor(c), emptyList()).isEmpty())
        d.reset()
        assertTrue(d.update(patch(), t0 + 2_000_000_000L, corridor(c), emptyList()).isEmpty())
    }
    @Test fun `floor side walls distant patches and pixels on one cell never warn`() {
        val c = config()
        val floor = (0..12).flatMap { x -> (0..12).map { z -> Vec3(-.3f + x * .05f, .02f, -.2f - z * .05f) } }
        val side = patch(x = .7f)
        val far = patch(z = -1.5f)
        val duplicate = List(1000) { Vec3(0f, .4f, -.8f) }
        val speckles = listOf(Vec3(-.3f, .3f, -.3f), Vec3(.3f, .8f, -.8f))
        for (points in listOf(floor, side, far, duplicate, speckles)) {
            val d = NearStopDetector(c)
            repeat(4) { assertTrue(d.update(points, t0 + it * frameNs, corridor(c), emptyList()).isEmpty()) }
        }
    }
    @Test fun `confirmed normal surface suppresses duplicate early output`() {
        val c = config(); val d = NearStopDetector(c); val early = confirmed(d, c)
        val normal = early.copy(id = 1, earlyStop = false)
        assertTrue(d.update(patch(), t0 + 2 * frameNs, corridor(c), listOf(normal)).isEmpty())
    }
    @Test fun `early warning is STOP only and expires independently of a fresh snapshot`() {
        val c = config(); val o = confirmed(NearStopDetector(c), c)
        fun commands(head: HeadPose, snapNs: Long, ageMs: Float = 10f) =
            Policy(c.policy, c.corridor.behindM, c.corridor.widthM).commands(ObstacleSnapshot(snapNs, listOf(o), 0f, MapHealth.OK), head, ageMs)
        val head = HeadPose(Vec3(0f, 1.5f, 0f), forward)
        assertEquals(Band.STOP, commands(head, o.lastSeenNs).single().band)
        assertTrue(commands(head, o.lastSeenNs + 200_000_000L).isEmpty())
        assertTrue(commands(head, o.lastSeenNs - 200_000_000L).isEmpty())
        assertTrue(commands(head, o.lastSeenNs, 600f).isEmpty())
        assertTrue(commands(head.copy(positionW = head.positionW + Vec3(0f, 0f, .5f)), o.lastSeenNs).isEmpty())
        assertTrue(commands(head.copy(headingW = Vec3(1f, 0f, 0f)), o.lastSeenNs).isEmpty())
        assertTrue(commands(head.copy(headingW = -forward), o.lastSeenNs).isEmpty())
    }
    @Test fun `early candidate cannot displace a confirmed STOP when there is one source`() {
        val c = config(); val early = confirmed(NearStopDetector(c), c)
        val normal = early.copy(id = 1, repPointW = Vec3(0f, .5f, -.95f), earlyStop = false)
        val p = Policy(c.policy.copy(maxSources = 1), c.corridor.behindM, c.corridor.widthM)
        val g = p.commands(ObstacleSnapshot(early.lastSeenNs, listOf(early, normal), 0f, MapHealth.OK), HeadPose(Vec3(0f, 1.5f, 0f), forward), 10f)
        assertEquals(1, g.single().obstacleId)
        assertEquals(Band.STOP, g.single().band)
    }
}
