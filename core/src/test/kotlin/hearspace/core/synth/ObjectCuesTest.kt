package hearspace.core.synth

import hearspace.core.audio.*
import hearspace.core.geometry.Vec3
import hearspace.core.types.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ObjectCuesTest {
    private val settings = ObjectCueSettings(300, 1000, 400, 1000, 0.5f,
        ObjectCueTone(650.0, 1000.0, 250, 0.4, 20, 100),
        ObjectCueTone(550.0, 350.0, 300, 0.25, 20, 200))
    private fun renderer() = ObjectCueRenderer(settings, 48000, 350f)
    private fun snapshot(ms: Long, vararg ids: Int) = ObstacleSnapshot(ms * 1_000_000,
        ids.map { Obstacle(it, Vec3.ZERO, emptyMap(), Vec3.ZERO, Vec3.ZERO,
            HeightClass.BODY, true, 1f, ms * 1_000_000, 10) }, 0f, MapHealth.OK)
    private fun block(r: ObjectCueRenderer, ms: Long, vararg ids: Int,
                      state: GuidanceState = GuidanceState.NORMAL, stop: Boolean = false,
                      snap: ObstacleSnapshot? = snapshot(ms, *ids), out: FloatArray = FloatArray(512)) =
        r.mix(ms * 1_000_000, snap, GuidanceOutput(ms * 1_000_000, state,
            if (stop) listOf(AudioCmd(1, 0f, 0.5f, Band.STOP, SoundKind.FLOOR_PULSE, 0f)) else emptyList(),
            0f, null), out)

    @Test fun `held appearance and loss fire once from snapshots even with no audio commands`() {
        val r = renderer()
        assertTrue(block(r, 0, 1).isEmpty())
        assertTrue(block(r, 200, 1).isEmpty())
        val event = block(r, 300, 1).single()
        assertEquals(ObjectCueKind.APPEAR, event.kind)
        assertEquals("PLAYED", event.disposition)
        // Render enough continuous blocks for the tone to finish.
        for (ms in 310L..600L step 10) block(r, ms, 1)
        assertTrue(block(r, 700).isEmpty())
        assertTrue(block(r, 1600).isEmpty())
        assertEquals(ObjectCueKind.LOST, block(r, 1700).single().kind)
        assertTrue(block(r, 1800).isEmpty())
    }

    @Test fun `short candidates and temporary loss do not repeat cues`() {
        val r = renderer()
        block(r, 0, 1); block(r, 100)
        block(r, 200, 1)
        assertTrue(block(r, 400, 1).isEmpty())
        assertEquals(ObjectCueKind.APPEAR, block(r, 500, 1).single().kind)
        block(r, 600); block(r, 1000, 1)
        assertTrue(block(r, 1600, 1).isEmpty())
    }

    @Test fun `repeated snapshot cannot confirm a new object`() {
        val r = renderer(); val same = snapshot(0, 1)
        block(r, 0, snap = same)
        assertTrue(block(r, 300, snap = same).isEmpty())
        assertTrue(block(r, 400, snap = same).isEmpty()) // expired, no loss
    }

    @Test fun `unknown paused and stale input cancel tails without mass loss or reappearance`() {
        for (state in listOf(GuidanceState.UNKNOWN, GuidanceState.PAUSED)) {
            val r = renderer()
            block(r, 0, 1); block(r, 300, 1)
            val out = FloatArray(512) { 0.2f }
            assertTrue(block(r, 310, state = state, out = out).isEmpty())
            assertTrue(out.all { it == 0.2f })
            assertTrue(block(r, 500, 1).isEmpty())
            assertTrue(block(r, 900, 1).isEmpty())
            assertTrue(block(r, 2000, snap = snapshot(900, 1)).isEmpty())
        }
    }

    @Test fun `stop suppresses cue and leaves base audio untouched`() {
        val r = renderer(); block(r, 0, 1)
        val out = FloatArray(512) { 0.5f }
        assertEquals("SUPPRESSED_STOP", block(r, 300, 1, stop = true, out = out).single().disposition)
        assertTrue(out.all { it == 0.5f })
        assertTrue(block(r, 400, 1).isEmpty()) // no delayed replay
    }

    @Test fun `simultaneous objects use one voice and cannot clip`() {
        val r = renderer(); block(r, 0, 1, 2)
        val out = FloatArray(512) { if (it % 2 == 0) 0.99f else -0.99f }
        val events = block(r, 300, 1, 2, out = out)
        assertEquals(1, events.count { it.disposition == "PLAYED" })
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test fun `system alerts suppress events without postponing them`() {
        val r = renderer(); block(r, 0, 1)
        val g = GuidanceOutput(300_000_000, GuidanceState.NORMAL, emptyList(), 0f, AlertKind.UNKNOWN)
        val out = FloatArray(512) { 0.1f }
        val event = r.mix(300_000_000, snapshot(300, 1), g, out).single()
        assertEquals("SUPPRESSED_ALERT", event.disposition)
        assertTrue(out.all { it == 0.1f })
        assertTrue(block(r, 1400, 1).isEmpty())
    }

    @Test fun `stop and expired object interrupt a playing appearance`() {
        for (stop in listOf(false, true)) {
            val r = renderer(); block(r, 0, 1); block(r, 300, 1)
            val out = FloatArray(512) { 0.1f }
            val freshSnapshot = if (stop) snapshot(310, 1) else snapshot(310, 1).copy(
                obstacles = snapshot(0, 1).obstacles.map { it.copy(lastSeenNs = -100_000_000) })
            block(r, 310, stop = stop, snap = freshSnapshot, out = out)
            assertTrue(out.all { it == 0.1f })
        }
    }

    @Test fun `tone duration and boundary samples match the audition files`() {
        for ((tone, n) in listOf(settings.appear to 12000, settings.lost to 14400)) {
            val samples = tone.samples(48000)
            assertEquals(n, samples.size)
            assertEquals(0f, samples.first(), 0f); assertEquals(0f, samples.last(), 0f)
            assertTrue(samples.any { abs(it) > 0.01 })
        }
    }
}
