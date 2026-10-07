package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.session.FrameRow
import hearspace.core.session.PoseGl
import hearspace.core.types.AlignConfig
import hearspace.core.types.HeightClass
import hearspace.core.types.TrackingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.cos
import kotlin.math.sin

/** 정답 파일 v1·v2 읽기와 정렬(align.py와 같은 계산) (IMPROVE_SPEC §9.1, M11). */
class TruthTest {
    private val offset = Vec3(0f, 0.5f, -0.39f) // 설정 head.offsetFromCameraM
    private val cfg = AlignConfig(fitLengthM = 2.0f, minTravelM = 0.5f, headingWindowS = 3.0f)

    private fun close(expected: Float, actual: Float, tol: Float = 1e-4f, what: String = "") =
        assertTrue(kotlin.math.abs(expected - actual) <= tol, "$what expected $expected, got $actual")

    @Test
    fun `v1 file reads as start-referenced objects`() {
        val t = GroundTruth.parse(
            """{ "version": 1, "estimated": true, "note": "n",
                 "obstacles": [ { "name": "chair", "type": "FLOOR", "min": [-0.2, 0, 2.0], "max": [0.2, 0.6, 2.3] } ] }""",
            offset,
        )
        assertEquals(1, t.version)
        assertNull(t.scene)
        assertTrue(t.estimated)
        val o = t.obstacles.single()
        assertEquals(TruthKind.OBJECT, o.kind)
        assertEquals(HeightClass.FLOOR, o.type)
        assertEquals(Vec3(-0.2f, 0f, 2.0f), o.minM)
        assertNull(o.removeAtS)
    }

    @Test
    fun `v2 camera-referenced distances move to the head origin`() {
        val t = GroundTruth.parse(
            """{ "version": 2, "scene": "R02", "distanceFrom": "camera",
                 "obstacles": [
                   { "name": "box", "type": "FLOOR", "min": [-0.3, 0, 2.0], "max": [0.3, 0.4, 2.3] },
                   { "name": "wall", "type": "FLOOR", "kind": "structure", "min": [0.7, 0, 0], "max": [0.75, 2.4, 6] } ] }""",
            offset,
        )
        assertEquals("R02", t.scene)
        val box = t.obstacles[0]
        close(2.39f, box.minM.z, what = "z +0.39")
        close(-0.3f, box.minM.x, what = "x unchanged (ox = 0)")
        close(0f, box.minM.y, what = "y unchanged")
        assertEquals(TruthKind.STRUCTURE, t.obstacles[1].kind)
    }

    @Test
    fun `bad answer files fail loudly`() {
        assertThrows<IllegalArgumentException> { GroundTruth.parse("""{ "distanceFrom": "eye", "obstacles": [] }""", offset) }
        assertThrows<IllegalArgumentException> { GroundTruth.parse("""{ "obstacles": [ { "name": "a", "type": "FLOOR", "min": [0, 0], "max": [1, 1, 1] } ] }""", offset) }
        assertThrows<IllegalArgumentException> {
            GroundTruth.parse("""{ "obstacles": [ { "name": "a", "type": "FLOOR", "kind": "wall", "min": [0, 0, 0], "max": [1, 1, 1] } ] }""", offset)
        }
    }

    private fun row(i: Long, x: Float, z: Float, tracking: TrackingState = TrackingState.TRACKING) = FrameRow(
        frameIndex = i, tNs = 1_000_000L * i, sysElapsedNs = 1_000_000L * i, tracking = tracking, trackingFailure = "NONE",
        pose = PoseGl(x, 0f, z, 0f, 0f, 0f, 1f), displayPose = null,
        depthTNs = null, depthFile = null, rawDepthTNs = null, rawDepthFile = null, confFile = null, rgbFile = null,
    )

    /** 월드 방향 (dx, dz)로 [lengthM]만큼 곧게 걷는 카메라 궤적. 시작 (sx, sz). */
    private fun straight(dx: Double, dz: Double, lengthM: Double, sx: Float = 1f, sz: Float = -2f) =
        (0..100).map { i -> val s = lengthM * i / 100; row(i.toLong(), (sx + dx * s).toFloat(), (sz + dz * s).toFloat()) }

    @Test
    fun `straight walk gives its direction and a head origin behind the start camera`() {
        val a = Math.toRadians(30.0)
        val (dx, dz) = sin(a) to -cos(a) // 대략 −Z(ARCore 앞)에서 30° 돌아간 방향
        val al = Alignment.fit(straight(dx, dz, 3.0), cfg, offset, floorY = -1.1)
        assertFalse(al.fitShort)
        close(dx.toFloat(), al.dirX.toFloat(), what = "dirX")
        close(dz.toFloat(), al.dirZ.toFloat(), what = "dirZ")
        assertTrue(al.residualRmsM < 1e-6)
        // 시작 카메라는 정답 좌표에서 (−ox, ·, −oz) = (0, ·, 0.39)
        val cam = al.toTruth(Vec3(1f, 0f, -2f))
        close(0f, cam.x, what = "camera x")
        close(0.39f, cam.z, what = "camera z")
        close(1.1f, cam.y, what = "y above floor")
        // 걸은 끝은 보행선 위 3 m(+0.39)
        val end = al.toTruth(Vec3((1 + dx * 3).toFloat(), 0f, (-2 + dz * 3).toFloat()))
        close(3.39f, end.z, 1e-3f, "end z")
        close(0f, end.x, 1e-3f, "end x")
    }

    @Test
    fun `right is the walker's right hand and toWorld inverts toTruth`() {
        val al = Alignment.fit(straight(0.0, -1.0, 3.0, 0f, 0f), cfg, Vec3(0f, 0f, 0f), floorY = 0.0)
        // −Z로 걸으면 오른쪽은 +X
        close(0.5f, al.toTruth(Vec3(0.5f, 0f, -1f)).x, what = "right = +X")
        val p = Vec3(0.3f, 1.2f, 2.5f)
        val back = al.toTruth(al.toWorld(p))
        close(p.x, back.x); close(p.y, back.y); close(p.z, back.z)
    }

    @Test
    fun `short track is fitted whole and marked, non-tracking and repeated frames are ignored`() {
        val rows = straight(0.0, -1.0, 1.0) + row(200, 50f, 50f, TrackingState.PAUSED) + row(0, 99f, 99f)
        val al = Alignment.fit(rows, cfg, offset, floorY = 0.0)
        assertTrue(al.fitShort)
        assertEquals(101, al.nPoints)
        close(1.0f, al.fitLengthM.toFloat(), 1e-4f, "fit length = walked length")
    }

    /** 30 fps, 월드 Y축 둘레로 [yawDeg]만큼 돈 카메라(GL: 앞 = −Z)가 제자리에서 [jitterM]만큼 떨리는 정지 녹화. */
    private fun stationary(yawDeg: Double, frames: Int, jitterM: Double, yawWobbleDeg: Double = 0.0) = (0 until frames).map { i ->
        val a = Math.toRadians(yawDeg + yawWobbleDeg * sin(i * 0.7)) / 2
        // 떨림 방향은 프레임마다 제각각(궤적 주축이 잡음이 되도록)
        val jx = (jitterM * cos(i * 2.399)).toFloat()
        val jz = (jitterM * sin(i * 2.399)).toFloat()
        FrameRow(
            frameIndex = i.toLong(), tNs = 33_333_333L * i, sysElapsedNs = 33_333_333L * i, tracking = TrackingState.TRACKING,
            trackingFailure = "NONE", pose = PoseGl(1f + jx, 0.1f, -2f + jz, 0f, sin(a).toFloat(), 0f, cos(a).toFloat()), displayPose = null,
            depthTNs = null, depthFile = null, rawDepthTNs = null, rawDepthFile = null, confFile = null, rgbFile = null,
        )
    }

    @Test
    fun `stationary recording is aligned by camera heading, not by jitter`() {
        for (yaw in listOf(0.0, 30.0, -75.0, 170.0)) {
            val al = Alignment.fit(stationary(yaw, 120, jitterM = 0.02), cfg, offset, floorY = -1.0)
            assertTrue(al.byHeading && al.fitShort)
            val a = Math.toRadians(yaw)
            close((-sin(a)).toFloat(), al.dirX.toFloat(), what = "dirX @ $yaw")
            close((-cos(a)).toFloat(), al.dirZ.toFloat(), what = "dirZ @ $yaw")
            // 시작 카메라는 정답 좌표 (0, ·, 0.39): 정지 녹화도 같은 원점 규약
            val cam = al.toTruth(Vec3(1f + 0.02f, 0.1f, -2f))
            close(0f, cam.x, 0.03f, "camera x"); close(0.39f, cam.z, 0.03f, "camera z")
        }
    }

    @Test
    fun `heading window ignores later frames and reports wobble as uncertainty`() {
        // 3 s(90프레임)까지는 요 0° ± 2°, 이후 90°로 돌아도 방향은 처음 3 s 평균
        val rows = stationary(0.0, 90, 0.01, yawWobbleDeg = 2.0) +
            stationary(90.0, 60, 0.01).map { it.copy(frameIndex = it.frameIndex + 90, tNs = it.tNs + 33_333_333L * 90 + 33_333_333L, sysElapsedNs = it.tNs + 33_333_333L * 91) }
        val al = Alignment.fit(rows, cfg, offset, floorY = -1.0)
        assertTrue(al.byHeading)
        close(0f, al.dirX.toFloat(), 0.05f, "dirX"); close(-1f, al.dirZ.toFloat(), 0.01f, "dirZ")
        assertTrue(al.angleUncertaintyDeg in 0.5..3.0, "wobble ${al.angleUncertaintyDeg}")
    }

    @Test
    fun `walking track is still fitted by trajectory`() {
        val al = Alignment.fit(straight(0.0, -1.0, 3.0), cfg, offset, floorY = -1.1)
        assertFalse(al.byHeading)
    }

    @Test
    fun `corridor-nearest truth point follows metrics truth_nearest`() {
        val c = hearspace.core.types.CorridorConfig(widthM = 0.8f, heightM = 2.0f, lengthM = 3.5f, behindM = 0.2f, edgeInnerM = 0.25f, edgeMinLengthM = 0.8f)
        fun box(x0: Float, x1: Float, z0: Float, z1: Float, y0: Float = 0f) =
            TruthObstacle("b", HeightClass.FLOOR, TruthKind.OBJECT, Vec3(x0, y0, z0), Vec3(x1, y0 + 0.5f, z1), null)
        val ahead = box(-0.2f, 0.2f, 2.0f, 2.3f).nearestInCorridor(0f, 0f, c)!!
        close(2.0f, ahead.alongM); close(2.0f, ahead.distanceM); close(0f, ahead.azimuthDeg)
        // 통로(±0.4)에 걸친 상자: 통로 안 부분의 머리 쪽 끝(x 0.2)
        val edge = box(0.2f, 0.6f, 2.0f, 2.3f).nearestInCorridor(0f, 0f, c)!!
        close(Math.toDegrees(kotlin.math.atan2(0.2, 2.0)).toFloat(), edge.azimuthDeg, what = "az to x=0.2")
        assertNull(box(0.5f, 0.9f, 2.0f, 2.3f).nearestInCorridor(0f, 0f, c), "outside corridor width")
        assertNull(box(-0.2f, 0.2f, 2.0f, 2.3f, y0 = 2.0f).nearestInCorridor(0f, 0f, c), "above corridor height")
        assertNull(box(-0.2f, 0.2f, -1f, -0.5f).nearestInCorridor(0f, 0f, c), "behind")
        assertNull(box(-0.2f, 0.2f, 4.0f, 4.5f).nearestInCorridor(0f, 0f, c), "beyond corridor length")
        // 머리가 상자 옆을 지나가는 중(상자 앞면이 머리 뒤 behindM 안): along은 −behindM까지
        val passing = box(-0.2f, 0.2f, 1.0f, 2.0f).nearestInCorridor(0f, 1.5f, c)!!
        close(-0.2f, passing.alongM, what = "along clipped at -behind")
    }

    @Test
    fun `median floor matches numpy`() {
        assertEquals(2.0, Alignment.medianFloorY(listOf(3f, null, 1f, 2f)))
        assertEquals(2.5, Alignment.medianFloorY(listOf(4f, 1f, 2f, 3f)))
        assertNull(Alignment.medianFloorY(listOf(null)))
    }
}
