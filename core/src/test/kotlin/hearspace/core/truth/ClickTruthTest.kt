package hearspace.core.truth

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.Intrinsics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** 클릭 정답(IMPROVE_SPEC §9.5, M47): 합성 카메라로 투영한 화소를 되돌리면 같은 세계 점이 나와야 한다. */
class ClickTruthTest {
    private val k = Intrinsics(503f, 499f, 314.2f, 233.8f, 640, 480) // S10 세션 meta의 영상 내부 파라미터

    /** 월드 −z를 보며 [pitchDeg]만큼 숙이고 [yawDeg]만큼 돈, 높이 [heightM]의 C_cv 카메라. */
    private fun camera(heightM: Float, pitchDeg: Float, yawDeg: Float, at: Vec3 = Vec3(0.3f, 0f, -0.2f)): Mat4 {
        val p = Math.toRadians(pitchDeg.toDouble())
        val y = Math.toRadians(yawDeg.toDouble())
        val f0 = Vec3(0f, -sin(p).toFloat(), -cos(p).toFloat())
        val d0 = Vec3(0f, -cos(p).toFloat(), sin(p).toFloat())
        fun yaw(v: Vec3) = Vec3((v.x * cos(y) + v.z * sin(y)).toFloat(), v.y, (-v.x * sin(y) + v.z * cos(y)).toFloat())
        val f = yaw(f0)
        val d = yaw(d0)
        return Mat4.fromAxes(d cross f, d, f, Vec3(at.x, at.y + heightM, at.z))
    }

    private fun roundTrip(worldFromCam: Mat4, w: Vec3) {
        val (u, v) = Projection.project(worldFromCam.rigidInverse().transformPoint(w), k)!!
        val back = ClickTruth.planePoint(worldFromCam, k, u, v, w.y)!!
        assertTrue((back - w).norm() < 1e-3f, "round trip $w → ($u, $v) → $back")
    }

    @Test
    fun feetAndHeadPointsComeBack() {
        val floor = -1.4f
        for (pitch in listOf(0f, 10f, 25f)) for (yaw in listOf(0f, 35f, -120f)) {
            val cam = camera(1.2f, pitch, yaw, Vec3(0.3f, floor, -0.2f))
            val fwd = cam.transformDir(Vec3(0f, 0f, 1f)).horizontal().normalized()
            val side = Vec3(-fwd.z, 0f, fwd.x)
            val base = cam.translation().horizontal() + Vec3(0f, floor, 0f)
            // 발: 1.9~3.5 m 바닥 점, 머리 끝: 0.8~2 m 키 1.72 m
            for (dist in listOf(1.9f, 2.5f, 3.5f)) roundTrip(cam, base + fwd * dist + side * 0.3f)
            if (pitch < 20f) for (dist in listOf(1.2f, 2f)) roundTrip(cam, base + fwd * dist + side * -0.2f + Vec3(0f, 1.72f, 0f))
        }
    }

    @Test
    fun rayAboveHorizonHasNoFloorPoint() {
        val cam = camera(1.2f, 0f, 0f)
        // 영상 위쪽(센서 v 작음 = 위) 화소는 바닥과 카메라 앞에서 만나지 않는다
        assertNull(ClickTruth.planePoint(cam, k, k.cx, 10f, -1.2f + 0f))
    }

    @Test
    fun planeHeightForHeadClick() {
        assertEquals(-1.4f + 1.72f, ClickTruth.planeY(ClickPlane.HEAD, -1.4f, 1.72f), 1e-6f)
        assertEquals(-1.4f, ClickTruth.planeY(ClickPlane.FEET, -1.4f, 1.72f), 1e-6f)
    }

    @Test
    fun saveAndLoadKeepEverything(@TempDir dir: File) {
        val a = ClickAnnotations(
            listOf(
                ClickObject("p1", "moving", "point", "person", 0.2f, 1.72f, "blind", "S1", "STOP",
                    listOf(Click(123L, 320.5f, 400.25f, ClickPlane.FEET, -1.4f, "rgb", Vec3(0.1f, -1.4f, -2.5f)),
                        Click(456L, 300f, 100f, ClickPlane.HEAD, -1.4f, "depth", null))),
                ClickObject("o1", "static", "line", "wall base", 0f, 0f, "review", null, null, emptyList()),
            ),
            floorYFallback = -1.35f,
        )
        val f = File(dir, ClickTruth.FILE)
        ClickTruth.save(f, a)
        assertEquals(a, ClickTruth.load(f))
    }
}
