package hearspace.core.synth

import hearspace.core.calibration.ChestMountConfigMain
import hearspace.core.geometry.*
import hearspace.core.guidance.Heading
import hearspace.core.pipeline.FastPath
import hearspace.core.types.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.math.*

/** All dimensions/poses below are synthetic fixtures, not user harness measurements. */
class ChestMountCalibrationTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val profile = """{"minHorizontalNorm":0.1}"""
    private val measured = mapOf("rightCm" to "3", "upCm" to "50", "backCm" to "10", "yawDeg" to "15")
    private fun config() = ConfigLoader.load(base, ChestMountConfigMain.generate(base, profile, measured))
    private fun fwd(deg: Float): Vec3 {
        val a = Math.toRadians(deg.toDouble()); return Vec3(sin(a).toFloat(), 0f, -cos(a).toFloat())
    }
    private fun pose(bodyDeg: Float, t: Long, pitchDeg: Float = 10f, tracking: TrackingState = TrackingState.TRACKING): PoseFrame {
        val b = fwd(bodyDeg); val r = b cross Vec3.UP
        val cameraF = fwd(bodyDeg + 15f)
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val z = cameraF * cos(pitch) - Vec3.UP * sin(pitch)
        val x = cameraF cross Vec3.UP
        val camera = Vec3(0f, 1.5f, 0f) - r * .03f - Vec3.UP * .5f + b * .1f
        return PoseFrame(t, tracking, Mat4.fromAxes(x, z cross x, z, camera))
    }
    private fun assertHeading(expected: Vec3, actual: Vec3?) {
        assertNotNull(actual)
        assertTrue((expected - actual!!).norm() < 1e-4f, "expected $expected actual $actual")
    }

    @Test fun `default and old base config keep chest mode off`() {
        val c = ConfigLoader.load(base)
        assertNull(c.heading.chestMount)
        assertEquals(c.head.offsetFromCameraM, c.headOffsetFromCameraM)
        val old = base.replace(", \"chestMount\": null", "")
        assertEquals(c, ConfigLoader.load(old))
    }
    @Test fun `measurements are converted with body axes and override legacy geometry`() {
        val c = config()
        assertEquals(Vec3(.03f, .5f, -.1f), c.headOffsetFromCameraM)
        assertEquals(Vec3(0f, .5f, -.39f), c.head.offsetFromCameraM) // legacy slot intentionally unchanged
        assertEquals(15f, c.heading.chestMount!!.yawOffsetDeg)
    }
    @Test fun `all four measurements are mandatory and finite`() {
        for (key in measured.keys) assertThrows<IllegalArgumentException> {
            ChestMountConfigMain.generate(base, profile, measured - key)
        }
        for (bad in listOf("NaN", "Infinity", "1e300")) assertThrows<IllegalArgumentException> {
            ChestMountConfigMain.generate(base, profile, measured + ("upCm" to bad))
        }
        assertThrows<IllegalArgumentException> { ChestMountConfigMain.generate(base, profile, measured + ("guess" to "1")) }
    }
    @Test fun `existing device overrides are preserved except the chosen heading method`() {
        val existing = """{"policy":{"maxSources":2,"prioritizeStop":true},"heading":{"windowS":4,"cameraSigmaDeg":2.3}}"""
        val c = ConfigLoader.load(base, ChestMountConfigMain.generate(base, profile, measured, existing))
        assertEquals(2, c.policy.maxSources)
        assertTrue(c.policy.prioritizeStop)
        assertEquals(4f, c.heading.windowS)
        assertEquals(0f, c.heading.cameraSigmaDeg)
        assertEquals(Vec3(.03f, .5f, -.1f), c.headOffsetFromCameraM)
    }
    @Test fun `partial enabled bundle bad axes and fusion conflict are rejected`() {
        val bad = listOf(
            """{"heading":{"chestMount":{"yawOffsetDeg":0}}}""",
            """{"heading":{"chestMount":{"yawOffsetDeg":0,"headOffsetBodyM":[0,0,-0.1],"minHorizontalNorm":0.1}}}""",
            """{"heading":{"chestMount":{"yawOffsetDeg":181,"headOffsetBodyM":[0,0.5,-0.1],"minHorizontalNorm":0.1}}}""",
            """{"heading":{"chestMount":{"yawOffsetDeg":0,"headOffsetBodyM":[0,0.5,-0.1],"minHorizontalNorm":0}}}""",
            """{"heading":{"chestMount":{"yawOffsetDeg":0,"headOffsetBodyM":[0,0.5,-0.1],"minHorizontalNorm":1.1}}}""",
            """{"heading":{"chestMount":{"yawOffsetDeg":0,"headOffsetBodyM":[0,0.5],"minHorizontalNorm":0.1}}}""",
            """{"heading":{"cameraSigmaDeg":2.3,"chestMount":{"yawOffsetDeg":0,"headOffsetBodyM":[0,0.5,-0.1],"minHorizontalNorm":0.1}}}""",
        )
        for (o in bad) assertThrows<ConfigException> { ConfigLoader.load(base, o) }
    }
    @Test fun `standing turns follow the chest from a nonzero starting world yaw`() {
        for (turn in listOf(-180f, -90f, 90f, 180f)) {
            val cfg = config(); val h = Heading(cfg.heading)
            for (i in 0..120) {
                val yaw = 37f + turn * (i / 30f).coerceAtMost(1f)
                val p = pose(yaw, 1_000_000_000_000L + i * 33_333_333L)
                val chosen = h.update(p)
                assertHeading(fwd(yaw), chosen)
                val head = HeadPose.fromCamera(p.worldFromCam.translation(), chosen!!, cfg.headOffsetFromCameraM)
                assertTrue((head.positionW - Vec3(0f, 1.5f, 0f)).norm() < 1e-4f)
            }
        }
    }
    @Test fun `world reset preserves relative mount calibration`() {
        val h = Heading(config().heading)
        h.update(pose(37f, 1_000_000_000_000L))
        h.reset()
        assertNull(h.headingW)
        // Global world yaw changes, while relative mount geometry does not.
        assertHeading(fwd(112f), h.update(pose(112f, 1_000_100_000_000L)))
    }
    @Test fun `near vertical initialization waits and tracking loss does not calibrate`() {
        val h = Heading(config().heading)
        assertNull(h.update(pose(0f, 1L, tracking = TrackingState.PAUSED)))
        assertNull(h.update(pose(0f, 2L, pitchDeg = 90f)))
        assertHeading(fwd(0f), h.update(pose(0f, 3L)))
    }
    @Test fun `invalid chest direction after ready enters UNKNOWN and stops commands`() {
        val c = config(); val fast = FastPath(c)
        var last: GuidanceOutput? = null
        for (i in 0..c.state.recoverFrames + 2) {
            val p = pose(0f, 1_000_000_000_000L + i * 33_333_333L)
            val snap = ObstacleSnapshot(p.tCaptureNs, emptyList(), 0f, MapHealth.OK)
            last = fast.compute(p, snap, p.tCaptureNs)
            fast.takeMapAction()
        }
        assertEquals(GuidanceState.NORMAL, last!!.state)
        val upright = pose(0f, 1_001_000_000_000L)
        // Tilt the entire rigid body/mount, not just the camera's pitch around its yaw-offset right axis.
        val tilt = Quaternion.fromAxisAngle(Vec3(1f, 0f, 0f), (Math.PI / 2).toFloat()).toMat4(Vec3.ZERO)
        val p = upright.copy(worldFromCam = tilt * upright.worldFromCam)
        val out = fast.compute(p, ObstacleSnapshot(p.tCaptureNs, emptyList(), 0f, MapHealth.OK), p.tCaptureNs)
        assertEquals(GuidanceState.UNKNOWN, out.state)
        assertTrue(out.commands.isEmpty())
        assertNull(fast.headingW)
    }
}
