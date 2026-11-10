package hearspace.core.frontend

import hearspace.core.geometry.Mat4
import hearspace.core.types.DepthFrame
import hearspace.core.types.Intrinsics
import hearspace.core.types.LabelImage
import hearspace.core.types.SemanticLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 거리 비례 띠(M56, `frontend.bandWidthM`): 합성 장면 — 5 m 벽(건물 라벨) 앞에 1 m 상자와 3 m 상자(물체 라벨). 깊이 fx 125(실측 깊이 영상과 같음).
 * 5 cm는 1 m에서 6.25화소(→ 최대 6), 3 m에서 2.1화소(→ 최소 3).
 */
class LabelBandWidthTest {
    private val w = 160
    private val h = 90
    private val kd = Intrinsics(125f, 125f, 80f, 45f, w, h)
    private val kl = Intrinsics(125f, 125f, 80.5f, 45.5f, w, h)

    private fun near(u: Int, v: Int) = u in 20..59 && v in 20..69
    private fun far(u: Int, v: Int) = u in 100..139 && v in 20..69

    private val depthMm = ShortArray(w * h) { i -> (if (near(i % w, i / w)) 1000 else if (far(i % w, i / w)) 3000 else 5000).toShort() }
    private val labels = ByteArray(w * h) { i -> (if (near(i % w, i / w) || far(i % w, i / w)) SemanticLabel.OBJECT.pixel else SemanticLabel.BUILDING.pixel).toByte() }
    private val depth = DepthFrame(0L, depthMm, null, kd, Mat4.IDENTITY, "synthetic")
    private val field = LabelBand.field(LabelImage(0L, labels, kl, Mat4.IDENTITY), kd.fx, 6)

    @Test
    fun widthFollowsNearestDepth() {
        val lim = LabelBand.widthByDepth(depthMm, w, h, kd.fx, 0.05f, 3, 6)
        assertEquals(6f, lim[45 * w + 24], 1e-4f) // 1 m 상자 안: 6.25 → 6
        assertEquals(3f, lim[45 * w + 104], 1e-4f) // 3 m 상자 안: 2.08 → 3
        assertEquals(6f, lim[45 * w + 62], 1e-4f) // 1 m 상자 바로 옆 벽: 가장 가까운 깊이 = 상자
    }

    @Test
    fun stepRuleNearMaxFarThree() {
        val lim = LabelBand.stepByDepth(depthMm, w, h, 1.5f, 3, 6)
        assertEquals(6f, lim[45 * w + 24], 1e-4f) // 1 m 상자: 1.5 m 안 → 6
        assertEquals(3f, lim[45 * w + 104], 1e-4f) // 3 m 상자: 1.5 m 밖 → 3
        val (byStep, _) = LabelBand.apply(depthMm, depth, field, pxLimit = lim)
        assertTrue(byStep[45 * w + 104].toInt() != 0 && byStep[45 * w + 102].toInt() == 0, "3 m 상자: 가장자리 4화소는 남기고 2화소는 지움")
        assertTrue(byStep[45 * w + 24].toInt() == 0, "1 m 상자: 가장자리 4화소도 지움")
    }

    @Test
    fun farObjectKeepsMoreOfItsEdgeNearObjectLosesTheSame() {
        val (plain, _) = LabelBand.apply(depthMm, depth, field)
        val lim = LabelBand.widthByDepth(depthMm, w, h, kd.fx, 0.05f, 3, 6)
        val (byDepth, _) = LabelBand.apply(depthMm, depth, field, pxLimit = lim)
        fun removed(o: ShortArray, u: Int, v: Int) = o[v * w + u].toInt() == 0
        assertTrue(removed(plain, 104, 45) && !removed(byDepth, 104, 45), "3 m 상자 가장자리에서 4화소: 고정 6화소 띠는 지우고 거리 비례 띠(3화소)는 남긴다")
        assertTrue(removed(plain, 102, 45) && removed(byDepth, 102, 45), "3 m 상자 가장자리에서 2화소: 둘 다 지운다")
        assertTrue(removed(plain, 24, 45) && removed(byDepth, 24, 45), "1 m 상자 가장자리에서 4화소: 둘 다 지운다(6화소)")
        assertEquals(depthMm[45 * w + 40], byDepth[45 * w + 40]) // 1 m 상자 가운데는 그대로
    }

    /** 먼 가는 물체: 2.5 m 기둥 9화소(18 cm, 볼라드 굵기). 영역 안쪽 반지름 한도가 먼저 걸려 고정 6화소 띠도 기둥을 다 지우지 못하고, 계단 띠와 같다. */
    @Test
    fun thinFarPoleKeepsItsCenterUnderBothBands() {
        fun pole(u: Int, v: Int) = u in 76..84 && v in 20..69
        val dm = ShortArray(w * h) { i -> (if (pole(i % w, i / w)) 2500 else 5000).toShort() }
        val lb = ByteArray(w * h) { i -> (if (pole(i % w, i / w)) SemanticLabel.OBJECT.pixel else SemanticLabel.BUILDING.pixel).toByte() }
        val d = DepthFrame(0L, dm, null, kd, Mat4.IDENTITY, "synthetic")
        val f = LabelBand.field(LabelImage(0L, lb, kl, Mat4.IDENTITY), kd.fx, 6)
        val (fixed, _) = LabelBand.apply(dm, d, f)
        val (step, _) = LabelBand.apply(dm, d, f, pxLimit = LabelBand.stepByDepth(dm, w, h, 1.5f, 3, 6))
        fun kept(o: ShortArray) = (76..84).filter { o[45 * w + it].toInt() != 0 }
        assertEquals(listOf(79, 80, 81), kept(fixed)) // 안쪽 반지름 4 → 한도 min(6, 2) = 2: 가장자리 3화소씩 빼고 가운데 3화소 남김
        assertEquals(kept(fixed), kept(step))
    }
}
