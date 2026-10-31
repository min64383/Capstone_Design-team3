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
    fun `interrupted recording is read up to the cut`() {
        // A1 FA8: 녹화가 끊기면 frames.csv 마지막 줄과 마지막 PNG가 잘린다. 그 줄은 버리고 잘린 PNG는 없는 파일처럼 건너뛴다
        val rec = Scenes.SC02.generate()
        val dir = SyntheticSessionWriter.write(rec, File(tmp, "s"), "SC-02")
        val f = File(dir, SessionFormat.FRAMES_FILE)
        val lines = f.readLines().filter { it.isNotBlank() }.toMutableList()
        lines[lines.lastIndex] = lines.last().take(lines.last().length / 2)
        f.writeText(lines.joinToString("\n"))
        val png = File(dir, SessionFormat.depthFile(40))
        png.writeBytes(png.readBytes().copyOf(png.length().toInt() / 2))
        val r = SessionReader(dir)
        assertEquals(rec.frames.size - 1, r.rows.size)
        val depths = r.events(DepthSource.SMOOTHED).filterIsInstance<DepthEvent>().toList()
        assertTrue(depths.isNotEmpty() && depths.none { it.frameIndex == 40L })
        assertTrue(r.warnings.any { "dropped" in it && "line ${lines.size}" in it }, r.warnings.toString())
        assertTrue(r.warnings.any { "unreadable" in it && "000040" in it }, r.warnings.toString())
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

    /** M37 학습형 장: 원시 장 일부를 learned_depth PNG로 바꾸고, 화소 출처를 붙이고, 모델 시간만큼 늦게 내며 그 사이 원시 장은 버린다. */
    @Test
    fun `learned frames replace chosen raw frames, carry pixel source and arrive after the model delay`() {
        val rec = Scenes.SC02.generate()
        val dir = SyntheticSessionWriter.write(rec, File(tmp, "s"), "SC-02", rawEveryN = 3)
        val plain = SessionReader(dir).events(DepthSource.RAW).filterIsInstance<DepthEvent>().toList()
        assertTrue(plain.size > 6, "raw frames ${plain.size}")
        val chosen = plain.filterIndexed { i, _ -> i % 3 == 1 }.map { it.frameIndex }
        val ld = File(dir, "learned_depth/v")
        val w = plain[0].depth.K.width
        val h = plain[0].depth.K.height
        for (sub in listOf("full", "fill", "src")) File(ld, sub).mkdirs()
        val byIndex = plain.associateBy { it.frameIndex }
        for (fi in chosen) {
            val raw = byIndex.getValue(fi).depth.depthMm
            val full = ShortArray(raw.size) { 2000 } // 학습형: 어디나 2 m
            val src = ByteArray(raw.size) { if (it % w < w / 2) 1 else 0 } // 왼쪽 반만 채움
            val fill = ShortArray(raw.size) { if (src[it].toInt() == 1) 2000 else raw[it] }
            File(ld, "full/%06d.png".format(fi)).writeBytes(Png16.encode(GrayImage.of16(w, h, full)))
            File(ld, "fill/%06d.png".format(fi)).writeBytes(Png16.encode(GrayImage.of16(w, h, fill)))
            File(ld, "src/%06d.png".format(fi)).writeBytes(Png16.encode(GrayImage.of8(w, h, src)))
        }
        File(ld, "index.csv").writeText("frameIndex,rawTNs\n" + chosen.joinToString("\n") { "$it,0" } + "\n")

        // 기다림 없음: 같은 자리에서 바뀐다
        val full = SessionReader(dir).events(DepthSource.RAW, SessionReader.Learned(dir, "v", "full", 0L)).filterIsInstance<DepthEvent>().toList()
        assertEquals(plain.map { it.frameIndex }, full.map { it.frameIndex })
        for (e in full) {
            if (e.frameIndex in chosen) {
                assertTrue(e.depth.depthMm.all { it.toInt() == 2000 })
                assertTrue(e.depth.pixelSource!!.all { it.toInt() == hearspace.core.types.DepthFrame.PIXEL_LEARNED })
                assertEquals(byIndex.getValue(e.frameIndex).depth.tCaptureNs, e.depth.tCaptureNs)
                assertSamePose(byIndex.getValue(e.frameIndex).depth.worldFromCam, e.depth.worldFromCam)
            } else {
                assertEquals(null, e.depth.pixelSource)
                assertArrayEquals(byIndex.getValue(e.frameIndex).depth.depthMm, e.depth.depthMm)
            }
        }
        val fill = SessionReader(dir).events(DepthSource.RAW, SessionReader.Learned(dir, "v", "fill", 0L)).filterIsInstance<DepthEvent>()
            .first { it.frameIndex == chosen[0] }
        val px = fill.depth.pixelSource!!
        assertTrue((0 until px.size).all { px[it].toInt() == (if (it % w < w / 2) 1 else 0) })

        // 기다림 0.2 s: 도착 행 시스템 시각 + 0.2 s 뒤 첫 행에서 나오고, 그 사이 원시 장은 버린다
        val r = SessionReader(dir)
        val sys = r.rows.associate { it.frameIndex to it.sysElapsedNs }
        val delayed = r.events(DepthSource.RAW, SessionReader.Learned(dir, "v", "full", 200_000_000L)).filterIsInstance<DepthEvent>().toList()
        val learnedOut = delayed.filter { it.depth.pixelSource != null }
        assertTrue(learnedOut.isNotEmpty())
        for (e in learnedOut) {
            val origin = plain.first { it.depth.tCaptureNs == e.depth.tCaptureNs }.frameIndex
            assertTrue(sys.getValue(e.frameIndex) >= sys.getValue(origin) + 200_000_000L)
            val dropped = plain.filter { it.frameIndex > origin && sys.getValue(it.frameIndex) < sys.getValue(origin) + 200_000_000L }
            assertTrue(dropped.none { d -> delayed.any { it.depth.tCaptureNs == d.depth.tCaptureNs } }, "raw frames while waiting must be dropped")
        }
    }
}
