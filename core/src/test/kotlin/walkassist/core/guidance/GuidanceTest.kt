package walkassist.core.guidance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import walkassist.core.geometry.HeadPose
import walkassist.core.geometry.Vec3
import walkassist.core.geometry.headRelative
import walkassist.core.pipeline.FastPath
import walkassist.core.pipeline.SlowPath
import walkassist.core.synth.SceneSpec
import walkassist.core.synth.Scenes
import walkassist.core.synth.SynthFrame
import walkassist.core.types.AlertKind
import walkassist.core.types.Band
import walkassist.core.types.Config
import walkassist.core.types.ConfigLoader
import walkassist.core.types.GuidanceOutput
import walkassist.core.types.GuidanceState
import walkassist.core.types.HeightClass
import walkassist.core.types.MapHealth
import walkassist.core.types.Obstacle
import walkassist.core.types.ObstacleSnapshot
import walkassist.core.types.RepStrategy
import walkassist.core.types.SoundKind
import java.io.File
import kotlin.math.abs
import kotlin.math.atan

/** 한 프레임의 결과. */
data class Step(val f: SynthFrame, val out: GuidanceOutput, val action: MapAction)

/** 합성 장면을 앱과 같은 인과 순서로 돌린다: 자세마다 빠른 경로, 새 깊이면 맵 명령 적용 후 느린 경로(진행 방향은 빠른 경로의 최신 값). */
fun runGuidance(
    spec: SceneSpec,
    config: Config,
    pauseAt: ClosedFloatingPointRange<Float>? = null,
    /** 자세 하나당 빠른 경로 호출 수(앱은 오디오 블록마다 같은 자세로 부른다, M7). */
    blocksPerFrame: Int = 1,
): List<Step> {
    val fast = FastPath(config)
    val slow = SlowPath(config)
    var snapshot: ObstacleSnapshot? = null
    var lastDepthT = Long.MIN_VALUE
    val out = ArrayList<Step>()
    for (f in spec.generate().frames) {
        fast.paused = pauseAt != null && f.tS in pauseAt
        var g = fast.compute(f.pose, snapshot, f.pose.tCaptureNs)
        for (k in 1 until blocksPerFrame) {
            val gk = fast.compute(f.pose, snapshot, f.pose.tCaptureNs + k * BLOCK_NS)
            g = gk.copy(alert = g.alert ?: gk.alert)
        }
        val action = fast.takeMapAction()
        slow.apply(action)
        if (action == MapAction.RESET) snapshot = null
        val d = f.depth
        val h = fast.headingW
        if (d != null && d.tCaptureNs > lastDepthT && h != null) { // 같은 시각의 깊이 반복(정지)은 새 입력이 아니다
            lastDepthT = d.tCaptureNs
            snapshot = slow.process(d, d.worldFromCam.translation(), h)
        }
        out += Step(f, g, action)
    }
    return out
}

/** 256 샘플 @ 48 kHz 블록 길이. */
private const val BLOCK_NS = 5_333_333L

class GuidanceTest {
    private val base = File(System.getProperty("walkassist.defaultConfig")).readText()

    /** 장면의 파지 오프셋을 머리 원점으로(합성 장면은 머리 위치를 정확히 안다). */
    private fun configFor(spec: SceneSpec, extra: String = ""): Config {
        val g = spec.walk.gripOffsetM
        val sep = if (extra.isBlank()) "" else ", $extra"
        return ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }$sep }""")
    }

    private fun alongToBoxFront(f: SynthFrame, frontZ: Float): Float =
        (Vec3(0f, 0f, frontZ) - f.truthHead.positionW).horizontal() dot f.truthHead.headingW

    @Test
    fun `SC-08 heading follows walking, not the wrist yaw`() {
        val spec = Scenes.SC08
        val steps = runGuidance(spec, configFor(spec))
        var maxCamYawDev = 0f
        for (s in steps.filter { it.f.tS > 2.4f }) {
            val truth = Heading.toDeg(s.f.truthHead.headingW)
            assertEquals(truth, s.out.headingDeg, 2f, "t=${s.f.tS}")
            val camFwd = s.f.truthWorldFromCam.axis(2).horizontal().normalized()
            maxCamYawDev = maxOf(maxCamYawDev, abs(Heading.toDeg(camFwd) - truth))
        }
        assertTrue(maxCamYawDev > 10f, "wrist yaw should move the camera by >10° (was $maxCamYawDev)")
        // 서 있는 동안: 첫 TRACKING 프레임의 카메라 정면
        assertEquals(0f, steps.first { it.f.tS < 1f }.out.headingDeg, 0.5f)
    }

    @Test
    fun `SC-02 end to end - ready, then WARN at 2_5 m and STOP at 1_0 m straight ahead`() {
        val spec = Scenes.SC02
        val steps = runGuidance(spec, configFor(spec))
        // 시작: 복귀 규칙으로 대기(UNKNOWN, 알림 없음) → READY 한 번
        assertEquals(GuidanceState.UNKNOWN, steps.first().out.state)
        assertTrue(steps.takeWhile { it.out.state == GuidanceState.UNKNOWN }.none { it.out.alert != null })
        assertEquals(listOf(AlertKind.READY), steps.mapNotNull { it.out.alert })
        assertTrue(steps.filter { it.out.state == GuidanceState.UNKNOWN }.all { it.out.commands.isEmpty() })

        // 머리는 카메라 0.3 m 뒤라 서 있을 때부터 앞면까지 2.3 m(WARN). 프레임마다 참 거리에 맞는 구간인지 본다
        // (대표점은 복셀 중심이라 약 +0.025 m, 히스테리시스 0.15 m 안쪽은 어느 쪽이든 허용)
        for (s in steps.filter { it.out.commands.isNotEmpty() }) {
            val a = alongToBoxFront(s.f, -2f)
            val b = s.out.commands.single().band
            if (a < 0.9f) assertEquals(Band.STOP, b, "t=${s.f.tS} along=$a")
            if (a in 1.2f..2.4f) assertEquals(Band.WARN, b, "t=${s.f.tS} along=$a")
        }
        val firstStop = steps.first { s -> s.out.commands.any { it.band == Band.STOP } }
        assertEquals(1.0f, alongToBoxFront(firstStop.f, -2f), 0.1f)
        for (s in steps.filter { it.out.commands.isNotEmpty() }) {
            val c = s.out.commands.single()
            val along = alongToBoxFront(s.f, -2f)
            // 대표점은 앞면(폭 0.45) 어딘가: 방위각은 상자 폭 안
            assertTrue(abs(c.azimuthDeg) <= Math.toDegrees(atan(0.25 / along)).toFloat() + 2f, "t=${s.f.tS} az=${c.azimuthDeg}")
            assertEquals(SoundKind.FLOOR_PULSE, c.sound)
        }
    }

    @Test
    fun `SC-14 narrow corridor - side wall inside the corridor is not announced, the box ahead is`() {
        val spec = Scenes.SC14
        val steps = runGuidance(spec, configFor(spec))
        val box = spec.scene.items.first { it.name == "box" }.shape
        val announced = steps.flatMap { it.out.commands }.filter { it.band != Band.SILENT }
        assertTrue(announced.isNotEmpty(), "the box must be announced")
        // 안내된 물체는 모두 상자 쪽(오른쪽 벽은 사용자 기준 +0.35 m, 상자는 −0.2~+0.2 m 앞쪽)
        for (s in steps.filter { st -> st.out.commands.any { it.band != Band.SILENT } }) {
            val c = s.out.commands.single()
            val rel = walkassist.core.geometry.headRelative(Vec3(0.2f, 0.4f, box.aabbMax.z), s.f.truthHead)
            assertEquals(rel.horizontalDistM, c.distanceM, 0.35f, "t=${s.f.tS}: announced something that is not the box")
        }
        // 상자가 SILENT 끝(3.0 m) + 여유 밖에 있는 동안은 벽만 보인다 → 경고 없음. (−Z로 걸으므로 앞 거리 = 머리 z − 상자 앞면 z)
        val early = steps.filter { it.out.state == GuidanceState.NORMAL && it.f.truthHead.positionW.z - box.aabbMax.z > 3.2f }
        assertTrue(early.size > 10, "frames before the box is in range: ${early.size}")
        assertTrue(early.all { s -> s.out.commands.none { it.band == Band.WARN || it.band == Band.STOP } })
    }

    @Test
    fun `SC-10 tracking loss - UNKNOWN at once, one alert, silence, then READY and map scale`() {
        val spec = Scenes.SC10 // 1.0~2.0 s 추적 상실
        val steps = runGuidance(spec, configFor(spec))
        val lost = steps.filter { it.f.tS in 1.0f..2.0f }
        assertTrue(lost.all { it.out.state == GuidanceState.UNKNOWN && it.out.commands.isEmpty() })
        assertEquals(AlertKind.UNKNOWN, lost.first().out.alert, "alert on the first lost frame")
        assertEquals(1, lost.count { it.out.alert == AlertKind.UNKNOWN }) // 1 s < unknownRepeatS 3 s
        val after = steps.filter { it.f.tS > 2.0f }
        val ready = after.first { it.out.alert == AlertKind.READY }
        assertTrue(ready.f.tS - 2.0f < 0.6f, "recovered at ${ready.f.tS}")
        assertEquals(MapAction.SCALE, ready.action)
        assertTrue(after.last().out.commands.isNotEmpty())
    }

    @Test
    fun `SC-12 pose jump while TRACKING - UNKNOWN, map reset, heading re-initialised, recovery`() {
        val spec = Scenes.SC12 // 2.5 s에 3 m·60° 점프
        val steps = runGuidance(spec, configFor(spec))
        val jump = steps.first { it.f.tS >= 2.5f }
        assertEquals(GuidanceState.UNKNOWN, jump.out.state)
        assertEquals(AlertKind.UNKNOWN, jump.out.alert)
        assertEquals(MapAction.RESET, jump.action)
        assertTrue(jump.f.pose.tracking == walkassist.core.types.TrackingState.TRACKING)
        val ready = steps.first { it.f.tS > 2.5f && it.out.alert == AlertKind.READY }
        assertEquals(MapAction.NONE, ready.action) // 초기화했으므로 SCALE 없음
        // 새 좌표에서 맵을 다시 쌓아 경고가 다시 나온다
        assertTrue(steps.last().out.commands.any { it.band == Band.STOP || it.band == Band.WARN })
    }

    @Test
    fun `SC-13 frozen depth - DEGRADED after half the allowed age, UNKNOWN after it, then recovery`() {
        val spec = Scenes.SC13 // 1.0~2.0 s 깊이 정지
        val steps = runGuidance(spec, configFor(spec))
        val frozen = steps.filter { it.f.tS in 1.0f..2.0f }
        val firstDegraded = frozen.first { it.out.state == GuidanceState.DEGRADED }
        val firstUnknown = frozen.first { it.out.state == GuidanceState.UNKNOWN }
        val t0 = frozen.first().f.tS
        assertTrue(firstDegraded.f.tS - t0 in 0.1f..0.25f, "DEGRADED at +${firstDegraded.f.tS - t0}")
        assertTrue(firstUnknown.f.tS - t0 in 0.25f..0.4f, "UNKNOWN at +${firstUnknown.f.tS - t0}")
        assertTrue(firstDegraded.out.commands.isNotEmpty()) // DEGRADED: 음원 유지
        assertTrue(frozen.filter { it.out.state == GuidanceState.UNKNOWN }.all { it.out.commands.isEmpty() })
        assertTrue(steps.any { it.f.tS > 2.0f && it.out.alert == AlertKind.READY })
    }

    @Test
    fun `user pause is silent with one PAUSE alert, resume waits for recovery`() {
        val spec = Scenes.SC02
        val steps = runGuidance(spec, configFor(spec), pauseAt = 2.5f..3.0f)
        val paused = steps.filter { it.f.tS in 2.5f..3.0f }
        assertTrue(paused.all { it.out.state == GuidanceState.PAUSED && it.out.commands.isEmpty() })
        assertEquals(listOf(AlertKind.PAUSE), paused.mapNotNull { it.out.alert })
        assertTrue(steps.any { it.f.tS > 3.0f && it.out.alert == AlertKind.READY })
    }

    @Test
    fun `SC-09 lateral grip offset - corrected head origin removes the azimuth bias`() {
        val spec = Scenes.SC09 // 폰이 몸 오른쪽 0.2 m
        val front = Vec3(0f, 0.4f, -2f)
        fun meanErr(config: Config): Float {
            val errs = runGuidance(spec, config).filter { it.out.commands.isNotEmpty() && it.f.tS > 2.5f }.map { s ->
                s.out.commands.single().azimuthDeg - headRelative(front, s.f.truthHead).azimuthDeg
            }
            assertTrue(errs.size > 10)
            return errs.average().toFloat()
        }
        // 대표점이 상자 가운데에 오도록 CENTROID로 비교
        val corrected = meanErr(configFor(spec, """"repPoint": { "strategy": "CENTROID" }"""))
        val uncorrected = meanErr(ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [0, 0.5, -0.3] }, "repPoint": { "strategy": "CENTROID" } }"""))
        assertTrue(abs(corrected) < 1.5f, "corrected mean azimuth error $corrected°")
        assertTrue(uncorrected < -5f, "uncorrected should be biased left by several degrees, was $uncorrected°")
    }

    @Test
    fun `audio blocks repeating the same pose do not speed up recovery`() {
        val spec = Scenes.SC10
        val c = configFor(spec)
        fun readyTimes(blocks: Int) = runGuidance(spec, c, blocksPerFrame = blocks).filter { it.out.alert == AlertKind.READY }.map { it.f.tS }
        val perFrame = readyTimes(1)
        assertEquals(2, perFrame.size, "start + recovery after loss")
        assertEquals(perFrame, readyTimes(6))
    }
}

class PolicyTest {
    private val config = ConfigLoader.load(File(System.getProperty("walkassist.defaultConfig")).readText())
    private val head = HeadPose(Vec3(0f, 1.5f, 0f), Vec3(0f, 0f, -1f))

    private fun obstacle(id: Int, along: Float, x: Float = 0f, cls: HeightClass = HeightClass.FLOOR): Obstacle {
        val p = Vec3(x, 0.3f, -along)
        return Obstacle(id, p, RepStrategy.entries.associateWith { p }, p, p, cls, true, 1f, 0L, 10)
    }

    private fun snap(vararg o: Obstacle) = ObstacleSnapshot(0L, o.toList(), 0f, MapHealth.OK)

    private fun band(p: Policy, along: Float): Band? = p.commands(snap(obstacle(1, along)), head, 0f).singleOrNull()?.band

    @Test
    fun `bands with safety-side hysteresis`() {
        val p = Policy(config.policy, config.corridor.behindM)
        assertNull(band(p, 3.1f))
        assertEquals(Band.SILENT, band(p, 2.95f))
        assertEquals(Band.WARN, band(p, 2.45f)) // 더 급한 쪽은 경계에서 바로
        assertEquals(Band.WARN, band(p, 2.6f)) // 덜 급한 쪽은 2.5 + 0.15까지 유지
        assertEquals(Band.SILENT, band(p, 2.7f))
        assertEquals(Band.WARN, band(p, 1.05f))
        assertEquals(Band.STOP, band(p, 0.99f))
        assertEquals(Band.STOP, band(p, 1.1f))
        assertEquals(Band.WARN, band(p, 1.2f))
        assertEquals(Band.SILENT, band(p, 3.1f)) // 3.0 + 0.15 전까지 SILENT
        assertNull(band(p, 3.2f))
    }

    @Test
    fun `nearest first up to maxSources, head tone, expiry and behind exclusion`() {
        val p = Policy(config.policy, config.corridor.behindM)
        val cmds = p.commands(snap(obstacle(1, 2.0f), obstacle(2, 1.5f, 0.2f, HeightClass.HEAD)), head, 50f)
        assertEquals(1, cmds.size) // maxSources 1
        assertEquals(2, cmds.single().obstacleId)
        assertEquals(SoundKind.HEAD_TONE, cmds.single().sound)
        assertTrue(cmds.single().azimuthDeg > 0f) // 오른쪽 +
        assertEquals(50f, cmds.single().infoAgeMs)
        assertTrue(p.commands(snap(obstacle(1, 2.0f)), head, 301f).isEmpty()) // 정보 만료
        assertEquals(Band.STOP, p.commands(snap(obstacle(3, -0.1f)), head, 0f).single().band) // 옆·발밑(뒤 0.2 m까지)
        assertTrue(p.commands(snap(obstacle(4, -0.3f)), head, 0f).isEmpty())
    }
}

class HeadingUnitTest {
    private val config = ConfigLoader.load(File(System.getProperty("walkassist.defaultConfig")).readText())

    @Test
    fun `short moves keep the previous heading and reset forgets it`() {
        val h = Heading(config.heading)
        fun pose(t: Float, x: Float, z: Float) = walkassist.core.types.PoseFrame(
            (t * 1e9).toLong(), walkassist.core.types.TrackingState.TRACKING,
            walkassist.core.geometry.Mat4.fromAxes(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f), Vec3(x, 1f, z)),
        )
        assertEquals(0f, Heading.toDeg(h.update(pose(0f, 0f, 0f))!!), 1e-4f) // 카메라 정면(−Z)
        assertEquals(0f, Heading.toDeg(h.update(pose(0.5f, 0.1f, 0f))!!), 1e-4f) // 0.1 m < minTravel 0.15
        assertEquals(90f, Heading.toDeg(h.update(pose(1.0f, 0.5f, 0f))!!), 1e-3f) // +X로 0.5 m
        h.reset()
        assertNull(h.headingW)
    }
}
