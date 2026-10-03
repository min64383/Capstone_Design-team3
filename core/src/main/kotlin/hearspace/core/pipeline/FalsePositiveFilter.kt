package hearspace.core.pipeline

import hearspace.core.types.FalsePositiveFilterConfig

/**
 * 실제 빈 복도 녹화에서 반복된 "통로를 넓게·길게·거의 전 높이로 채우는 depth sheet" 후보를 찾는 실험 필터.
 *
 * 안전상 중요한 점:
 * - 일반적인 큰 물체 하나만으로 제거하지 않는다.
 * - 좌우 폭 + 진행방향 길이 + 높이 범위 + 바닥/머리 높이 동시 점유 + 복셀 수를 모두 만족해야 한다.
 * - v2: 군집의 가장 가까운 경계가 `minAlongMinM`보다 가까우면 필터하지 않는다. 근거리 위험물을 휴리스틱으로 지우지 않기 위한 안전 조건이다.
 * - 기본 설정은 disabled다. 실측 비교용으로만 켠 뒤, 실제 장애물 누락을 확인하고 채택 여부를 정한다.
 */
object FalsePositiveFilter {
    const val REASON = "FULL_CORRIDOR_DEPTH_SHEET_V2"

    fun reason(d: ClusterDebug, cfg: FalsePositiveFilterConfig): String? {
        if (!cfg.enabled) return null

        val looksLikeFullCorridorDepthSheet =
            d.nVoxels >= cfg.minVoxels &&
                d.lateralSpanM >= cfg.minLateralSpanM &&
                d.alongSpanM >= cfg.minAlongSpanM &&
                d.alongMinM >= cfg.minAlongMinM &&
                d.heightSpanM >= cfg.minHeightSpanM &&
                d.heightMinM <= cfg.maxHeightMinM &&
                d.heightMaxM >= cfg.minHeightMaxM

        return if (looksLikeFullCorridorDepthSheet) REASON else null
    }
}
