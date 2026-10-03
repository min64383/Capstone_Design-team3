package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.types.CorridorConfig
import kotlin.math.abs

/**
 * 진행 통로 (§7.5): 사용자 위치 [originW]에서 진행 방향 [headingW] 기준 폭 `widthM`, 앞 `lengthM`, 뒤 `behindM`,
 * 바닥 위 0 ~ `heightM`의 상자. 진행 방향 추정(Heading, M5)은 호출하는 쪽 책임이다.
 */
class Corridor(val originW: Vec3, headingW: Vec3, val floorY: Float, private val cfg: CorridorConfig) {

    /** 수평 단위 진행 방향. */
    val headingW: Vec3 = headingW.horizontal().normalized()

    private val rightW = this.headingW cross Vec3.UP

    /** 진행 방향 거리(앞 +). */
    fun alongM(pW: Vec3): Float = (pW - originW).horizontal() dot headingW

    /** 좌우 거리(오른쪽 +). */
    fun lateralM(pW: Vec3): Float = (pW - originW).horizontal() dot rightW

    /** 통로 안인지. */
    fun contains(pW: Vec3): Boolean {
        val along = alongM(pW)
        val h = pW.y - floorY
        return along >= -cfg.behindM && along <= cfg.lengthM &&
            abs(lateralM(pW)) <= cfg.widthM / 2 &&
            h >= 0f && h <= cfg.heightM
    }
}
