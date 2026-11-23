package hearspace.core.synth

import hearspace.core.audio.ObjectSpeechPolicy
import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.situation.SituationInput
import hearspace.core.situation.Situations
import hearspace.core.situation.SurfaceClass
import hearspace.core.situation.LiveSituationSpeech
import hearspace.core.situation.SituationSpeechFrame
import hearspace.core.situation.SpeechPriority
import hearspace.core.situation.SpeechRequest
import hearspace.core.situation.chooseSpeech
import hearspace.core.situation.obstacleSpeechLine
import hearspace.core.types.ConfigLoader
import hearspace.core.types.DepthFrame
import hearspace.core.types.Intrinsics
import hearspace.core.types.LabelImage
import hearspace.core.types.MapHealth
import hearspace.core.types.ObstacleSnapshot
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LiveSituationSpeechTest {
    private val ageNs = 500_000_000L
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())
    private val policy = LiveSituationSpeech(config.situation, ageNs)
    private val nowNs = 10_000_000_000L
    private val epoch = 1L
    private val turn = SpeechRequest("turn", "블록 오른쪽으로 꺾임", 2, nowNs)

    @Test
    fun `detected block end reaches the live speech policy`() {
        val k = Intrinsics(250f, 250f, 120f, 160f, 240, 320)
        val pose = Mat4.fromAxes(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f),
            Vec3(0f, 1.3f, 0f))
        val poseOrigin = pose.translation()
        val surfacePixels = ByteArray(k.width * k.height) { index ->
            val u = index % k.width
            val v = index / k.width
            val ray = pose.transformDir(Vec3((u + 0.5f - k.cx) / k.fx, (v + 0.5f - k.cy) / k.fy, 1f))
            if (ray.y >= 0f) 255.toByte() else {
                val hit = poseOrigin + ray * (-poseOrigin.y / ray.y)
                val surface = if (hit.x in -0.15f..0.15f && -hit.z in 0.5f..3.5f) SurfaceClass.BLOCK
                    else SurfaceClass.SIDEWALK
                surface.pixel.toByte()
            }
        }
        val detector = Situations(config.situation, config.corridor.widthM, config.corridor.heightM,
            config.vision.footHeightWindowS, ageNs)
        val depth = DepthFrame(nowNs, ShortArray(k.width * k.height), null, k, pose, "synthetic")
        val snapshot = ObstacleSnapshot(nowNs, emptyList(), 0f, MapHealth.OK)
        val surface = LabelImage(nowNs, surfacePixels, k, pose)
        val (requests, _) = detector.detect(SituationInput(nowNs, depth, snapshot, emptyList(),
            Vec3(0f, 0f, -1f), surface))
        val current = policy.observe(SituationSpeechFrame(epoch, requests), epoch, nowNs)
        assertEquals("end", policy.next(current, true, nowNs)?.key)
    }

    @Test
    fun `STOP interrupts a turn but equal priority cannot interrupt STOP`() {
        val candidates = listOf(SpeechPriority.ROUTE, SpeechPriority.IMMEDIATE, SpeechPriority.STATUS)
        assertEquals(SpeechPriority.IMMEDIATE, chooseSpeech(SpeechPriority.ROUTE, candidates) { it })
        assertNull(chooseSpeech(SpeechPriority.IMMEDIATE, candidates) { it })
        assertEquals(SpeechPriority.UNAVAILABLE, chooseSpeech(SpeechPriority.IMMEDIATE,
            candidates + SpeechPriority.UNAVAILABLE) { it })
    }

    @Test
    fun `waiting turn is discarded when expired or when the world changes`() {
        val frame = SituationSpeechFrame(epoch, listOf(turn))
        assertEquals(turn, policy.next(policy.observe(frame, epoch, nowNs), true, nowNs))
        assertEquals(emptyList<SpeechRequest>(), policy.observe(frame, epoch, nowNs + ageNs + 1))
        assertEquals(emptyList<SpeechRequest>(), policy.observe(frame, epoch, nowNs - 1))
        assertEquals(emptyList<SpeechRequest>(), policy.observe(frame, epoch + 1, nowNs))
    }

    @Test
    fun `completed turn stays quiet until it returns after the repeat interval`() {
        val frame = SituationSpeechFrame(epoch, listOf(turn))
        policy.observe(frame, epoch, nowNs)
        policy.finished(turn, nowNs, completed = true, failed = false)
        val later = nowNs + 31_000_000_000L
        val refreshed = frame.copy(requests = listOf(turn.copy(evidenceNs = later)))
        assertNull(policy.next(policy.observe(refreshed, epoch, later), true, later))
        policy.observe(null, epoch, later + 1)
        assertEquals(refreshed.requests.single(),
            policy.next(policy.observe(refreshed, epoch, later + 2), true, later + 2))
    }

    @Test
    fun `a confirmed descent can replace the already announced generic stair`() {
        val stairs = SpeechRequest("stairs", "계단, 2미터", 2, nowNs, level = 1)
        policy.observe(SituationSpeechFrame(epoch, listOf(stairs)), epoch, nowNs)
        policy.finished(stairs, nowNs, completed = true, failed = false)
        val down = stairs.copy(text = "내려가는 계단, 2미터", priority = 1, level = 2)
        val current = policy.observe(SituationSpeechFrame(epoch, listOf(down)), epoch, nowNs)
        assertEquals(down, policy.next(current, true, nowNs))
    }

    @Test
    fun `same obstacle on the block makes one urgent line using both timestamps`() {
        val onBlock = SpeechRequest("obj#7", "블록 위 물체, 1미터", 2, nowNs - 5,
            onBlockObstacleId = 7)
        val stop = ObjectSpeechPolicy.Request("object-1",
            ObjectSpeechPolicy.Signature(7, "장애물", "정면", true),
            "정면, 장애물, 1미터. 잠시 멈춰 주세요.", nowNs, "1미터")
        val current = policy.observe(SituationSpeechFrame(epoch, listOf(onBlock)), epoch, nowNs)
        val line = obstacleSpeechLine(stop, current)
        assertEquals("멈추세요. 정면, 점자블록 위 장애물, 1미터.", line.text)
        assertEquals(nowNs - 5, line.evidenceNs)
        assertNull(policy.next(current, true, nowNs))
        assertEquals(onBlock, policy.next(current, false, nowNs))
        assertEquals("멈추세요. 정면, 장애물, 1미터.",
            obstacleSpeechLine(stop, listOf(onBlock.copy(onBlockObstacleId = 8))).text)
    }
}
