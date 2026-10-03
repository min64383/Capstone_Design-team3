package hearspace.core.session

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import hearspace.core.geometry.Quaternion
import hearspace.core.synth.Noise
import hearspace.core.synth.Scenes
import hearspace.core.synth.SyntheticSessionWriter
import hearspace.core.types.DepthSource
import hearspace.core.types.TrackingState
import java.io.File

class SessionReaderTest {

    @TempDir
    lateinit var tmp: File

    private fun assertSamePose(expected: hearspace.core.geometry.Mat4, actual: hearspace.core.geometry.Mat4) {
        assertEquals(0f, (expected.translation() - actual.translation()).norm(), 1e-4f)
        assertTrue(Quaternion.sameRotation(Quaternion.fromMat4(expected), Quaternion.fromMat4(actual), 1e-6f))
    }

    @Test
    fun `poses round trip GL to CV through a session folder`() {
        val rec = Scenes.SC08.generate() // 손목 흔들림으로 다양한 자세
        val r = SessionReader(SyntheticSessionWriter.write(rec, File(tmp, "s"), "SC-08"))
        val poses = r.poseFrames()
        assertEquals(rec.frames.size, poses.size)
        for ((f, p) in rec.frames.zip(poses)) {
            assertEquals(f.pose.tCaptureNs, p.tCaptureNs)
            assertEquals(f.pose.tracking, p.tracking)
            assertSamePose(f.pose.worldFromCam, p.worldFromCam)
        }
    }

    @Test
    fun `depth frames carry image timestamp, same pixels and depth intrinsics`() {
        val rec = Scenes.SC02.generate()
        val r = SessionReader(SyntheticSessionWriter.write(rec, File(tmp, "s"), "SC-02"))
        val depths = r.events(DepthSource.SMOOTHED).filterIsInstance<DepthEvent>().toList()
        assertEquals(rec.frames.count { it.depth != null }, depths.size)
        val byIndex = rec.frames.associateBy { it.index.toLong() }
        for (e in depths) {
            val src = byIndex[e.frameIndex]!!.depth!!
            assertEquals(src.tCaptureNs - 300_000L, e.depth.tCaptureNs) // 이미지 자체 시각(프레임보다 0.3 ms 앞)
            assertEquals(src.tCaptureNs, e.arrivalTNs)
            assertArrayEquals(src.depthMm, e.depth.depthMm)
            assertEquals(src.K.width, e.depth.K.width)
            assertEquals(src.K.fx, e.depth.K.fx, 1e-3f)
            assertEquals(src.K.cy, e.depth.K.cy, 1e-3f)
            // 도착 프레임(0.3 ms 뒤)이 가장 가까운 자세
            assertSamePose(src.worldFromCam, e.depth.worldFromCam)
            assertEquals("arcore_depth", e.depth.source)
        }
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
    }

    @Test
    fun `events are in arrival order and poses precede their depth`() {
        val r = SessionReader(SyntheticSessionWriter.write(Scenes.SC02.generate(), File(tmp, "s"), "SC-02"))
        val ev = r.events(DepthSource.RAW).toList()
        assertTrue(ev.zipWithNext().all { (a, b) -> a.arrivalTNs <= b.arrivalTNs })
        val first = ev.indexOfFirst { it is DepthEvent }
        assertTrue(ev[first - 1] is PoseEvent && ev[first - 1].arrivalTNs == ev[first].arrivalTNs)
        val raw = ev.filterIsInstance<DepthEvent>().first().depth
        assertEquals("arcore_raw_depth", raw.source)
        assertEquals(raw.depthMm.size, raw.confidence!!.size)
    }

    @Test
    fun `frozen depth produces no new depth events`() {
        val rec = Scenes.SC13.generate()
        val r = SessionReader(SyntheticSessionWriter.write(rec, File(tmp, "s"), "SC-13"))
        val times = r.events(DepthSource.SMOOTHED).filterIsInstance<DepthEvent>().map { it.arrivalTNs }.toList()
        val gaps = times.zipWithNext { a, b -> (b - a) / 1e9 }
        // 30 Hz 중 약 1 s 동안 새 깊이가 없다 → 정보 나이가 커져 만료로 이어진다(SC-13, M5)
        assertTrue(gaps.max() > 0.9, "max gap ${gaps.max()} s")
        assertEquals(times.size, times.toSet().size)
    }

    @Test
    fun `raw depth between frames gets the nearest already-received pose`() {
        // 깊이 시각을 도착 프레임보다 10 ms 앞으로: 도착 프레임(10 ms 차)이 직전 프레임(23.3 ms 차)보다 가깝다
        val rec = Scenes.SC08.generate()
        val near = SessionReader(SyntheticSessionWriter.write(rec, File(tmp, "a"), "SC-08", depthOffsetNs = 10_000_000L))
        for (e in near.events(DepthSource.RAW).filterIsInstance<DepthEvent>()) {
            val arrival = rec.frames.first { it.index.toLong() == e.frameIndex }
            assertSamePose(arrival.pose.worldFromCam, e.depth.worldFromCam)
        }
        // 25 ms 앞이면 직전 프레임(8.3 ms 차)이 더 가깝다 — 이미 받은 과거 자세
        val far = SessionReader(SyntheticSessionWriter.write(rec, File(tmp, "b"), "SC-08", depthOffsetNs = 25_000_000L))
        for (e in far.events(DepthSource.RAW).filterIsInstance<DepthEvent>()) {
            if (e.frameIndex == 0L) continue
            val prev = rec.frames.first { it.index.toLong() == e.frameIndex - 1 }
            assertSamePose(prev.pose.worldFromCam, e.depth.worldFromCam)
        }
    }

    @Test
    fun `depth during tracking loss is not used and missing files are reported`() {
        val rec = Scenes.SC02.copy(noise = Noise(trackingLossS = listOf(2.2f..2.5f))).generate()
        val dir = SyntheticSessionWriter.write(rec, File(tmp, "s"), "SC-02")
        val r = SessionReader(dir)
        assertTrue(r.poseFrames().any { it.tracking == TrackingState.PAUSED })
        File(dir, SessionFormat.depthFile(40)).delete()
        val depths = r.events(DepthSource.SMOOTHED).filterIsInstance<DepthEvent>().toList()
        assertTrue(depths.none { it.frameIndex == 40L })
        assertTrue(r.warnings.any { "missing" in it && "000040" in it }, r.warnings.toString())
    }

    @Test
    fun `v0 and v1 sessions of the same recording read identically`() {
        val rec = Scenes.SC08.generate()
        val v1 = SessionReader(SyntheticSessionWriter.write(rec, File(tmp, "v1"), "SC-08"))
        val v0 = SessionReader(SyntheticSessionWriter.write(rec, File(tmp, "v0"), "SC-08", version = "v0"))
        assertEquals("v1", v1.meta.formatVersion)
        assertEquals("v0", v0.meta.formatVersion)
        assertTrue(v1.rows.all { it.displayPose == null })
        assertTrue(v0.rows.all { it.displayPose != null })
        assertEquals(v0.poseFrames(), v1.poseFrames())
        val d0 = v0.events(DepthSource.SMOOTHED).filterIsInstance<DepthEvent>().toList()
        val d1 = v1.events(DepthSource.SMOOTHED).filterIsInstance<DepthEvent>().toList()
        assertEquals(d0.size, d1.size)
        for ((a, b) in d0.zip(d1)) {
            assertEquals(a.depth, b.depth.copy(K = a.depth.K)) // K 외 동일
            assertEquals(a.depth.K.fx, b.depth.K.fx, 1e-3f)
            assertEquals(a.depth.K.cx, b.depth.K.cx, 1e-3f)
        }
    }

    @Test
    fun `v1 meta depth intrinsics take precedence`() {
        val dir = SyntheticSessionWriter.write(Scenes.SC02.generate(), File(tmp, "s"), "SC-02")
        val f = File(dir, SessionFormat.META_FILE)
        val m = SessionMeta.fromJson(f.readText())
        val custom = m.depth.intrinsics!!.copy(fx = 100f, cy = 40f)
        f.writeText(m.copy(depth = m.depth.copy(intrinsics = custom)).toJson())
        val r = SessionReader(dir)
        assertEquals(custom, r.depthIntrinsics(160, 90))
        // 크기가 다르면 텍스처 K 환산으로
        assertEquals(hearspace.core.synth.SyntheticGenerator.DEPTH_K.fx * 2, r.depthIntrinsics(320, 180).fx, 1e-2f)
        assertEquals(custom, r.events(DepthSource.SMOOTHED).filterIsInstance<DepthEvent>().first().depth.K)
    }

    @Test
    fun `header that does not match meta version is rejected`() {
        val dir = SyntheticSessionWriter.write(Scenes.SC01.generate(), File(tmp, "s"), "SC-01")
        val f = File(dir, SessionFormat.META_FILE)
        f.writeText(f.readText().replace("\"formatVersion\": \"v1\"", "\"formatVersion\": \"v0\""))
        val e = assertThrows<IllegalArgumentException> { SessionReader(dir) }
        assertTrue("header" in e.message!!, e.message)
    }

    @Test
    fun `corrupt frames csv reports the line`() {
        val dir = SyntheticSessionWriter.write(Scenes.SC01.generate(), File(tmp, "s"), "SC-01")
        val f = File(dir, SessionFormat.FRAMES_FILE)
        val lines = f.readLines().toMutableList()
        lines[5] = lines[5].replaceFirst("TRACKING", "BOGUS")
        f.writeText(lines.joinToString("\n"))
        val e = assertThrows<IllegalArgumentException> { SessionReader(dir) }
        assertTrue("line 6" in e.message!!, e.message)
    }

    @Test
    fun `reads sessions recorded before elapsedMinusMonotonicNs existed`() {
        val dir = SyntheticSessionWriter.write(Scenes.SC01.generate(), File(tmp, "s"), "SC-01")
        val meta = File(dir, SessionFormat.META_FILE)
        meta.writeText(meta.readText().replace(Regex("""\s*"elapsedMinusMonotonicNs": [0-9]+,"""), ""))
        assertEquals(null, SessionReader(dir).meta.elapsedMinusMonotonicNs)
    }
}
