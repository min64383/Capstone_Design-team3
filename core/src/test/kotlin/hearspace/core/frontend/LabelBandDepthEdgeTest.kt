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
 * 깊이 경계 띠(M56 설계 3.6절, `frontend.bandDepthEdgeSigmas`): 합성 장면 — 5 m 벽(건물 라벨) 앞 2 m 상자(물체 라벨)가 2 m 바닥(보도 라벨)에 놓였다.
 * 상자 옆 경계는 깊이가 튀고(막이 생길 곳) 상자 아래 경계는 깊이가 이어진다(막이 없을 곳).
 */
class LabelBandDepthEdgeTest {
    private val w = 40
    private val h = 30
    private val kd = Intrinsics(30f, 30f, 20f, 15f, w, h)
    // 라벨 K는 화소 중심이 정수 화소로 떨어지게 반 화소 옮긴다(내림 투영의 경계 오차를 피함)
    private val kl = Intrinsics(30f, 30f, 20.5f, 15.5f, w, h)
    private val b = 3

    private fun isBox(u: Int, v: Int) = u in 10..19 && v in 10..24
    private fun isFloor(v: Int) = v >= 25

    private val depthMm = ShortArray(w * h) { i ->
        val u = i % w
        val v = i / w
        (if (isBox(u, v) || isFloor(v)) 2000 else 5000).toShort()
    }
    private val labels = ByteArray(w * h) { i ->
        val u = i % w
        val v = i / w
        (when {
            isBox(u, v) -> SemanticLabel.OBJECT.pixel
            isFloor(v) -> SemanticLabel.SIDEWALK.pixel
            else -> SemanticLabel.BUILDING.pixel
        }).toByte()
    }
    private val depth = DepthFrame(0L, depthMm, null, kd, Mat4.IDENTITY, "synthetic")
    private val field = LabelBand.field(LabelImage(0L, labels, kl, Mat4.IDENTITY), kd.fx, b)

    private fun removed(out: ShortArray, u: Int, v: Int) = out[v * w + u].toInt() == 0

    @Test
    fun withoutConditionBandRemovesBoxBottomTooWithConditionOnlyTheJumpingSides() {
        val (plain, _) = LabelBand.apply(depthMm, depth, field)
        val near = LabelBand.depthEdgeNear(depthMm, w, h, 2f, -0.002f, 0.0101f, b)
        val (cond, _) = LabelBand.apply(depthMm, depth, field, near)
        // 상자 아래 경계(물체–보도, 깊이 이어짐): 라벨 띠는 지우고 깊이 경계 띠는 남긴다
        assertTrue(removed(plain, 15, 24), "라벨 띠는 상자 아래 모서리를 지운다")
        assertTrue(!removed(cond, 15, 24), "깊이 경계 띠는 깊이가 이어지는 상자 아래 모서리를 남긴다")
        assertTrue(!removed(cond, 15, 25), "바닥 쪽도 남긴다")
        // 상자 옆 경계(물체–건물, 2 m 대 5 m): 둘 다 지운다
        assertTrue(removed(plain, 10, 15) && removed(cond, 10, 15), "상자 왼쪽 가장자리")
        assertTrue(removed(plain, 9, 15) && removed(cond, 9, 15), "그 바깥 벽 화소")
        // 띠 밖은 그대로
        assertEquals(depthMm[15 * w + 15], cond[15 * w + 15])
    }

    @Test
    fun depthEdgeNearMarksOnlyJumpsAndTheirBand() {
        val near = LabelBand.depthEdgeNear(depthMm, w, h, 2f, -0.002f, 0.0101f, b)
        assertTrue(near[15 * w + 10] && near[15 * w + 9], "튀는 경계의 양쪽")
        assertTrue(near[15 * w + 13], "경계에서 3화소")
        assertTrue(!near[15 * w + 14], "경계에서 4화소(띠 폭 밖)")
        assertTrue(!near[27 * w + 15], "평평한 바닥 가운데")
    }
}
