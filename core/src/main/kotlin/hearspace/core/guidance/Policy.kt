package hearspace.core.guidance

import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Vec3
import hearspace.core.geometry.headRelative
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.HeightClass
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PolicyConfig
import hearspace.core.types.SoundKind

/**
 * 거리 구간과 음원 선택 (§7.5). 구간은 머리 기준 **진행 방향 거리**로 나눈다:
 * STOP < stopM ≤ WARN < warnMaxM ≤ SILENT < silentMaxM, 그 밖은 후보 제외.
 * 히스테리시스(물체 id별): 더 급한 구간으로는 경계에서 바로 들어가고, 덜 급한 구간으로는 경계 + `hysteresisM`을 넘어야 나간다
 * (가까워질 때 늦게 울리는 쪽으로 틀리지 않게 — 안전 쪽 비대칭).
 *
 * 움직이는 물체(M48, [hearspace.core.types.Obstacle.moving], 설계 3.4절): 진행 방향 거리를 블록 시각까지 지난 속도로 늘이되 멀어지는 쪽으로는
 * 늘이지 않고(d = min(d_tc, d_tc − 다가오는 속도 × 정보 나이)), 위치 불확실성만큼 가깝게 본다(d_lo = d − k·σ_d, σ_d² = σ_p² + (나이·σ_v)²).
 * d_lo < stopM이거나 TTC = d_lo ÷ (다가오는 상대 속도 + k·σ_v) < `ttcStopS`면 STOP. 과거 깊이·자세만 쓰는 외삽이다(미래 프레임 없음).
 */
class Policy(private val cfg: PolicyConfig, private val behindM: Float) {

    private val lastRank = HashMap<Int, Int>()

    /**
     * 스냅샷의 물체들로 음원 명령을 만든다. 정보 나이가 허용치를 넘으면 빈 목록(§2.2-4).
     * 가까운 순으로 `maxSources`개. prioritizeStop이면 통로 안 STOP을 우선한다.
     * SILENT 구간은 명령을 내되 소리는 내지 않는다(렌더러 몫).
     */
    fun commands(snapshot: ObstacleSnapshot, head: HeadPose, infoAgeMs: Float, userVelW: Vec3 = Vec3.ZERO): List<AudioCmd> {
        if (infoAgeMs > cfg.maxInfoAgeMs) return emptyList()
        val seen = HashSet<Int>()
        val scored = ArrayList<Triple<Float, Int, AudioCmd>>()
        for (o in snapshot.obstacles) {
            seen += o.id
            val v = o.velocityW
            var point = o.repPointW
            var along = (o.repPointW - head.positionW).horizontal() dot head.headingW
            var ttcStop = false
            if (o.moving && v != null) {
                val ageS = infoAgeMs / 1000f
                val closingObj = -(v.horizontal() dot head.headingW)
                if (closingObj > 0f) { // 다가오는 쪽으로만 늘인다
                    along -= closingObj * ageS
                    point = o.repPointW + v.horizontal() * ageS
                }
                val sp = if (o.positionSigmaM.isNaN()) 0f else o.positionSigmaM
                val sv = if (o.velocitySigmaMps.isNaN()) 0f else o.velocitySigmaMps
                along -= cfg.motionSigmaMultiple * kotlin.math.sqrt(sp * sp + ageS * ageS * sv * sv)
                val closingHi = -((v - userVelW).horizontal() dot head.headingW) + cfg.motionSigmaMultiple * sv
                ttcStop = closingHi > 0f && along / closingHi < cfg.ttcStopS
            }
            if (along < -behindM) {
                lastRank.remove(o.id)
                continue
            }
            val rank = if (ttcStop) 0.also { lastRank[o.id] = 0 } else rankWithHysteresis(o.id, along)
            if (rank == EXCLUDED) continue
            val rel = headRelative(point, head)
            scored += Triple(
                along,
                o.id,
                AudioCmd(
                    obstacleId = o.id,
                    azimuthDeg = rel.azimuthDeg,
                    distanceM = rel.horizontalDistM,
                    band = BANDS[rank],
                    sound = if (o.heightClass == HeightClass.HEAD) SoundKind.HEAD_TONE else SoundKind.FLOOR_PULSE,
                    infoAgeMs = infoAgeMs,
                    inCorridor = o.inCorridor,
                    heightDeltaM = point.y - head.positionW.y,
                ),
            )
        }
        lastRank.keys.retainAll(seen)
        val sorted = if (cfg.prioritizeStop) {
            scored.sortedWith(compareBy<Triple<Float, Int, AudioCmd>>(
                { if (it.third.inCorridor && it.third.band == Band.STOP) 0 else 1 },
                { it.first }, { it.second }))
        } else scored.sortedWith(compareBy({ it.first }, { it.second }))
        return sorted.take(cfg.maxSources).map { it.third }
    }

    /** 구간 기억을 모두 지운다(상태가 UNKNOWN·PAUSED로 바뀔 때). */
    fun clear() = lastRank.clear()

    private fun rankOf(d: Float) = when {
        d < cfg.stopM -> 0
        d < cfg.warnMaxM -> 1
        d < cfg.silentMaxM -> 2
        else -> EXCLUDED
    }

    private fun rankWithHysteresis(id: Int, along: Float): Int {
        val raw = rankOf(along)
        val prev = lastRank[id]
        val rank = if (prev == null || raw <= prev) raw else maxOf(prev, rankOf(along - cfg.hysteresisM))
        lastRank[id] = rank
        return rank
    }

    private companion object {
        const val EXCLUDED = 3
        val BANDS = listOf(Band.STOP, Band.WARN, Band.SILENT)
    }
}

