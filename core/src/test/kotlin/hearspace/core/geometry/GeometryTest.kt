package hearspace.core.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import hearspace.core.types.Intrinsics
import kotlin.math.PI
import kotlin.random.Random

class GeometryTest {

    private val rnd = Random(42)

    private fun randomQuat() = Quaternion(
        rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1,
    ).normalized()

    private fun randomRigid() = randomQuat().toMat4(Vec3(rnd.nextFloat() * 10 - 5, rnd.nextFloat() * 3, rnd.nextFloat() * 10 - 5))

    private fun assertVec(expected: Vec3, actual: Vec3, tol: Float = 1e-5f) {
        assertEquals(expected.x, actual.x, tol, "x of $actual")
        assertEquals(expected.y, actual.y, tol, "y of $actual")
        assertEquals(expected.z, actual.z, tol, "z of $actual")
    }

    private fun assertMat(expected: Mat4, actual: Mat4, tol: Float = 1e-5f) {
        for (i in 0..3) for (j in 0..3) assertEquals(expected[i, j], actual[i, j], tol, "($i,$j)")
    }

    @Test
    fun `quaternion to rotation and back is the same rotation`() {
        repeat(1000) {
            val q = randomQuat()
            val back = Quaternion.fromRotation(q.toRotation())
            assertTrue(Quaternion.sameRotation(q, back, 1e-5f), "$q vs $back")
        }
        // 대각합이 음수가 되는 180° 근처 회전도
        for (axis in listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f), Vec3(1f, 1f, 0f))) {
            val q = Quaternion.fromAxisAngle(axis, (PI * 0.999).toFloat())
            assertTrue(Quaternion.sameRotation(q, Quaternion.fromRotation(q.toRotation()), 1e-5f))
        }
    }

    @Test
    fun `rotation matrices are orthonormal`() {
        repeat(100) {
            val m = randomQuat().toMat4(Vec3.ZERO)
            for (a in 0..2) for (b in 0..2) assertEquals(if (a == b) 1f else 0f, m.axis(a) dot m.axis(b), 1e-5f)
            assertEquals(1f, (m.axis(0) cross m.axis(1)) dot m.axis(2), 1e-5f) // 오른손
        }
    }

    @Test
    fun `rigid inverse undoes the transform`() {
        repeat(100) {
            val t = randomRigid()
            assertMat(Mat4.IDENTITY, t * t.rigidInverse())
            assertMat(Mat4.IDENTITY, t.rigidInverse() * t)
            val p = Vec3(1f, -2f, 3f)
            assertVec(p, t.rigidInverse().transformPoint(t.transformPoint(p)), 1e-4f)
        }
    }

    @Test
    fun `gl to cv round trip and axis meaning`() {
        repeat(100) {
            val gl = randomRigid()
            val cv = Conventions.glToCv(gl)
            assertMat(gl, Conventions.cvToGl(cv))
            // 같은 카메라 위치
            assertVec(gl.translation(), cv.translation())
            // C_cv +Z(앞) = C_gl −Z(시선), C_cv +Y(아래) = C_gl −Y, +X는 같음
            assertVec(-gl.axis(2), cv.axis(2))
            assertVec(-gl.axis(1), cv.axis(1))
            assertVec(gl.axis(0), cv.axis(0))
        }
        assertMat(Mat4.IDENTITY, Conventions.GL_CV_FLIP * Conventions.GL_CV_FLIP)
    }

    @Test
    fun `backproject and project round trip`() {
        val k = Intrinsics(123.59f, 123.59f, 79.39f, 43.63f, 160, 90)
        repeat(200) {
            val u = rnd.nextFloat() * 160
            val v = rnd.nextFloat() * 90
            val d = 0.3f + rnd.nextFloat() * 5
            val p = Projection.backproject(u, v, d, k)
            assertEquals(d, p.z, 1e-6f)
            val (u2, v2) = Projection.project(p, k)!!
            assertEquals(u, u2, 1e-3f)
            assertEquals(v, v2, 1e-3f)
        }
        assertNull(Projection.project(Vec3(0f, 0f, -1f), k))
        // 주점은 광축 위
        assertVec(Vec3(0f, 0f, 2f), Projection.backproject(k.cx, k.cy, 2f, k))
    }

    @Test
    fun `backprojectToWorld skips invalid depth and honours subsample`() {
        val k = Intrinsics(10f, 10f, 2f, 1f, 5, 3)
        val depth = ShortArray(15) { 1000 }
        depth[0] = 0
        val all = Projection.backprojectToWorld(depth, k, Mat4.IDENTITY, 1)
        assertEquals(14 * 3, all.size)
        val sub = Projection.backprojectToWorld(depth, k, Mat4.IDENTITY, 2)
        // 픽셀 (0,0) (2,0) (4,0) (0,2) (2,2) (4,2) 중 (0,0)은 무효
        assertEquals(5 * 3, sub.size)
        // 월드 변환 적용: 카메라를 (0, 1, 0)에 두면 점도 1 m 위
        val moved = Projection.backprojectToWorld(depth, k, Quaternion.IDENTITY.toMat4(Vec3(0f, 1f, 0f)), 1)
        for (i in 0 until 14) assertEquals(all[3 * i + 1] + 1f, moved[3 * i + 1], 1e-6f)
        // uint16 상한 근처 값도 음수로 해석하지 않는다
        val far = ShortArray(15) { 60000.toShort() }
        assertEquals(60f, Projection.backprojectToWorld(far, k, Mat4.IDENTITY, 1)[2], 1e-3f)
    }

    @Test
    fun `head relative azimuth is right positive and ignores height`() {
        val head = HeadPose(Vec3(0f, 1.5f, 0f), Vec3(0f, 0f, -1f)) // −Z로 걸음, 오른쪽 = +X
        assertVec(Vec3(1f, 0f, 0f), head.rightW)
        val ahead = headRelative(Vec3(0f, 0f, -2f), head)
        assertEquals(0f, ahead.azimuthDeg, 1e-4f)
        assertEquals(2f, ahead.horizontalDistM, 1e-5f)
        assertEquals(45f, headRelative(Vec3(1f, 3f, -1f), head).azimuthDeg, 1e-4f)
        assertEquals(-90f, headRelative(Vec3(-2f, 0f, 0f), head).azimuthDeg, 1e-4f)
        assertEquals(180f, kotlin.math.abs(headRelative(Vec3(0f, 0f, 1f), head).azimuthDeg), 1e-4f)
    }

    @Test
    fun `head pose from camera applies grip offset in heading frame`() {
        // 진행 방향 +X, 오른쪽 = +X × +Y = +Z
        val h = HeadPose.fromCamera(Vec3(1f, 1f, 1f), Vec3(2f, 0.3f, 0f), Vec3(0.1f, 0.5f, -0.3f))
        assertVec(Vec3(1f, 0f, 0f), h.headingW)
        assertVec(Vec3(1f - 0.3f, 1.5f, 1f + 0.1f), h.positionW)
    }

    @Test
    fun `lateral grip offset biases azimuth as spec section 5 says`() {
        // 폰이 몸 중심에서 오른쪽 0.2 m, 1 m 앞 장애물(카메라 정면): 머리 기준으로 약 11° 오른쪽
        val cam = Vec3(0.2f, 1f, 0f)
        val head = HeadPose.fromCamera(cam, Vec3(0f, 0f, -1f), Vec3(-0.2f, 0.5f, 0f))
        val az = headRelative(Vec3(0.2f, 0.5f, -1f), head).azimuthDeg
        assertEquals(11.3f, az, 0.1f)
    }
}
