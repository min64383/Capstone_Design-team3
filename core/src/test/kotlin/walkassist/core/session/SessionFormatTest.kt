package walkassist.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import walkassist.core.geometry.Vec3
import walkassist.core.types.Intrinsics
import walkassist.core.types.TrackingState

class SessionFormatTest {

    private val pose = PoseGl(0.1f, -1.25e-3f, 3.0f, 0f, 0.70710677f, 0f, 0.70710677f)

    private fun row(withFiles: Boolean) = FrameRow(
        frameIndex = 123,
        tNs = 98_765_432_101_234L,
        sysElapsedNs = 98_765_432_555_000L,
        tracking = if (withFiles) TrackingState.TRACKING else TrackingState.PAUSED,
        trackingFailure = if (withFiles) "NONE" else "INSUFFICIENT_FEATURES",
        pose = pose,
        displayPose = pose.copy(qz = 0.5f),
        depthTNs = if (withFiles) 98_765_400_000_000L else null,
        depthFile = if (withFiles) SessionFormat.depthFile(123) else null,
        rawDepthTNs = if (withFiles) 98_765_300_000_000L else null,
        rawDepthFile = if (withFiles) SessionFormat.rawDepthFile(123) else null,
        confFile = if (withFiles) SessionFormat.confFile(123) else null,
        rgbFile = if (withFiles) SessionFormat.rgbFile(123) else null,
    )

    private fun meta() = SessionMeta(
        formatVersion = SessionFormat.VERSION,
        sessionId = "20260926_101500_S02",
        sceneId = "S02",
        createdAt = "2026-09-26T10:15:00+09:00",
        device = DeviceInfo("samsung", "SM-G977N", "Exynos 9820", "12", 31),
        arcore = ArcoreInfo("1.56.0", null),
        depth = DepthInfo(true, true, "AUTOMATIC", 160, 90),
        camera = CameraInfo(
            Intrinsics(492.3f, 492.1f, 319.5f, 239.25f, 640, 480),
            Intrinsics(1476.9f, 1476.3f, 958.5f, 717.75f, 1920, 1440),
            90, 30, 30,
        ),
        gripOffsetM = Vec3(0.1f, 0f, -0.25f),
        elapsedMinusMonotonicNs = 1_234_567_890L,
        depthEveryN = 1,
        rgbEveryN = 3,
        conventions = mapOf("pose" to "ARCore Camera.getPose(), GL: +Y up, -Z forward"),
        stats = null,
    )

    @Test
    fun `file names are zero padded frame indices`() {
        assertEquals("depth/000123.png", SessionFormat.depthFile(123))
        assertEquals("raw_depth/000000.png", SessionFormat.rawDepthFile(0))
        assertEquals("depth_conf/1234567.png", SessionFormat.confFile(1_234_567))
        assertEquals("rgb/000042.jpg", SessionFormat.rgbFile(42))
    }

    @Test
    fun `frames csv header is fixed`() {
        assertEquals(
            "frameIndex,tNs,sysElapsedNs,tracking,trackingFailure,tx,ty,tz,qx,qy,qz,qw,dtx,dty,dtz,dqx,dqy,dqz,dqw," +
                "depthTNs,depthFile,rawDepthTNs,rawDepthFile,confFile,rgbFile",
            FramesCsv.headerLine(),
        )
    }

    @Test
    fun `frames csv round trips with and without files`() {
        for (r in listOf(row(true), row(false))) {
            val line = FramesCsv.format(r)
            assertEquals(FramesCsv.HEADER.size, line.split(',').size, line)
            assertEquals(r, FramesCsv.parse(line))
        }
    }

    @Test
    fun `frames csv rejects malformed lines`() {
        assertThrows<IllegalArgumentException> { FramesCsv.parse("1,2,3") }
        val bad = FramesCsv.format(row(true)).replaceFirst("0.1", "x")
        assertThrows<IllegalArgumentException> { FramesCsv.parse(bad) }
    }

    @Test
    fun `device csv round trips`() {
        val rows = listOf(DeviceRow(1_000_000_000L, 2, 87, null), DeviceRow(2L, null, null, 12.5f))
        for (r in rows) assertEquals(r, DeviceCsv.parse(DeviceCsv.format(r)))
        assertEquals("tNs,thermalStatus,batteryPct,audioOutputLatencyMs", DeviceCsv.headerLine())
    }

    @Test
    fun `meta json round trips with and without stats`() {
        val m = meta()
        assertEquals(m, SessionMeta.fromJson(m.toJson()))
        val done = m.copy(stats = SessionStats(1800, 600, 200, 600, 3, 0, 60.1f))
        val text = done.toJson()
        assertEquals(done, SessionMeta.fromJson(text))
        // Float가 이진 근사값(0.10000000149…)으로 써지지 않아야 한다
        assertTrue("[0.1, 0, -0.25]" in text, text)
    }

    @Test
    fun `meta json without clock field reads as null`() {
        // 이 필드가 생기기 전의 v0 세션(스모크 테스트 녹화)도 읽을 수 있어야 한다
        val text = meta().toJson().replace(Regex("""\s*"elapsedMinusMonotonicNs": [0-9]+,"""), "")
        assertTrue("elapsedMinusMonotonicNs" !in text, text)
        assertEquals(meta().copy(elapsedMinusMonotonicNs = null), SessionMeta.fromJson(text))
    }

    @Test
    fun `meta json reports missing fields`() {
        val e = assertThrows<IllegalArgumentException> { SessionMeta.fromJson("""{ "formatVersion": "v0" }""") }
        assertTrue("missing" in e.message!!, e.message)
    }
}
