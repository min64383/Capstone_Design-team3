package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import hearspace.core.guidance.Corridor
import hearspace.core.types.HeightClass
import hearspace.core.types.LabelImage
import hearspace.core.types.Obstacle
import hearspace.core.types.RepStrategy
import hearspace.core.types.SemanticLabel
import hearspace.core.types.TrackConfig
import hearspace.core.types.VisionConfig
import kotlin.math.sqrt

/**
 * 발 접점 추적 기록(M53 `foot.csv`) 한 줄. [kind] M = 새 의미 영상 측정을 반영한 track, O = 깊이 장마다 출력 후보(움직이는 track)의 결정.
 * M 줄의 [measW]·[distM]·[sigmaM]·[chi2]는 이번 측정(새 track이면 χ² NaN), O 줄은 NaN·null.
 */
data class FootDebug(
    val kind: Char,
    val tNs: Long,
    val id: Int,
    val label: Int,
    val nMeas: Int,
    val measW: Vec3?,
    val distM: Float,
    val sigmaM: Float,
    val chi2: Float,
    val posW: Vec3?,
    val velW: Vec3?,
    val velSigmaMps: Float,
    val moving: Boolean,
    val output: Boolean,
    val suppressed: Boolean,
)

/**
 * 깊이 밖 사람의 발 접점 track(M53 설계 3.5절, 표 6). 지금 추적([Tracker])과 따로 돌고 복셀 지도·칸·깊이 track을 읽지도 바꾸지도 않는다.
 * 깊이 track 출력은 출력 고르기(규칙 6)에서만 본다. 과거 값만 쓴다(카메라 높이 중앙값, 이미 받은 의미 영상).
 */
class FarTracker(
    private val vision: VisionConfig,
    private val track: TrackConfig,
    /** 출력에 필요한 측정 수(`map.minHits`, M48 움직이는 track 출력 문턱과 같음). */
    private val minMeas: Int,
    /** 정보 나이 허용치(ns, `policy.maxInfoAgeMs`): 마지막 측정이 이보다 오래되면 지운다. */
    private val maxInfoAgeNs: Long,
) {
    private class FarTrack(val id: Int, val label: Int, val filter: MotionFilter) {
        var nMeas = 0
        var last: FootMeas? = null
        var moving = false
    }

    private val tracks = ArrayList<FarTrack>()
    private var nextId = FIRST_ID
    private var lastImageNs = Long.MIN_VALUE
    private val heights = ArrayDeque<Pair<Long, Float>>()
    private val movingPixels = vision.movingLabels.map { it.pixel }.toSet()
    private val maxSpeedOfPixel = SemanticLabel.values().associate { it.pixel to (vision.maxSpeedMps[it] ?: vision.maxSpeedMps.values.maxOrNull() ?: 0f) }

    /** 마지막 [step]의 기록. */
    var lastDebug: List<FootDebug> = emptyList()
        private set

    /**
     * 깊이 장 하나(시각 [tNs], 카메라 높이 [camY], 앱 바닥 [floorY])마다 부른다. [labels]가 새 의미 영상이면 발 접점을 재서 track을 갱신하고,
     * 움직이는 track 중 출력할 것을 낸다. [depthOut]은 이번 장 깊이 추적 출력(같은 사람을 깊이의 움직이는 track이 내면 발 접점 출력은 내지 않는다).
     */
    fun step(tNs: Long, camY: Float, floorY: Float, labels: LabelImage?, corridor: Corridor, depthOut: List<Obstacle>): List<Obstacle> {
        val debug = ArrayList<FootDebug>()
        // 설계 3.4절: 카메라 높이(카메라 y − 앱 바닥)의 지난 창 중앙값으로 바닥면을 만든다(가슴 하네스 카메라 높이는 거의 일정)
        heights.addLast(tNs to camY - floorY)
        val windowNs = (vision.footHeightWindowS * 1e9).toLong()
        while (heights.first().first < tNs - windowNs) heights.removeFirst()
        if (labels != null && labels.tCaptureNs > lastImageNs) {
            lastImageNs = labels.tCaptureNs
            val hs = heights.map { it.second }.sorted()
            val med = if (hs.size % 2 == 1) hs[hs.size / 2] else (hs[hs.size / 2 - 1] + hs[hs.size / 2]) / 2f
            // 바닥 항 σh = 같은 창 카메라 높이의 흩어짐(σ), 0단계 값보다 작게 두지 않는다(M53 1단계 SM-19: 창이 덜 찬 동안 중앙값이 바닥 흔들림을
            // 따라가 가짜 속도가 판정됨 → 바닥이 불안한 만큼 측정을 덜 믿게. 바닥이 깨끗하면 0단계 값 그대로)
            val mean = hs.average()
            val sd = kotlin.math.sqrt(hs.sumOf { (it - mean) * (it - mean) } / hs.size).toFloat()
            val meas = FootContact.measure(labels, movingPixels, labels.worldFromCam.translation().y - med, vision, maxOf(vision.footFloorSigmaM, sd))
            associate(meas, debug)
        }
        tracks.removeAll { t -> tNs - (t.last?.tNs ?: Long.MIN_VALUE) > maxInfoAgeNs }
        val out = ArrayList<Obstacle>()
        for (t in tracks) {
            if (!t.moving) continue
            val last = t.last ?: continue
            val (p, sp) = t.filter.predictedAt(tNs) ?: continue
            val rep = Vec3(p.x, floorY + vision.footBodyHeightM, p.z)
            val ready = t.nMeas >= minMeas && corridor.contains(rep)
            // 규칙 6: 깊이의 움직이는 track 출력이 이 track의 99% 게이트 + 짝짓기 반경 안이면 깊이 쪽(같은 사람, 깊이가 더 정확)
            val gateM = sqrt(track.rawGateChi2 * (sp * sp + last.sigmaM * last.sigmaM)) + track.matchRadiusM
            val suppressed = depthOut.any { it.moving && (it.repPointW - rep).horizontal().norm() <= gateM }
            val emit = ready && !suppressed
            debug += FootDebug('O', tNs, t.id, t.label, t.nMeas, null, Float.NaN, Float.NaN, Float.NaN, p, t.filter.velocityW, t.filter.velocitySigmaMps, true, emit, suppressed)
            if (!emit) continue
            out += Obstacle(
                id = t.id,
                repPointW = rep,
                repCandidatesW = RepStrategy.values().associateWith { rep },
                aabbMinW = Vec3(p.x, floorY, p.z),
                aabbMaxW = Vec3(p.x, floorY + 2 * vision.footBodyHeightM, p.z),
                heightClass = HeightClass.BODY,
                inCorridor = true,
                confidence = 1f,
                lastSeenNs = last.tNs,
                nObservations = t.nMeas,
                velocityW = t.filter.velocityW,
                motionRepW = rep,
                moving = true,
                positionSigmaM = sp,
                velocitySigmaMps = t.filter.velocitySigmaMps,
            )
        }
        lastDebug = debug
        return out
    }

    /**
     * 규칙 2~4: χ²(칼만 준비 전이면 시작 게이트와 같은 꼴의 정규화 거리²)가 작은 짝부터 하나씩, 측정 하나는 track 하나만. 준비된 track은
     * χ² ≤ `track.rawGateChi2`, 준비 전 track은 거리 ≤ 최대 속도 · Δt + `track.initGateSigmas` · √(σ이전² + σ지금²)(M52 시작 게이트). 같은 라벨끼리만.
     * 짝 없는 측정은 새 track. 짝지은 track은 속도 > `track.minMovingMps`·속도 χ² > `track.movingChi2`이면 움직임(M48 추정 판정).
     */
    private fun associate(meas: List<FootMeas>, debug: MutableList<FootDebug>) {
        class Pair3(val cost: Float, val t: FarTrack, val i: Int, val chi2: Float)
        val pairs = ArrayList<Pair3>()
        for (t in tracks) {
            val last = t.last ?: continue
            for ((i, m) in meas.withIndex()) {
                if (m.label != t.label) continue
                if (t.filter.ready) {
                    val c = t.filter.innovationChi2(m.tNs, m.pW, m.sigmaM) ?: continue
                    if (c <= track.rawGateChi2) pairs += Pair3(c, t, i, c)
                } else {
                    val dt = (m.tNs - last.tNs) / 1e9f
                    val s2 = last.sigmaM * last.sigmaM + m.sigmaM * m.sigmaM
                    val d = (m.pW - last.pW).horizontal().norm()
                    if (d <= (maxSpeedOfPixel[m.label] ?: 0f) * dt + track.initGateSigmas * sqrt(s2)) pairs += Pair3(d * d / s2, t, i, Float.NaN)
                }
            }
        }
        pairs.sortWith(compareBy({ it.cost }, { it.t.id }, { it.i }))
        val usedT = HashSet<FarTrack>()
        val usedM = HashSet<Int>()
        fun apply(t: FarTrack, m: FootMeas, chi2: Float) {
            t.filter.update(m.tNs, m.pW, m.sigmaM)
            t.nMeas++
            t.last = m
            val v = t.filter.velocityW
            t.moving = t.filter.ready && v != null && v.norm() > track.minMovingMps && t.filter.velocityChi2 > track.movingChi2
            debug += FootDebug('M', m.tNs, t.id, t.label, t.nMeas, m.pW, m.distM, m.sigmaM, chi2, t.filter.positionW, v, t.filter.velocitySigmaMps, t.moving, false, false)
        }
        for (p in pairs) {
            if (p.t in usedT || p.i in usedM) continue
            usedT += p.t
            usedM += p.i
            apply(p.t, meas[p.i], p.chi2)
        }
        for ((i, m) in meas.withIndex()) {
            if (i in usedM) continue
            val t = FarTrack(nextId++, m.label, MotionFilter(track.accelSigmaMps2))
            tracks += t
            apply(t, m, Float.NaN)
        }
    }

    companion object {
        /** 발 접점 track 번호의 시작(깊이 track 번호와 겹치지 않게). */
        const val FIRST_ID = 1_000_000
    }
}
