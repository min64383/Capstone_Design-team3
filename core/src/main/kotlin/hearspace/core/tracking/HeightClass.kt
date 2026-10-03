package hearspace.core.tracking

import hearspace.core.types.ClusterConfig
import hearspace.core.types.HeightClass

/**
 * 높이 분류 (§7.4): 군집 전체가 아니라 **통로 안에 들어온 점**의 최저 높이로 판정한다.
 * 벽에 붙은 간판처럼 바닥까지 이어진 구조물의 돌출부를 놓치지 않기 위해서다. 통로 안 점이 없으면 null(= 통로 밖).
 */
object HeightClassifier {

    /** [corridorPointHeightsM]: 통로 안 점들의 바닥 위 높이. */
    fun classify(corridorPointHeightsM: List<Float>, cfg: ClusterConfig): HeightClass? {
        val lowest = corridorPointHeightsM.minOrNull() ?: return null
        return when {
            lowest >= cfg.headMinM -> HeightClass.HEAD
            lowest >= cfg.bodyMinM -> HeightClass.BODY
            else -> HeightClass.FLOOR
        }
    }
}
