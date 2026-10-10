package hearspace.core.mapping

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.types.HitWeighting
import hearspace.core.types.Intrinsics
import hearspace.core.types.MapConfig
import hearspace.core.types.MapMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 복셀 하나의 읽기 전용 모습. */
data class VoxelView(
    val ix: Int,
    val iy: Int,
    val iz: Int,
    /** 복셀 중심(월드). */
    val centerW: Vec3,
    val hits: Int,
    val score: Float,
    val lastSeenNs: Long,
    /** 로그 오즈(`map.mode` LOG_ODDS에서만 쓰임). */
    val logOdds: Float,
    /** 벽 평면 점(평면 추출의 WALL 라벨)으로 맞은 장 수. [hits] 중 이 비율이 `cluster.wallFraction` 이상이면 벽 칸(C2). */
    val wallHits: Int = 0,
    /** 인스턴스 지도(C3b)의 물체 번호, 0 = 없음. */
    val instId: Int = 0,
    /** 현재 점유 증거(hit-equivalent). `freeEvidenceDecay`가 0이면 기존 누적 hit와 같은 의미. */
    val evidence: Float = hits.toFloat(),
)

/**
 * 칸이 깊이 한 장에서 어떻게 보이는가(M20 대표점 진단). HIT = 이번 장에 점이 들어옴, FREE = 칸 뒤가 관측됨(빈 공간, 감쇠 대상),
 * OCCLUDED = 칸 자리 또는 그 앞이 관측됨, NO_DEPTH = 투영 화소 깊이 무효, OUT_OF_VIEW = 카메라 뒤·영상 밖.
 */
enum class CellView { HIT, FREE, OCCLUDED, NO_DEPTH, OUT_OF_VIEW }

/** 삭제 사유별 개수(로그·테스트용). */
data class PruneCounts(val passed: Int, val unseen: Int, val outOfRadius: Int)

/**
 * 월드 좌표 희소 해시 복셀 점유 맵 (§7.3). 복셀 크기 `map.voxelSizeM`.
 * - 관측: 깊이 한 장에서 비바닥 점이 들어간 복셀은 hits += 1, score += hitGain(최대 1). 같은 장 안에서는 한 번만.
 * - 빈 공간 감쇠: **시야 안에서** 복셀 중심을 깊이 이미지에 투영했을 때 유효 깊이가 복셀보다 `freeMarginM` 이상 멀면
 *   score −= decayPerObservation. 시야 밖·깊이 무효·가려진(깊이가 더 가까운) 복셀은 감쇠하지 않는다(§2.2-6).
 * - 삭제: score ≤ 0, 또는 [prune]의 세 조건(지나감·오래 안 보임·반경 밖)뿐이다.
 * - `map.hitWeighting` DISTANCE(M13.5): 한 장의 표 = 거리 가중치([weight]). 점유는 표의 가중합 ≥ `minHits`, score 증가도 같은
 *   가중치를 곱한다. 먼 관측(평활 깊이의 바닥 들림·꼬리)은 문턱까지 더 많은 장이 필요하고 score가 낮아, 다가가며 얻는 가까운
 *   빈 공간 관측이 먼저 지운다. 감쇠는 가중하지 않는다.
 *
 * `map.mode` LOG_ODDS(M13.2, OctoMap 방식): 칸마다 로그 오즈. 점이 들면 + `logHit` × 가중치, 시야 안에서 칸을 지나 더 멀리
 * 보이면(여유 = max(복셀, `freeMarginRatio` × 칸 깊이)) + `logMiss` × 가중치, [`logMin`, `logMax`]로 묶고, `logOccupied` 이상이면
 * 점유, 하한에 닿으면 지운다. 가중치 = min(1, (`weightRefM` / 거리)²): 먼 관측일수록 약해, 다가가며 얻는 가까운 관측이 멀리서
 * 생긴 칸(끌림·잡음)을 덮어쓴다. 시야 밖·깊이 무효·가려진 칸은 그대로다(§2.2-6). 지나감·오래 안 보임·반경 밖 삭제는 같다.
 *
 * `map.mode` TSDF(M13.6, KinectFusion·Voxblox 방식): 칸마다 부호 있는 거리 sd(m, + = 카메라 쪽 빈 곳)와 가중치. 점이 들면 그 시선을
 * 따라 점 앞뒤 잘림 폭 τ 안의 칸을 만들고(바닥 위만), 매 장 시야 안의 모든 칸을 깊이 영상에 투영해 관측 sd = 측정 깊이 − 칸 깊이를
 * [−τ, τ]로 잘라 가중 평균한다(가중치 [weight]). 측정이 칸보다 τ 넘게 앞이면 가려진 것이라 갱신하지 않는다. 지금 지도는 맞은 칸을
 * 합집합처럼 쌓아 프레임마다 다르게 퍼진 표면이 모두 남지만(캐리어 앞뒤 0.3 → 0.66~0.80 m), TSDF는 평균이라 표면이 얇아진다.
 * 거리는 시선 방향 차(측정 깊이 − 칸 깊이)에 cos(입사각)(이웃 픽셀로 구한 면 법선과 시선, 하한 `tsdfMinCosIncidence`)을 곱한 면 수직
 * 거리다(점–평면 거리, 비스듬한 옆 벽·판 아래면이 지워지지 않게). 칸의 투영 자리(가운데 픽셀) 깊이를 쓰되, 가운데는 빈 곳인데 3×3 이웃에 그 칸 근처(τ 안) 표면이 있으면 윤곽이 애매한 것이라
 * 갱신하지 않는다(물체 윤곽 칸이 옆 배경 픽셀만 보고 지워지지 않게, 얇은 물체 보호). 3×3 최소 깊이를 쓰면 비스듬한 면(상자 윗면·
 * 비스듬한 벽)의 표면이 틀어졌다(SC-23 상자 갈라짐, SC-22). 표면 근처 관측(sd < τ)은 가중 평균(가중치 상한 `tsdfMaxWeight`),
 * 확실한 빈 곳(sd ≥ τ)은 평균에 섞지 않고 가중치를 HITS의 감쇠·증가 비율만큼 깎아 0이면 지운다(공간 깎기, 치운 물체 SC-04).
 * 점유 = 가중치 ≥ `minHits`, |sd| ≤ 복셀 반 대각선(표면이 칸을 지나면 반드시 잡히는 폭), 측정점이 이 띠를 지난 장이 하나 이상(윤곽
 * 보호로 안 지워진 윤곽 바깥 칸이 평균만으로 표면이 되지 않게, SC-02). 맞은 장·벽 표는 시선 위 이 띠의 칸에 센다(C2). 시야 밖·깊이 무효 칸은 그대로다(§2.2-6).
 */
class VoxelMap(private val cfg: MapConfig) {

    private class Voxel(
        var hits: Int, var score: Float, var lastSeenNs: Long, var lastHitFrame: Long, var logOdds: Float, var weightedHits: Float,
        var wallHits: Int, var lastWallFrame: Long, var sd: Float = 0f, var sdWeight: Float = 0f,
        var instId: Int = 0, var instVotes: Int = 0, var lastInstFrame: Long = -1,
        var currentEvidence: Float = weightedHits,
    )

    private val logOdds = cfg.mode == MapMode.LOG_ODDS
    private val tsdf = cfg.mode == MapMode.TSDF
    /** 표면이 칸을 지나면 그 칸 중심까지의 거리는 반 대각선 이하(기하, 설정값이 아님). */
    private val surfaceBandM = cfg.voxelSizeM * sqrt(3f) / 2f
    /** TSDF 빈 곳 관측 한 번에 깎는 가중치: HITS의 감쇠 ÷ 증가(관측 한 장의 무게 1에 대한 비율). */
    private val carveWeight = cfg.decayPerObservation / cfg.hitGain

    /** TSDF 잘림 폭: 깊이 [depthM]에서 max(`tsdfTruncMinM`, `tsdfTruncPerM` × 깊이). */
    fun truncation(depthM: Float): Float = max(cfg.tsdfTruncMinM, cfg.tsdfTruncPerM * depthM)
    private val weighted = cfg.hitWeighting == HitWeighting.DISTANCE

    /** 관측 가중치: 거리 [distM]에서 min(1, (weightRefM / 거리)²). */
    fun weight(distM: Float): Float = if (distM <= cfg.weightRefM) 1f else (cfg.weightRefM / distM).let { it * it }

    private val voxels = HashMap<Long, Voxel>()
    private var frame = 0L

    /** 복셀 수. */
    val size: Int get() = voxels.size

    /** 새 깊이 한 장을 시작한다. [insert]의 "한 장에 한 번" 판정에 쓴다. */
    fun beginFrame() {
        frame++
    }

    /**
     * 비바닥 점 하나를 관측으로 넣는다. [weight]는 관측 가중치([weight] 함수): LOG_ODDS와 HITS의 `hitWeighting` DISTANCE가 쓴다.
     * [wall]이면 그 장의 벽 표로도 센다(한 장에 한 번, C2). TSDF는 [cameraW]가 있으면 시선을 따라 점 앞뒤 잘림 폭 안의 칸을 만든다
     * ([minAllocY] 아래 칸은 만들지 않음: 바닥 근처에 가짜 표면이 생기지 않게).
     */
    fun insert(pW: Vec3, tNs: Long, weight: Float = 1f, wall: Boolean = false, cameraW: Vec3? = null, minAllocY: Float = Float.NEGATIVE_INFINITY) {
        if (tsdf && cameraW != null) allocateAlongRay(pW, cameraW, tNs, minAllocY, weight, wall)
        hit(key(index(pW.x), index(pW.y), index(pW.z)), tNs, weight, wall)
    }

    /** 칸 [key]에 이 장의 관측 한 번(한 장에 한 번). 없으면 만든다. */
    private fun hit(key: Long, tNs: Long, weight: Float, wall: Boolean) {
        val v = voxels[key]
        val vote = if (weighted) weight else 1f
        if (v == null) {
            voxels[key] = Voxel(
                1, min(1f, cfg.hitGain * vote), tNs, frame, min(cfg.logMax, cfg.logHit * weight), vote, if (wall) 1 else 0, if (wall) frame else -1,
                currentEvidence = vote,
            )
            return
        }
        if (wall && v.lastWallFrame != frame) {
            v.wallHits++
            v.lastWallFrame = frame
        }
        if (v.lastHitFrame != frame) {
            v.hits++
            v.weightedHits += vote
            v.currentEvidence += vote
            v.score = min(1f, v.score + cfg.hitGain * vote)
            v.lastSeenNs = tNs
            v.lastHitFrame = frame
            v.logOdds = min(cfg.logMax, v.logOdds + cfg.logHit * weight)
        }
    }

    /**
     * TSDF: 카메라→[pW] 시선 위 [pW] ± 잘림 폭의 칸을 만들거나(값은 투영 갱신이 채운다) 마지막 관측 시각을 새로 한다. 점에서 표면 띠
     * 안의 칸은 이 장의 관측(맞은 장·벽 표)으로 센다.
     */
    private fun allocateAlongRay(pW: Vec3, cameraW: Vec3, tNs: Long, minAllocY: Float, weight: Float, wall: Boolean) {
        val ray = pW - cameraW
        val len = ray.norm()
        if (len <= 0f) return
        val dir = ray * (1f / len)
        val tau = truncation(len)
        val step = cfg.voxelSizeM / 2f
        var s = -tau
        while (s <= tau) {
            val q = pW + dir * s
            s += step
            if (q.y < minAllocY) continue
            val key = key(index(q.x), index(q.y), index(q.z))
            if (abs(s - step) <= surfaceBandM) {
                hit(key, tNs, weight, wall)
                continue
            }
            val v = voxels[key]
            if (v == null) voxels[key] = Voxel(0, 0f, tNs, -1, 0f, 0f, 0, -1) else v.lastSeenNs = max(v.lastSeenNs, tNs)
        }
    }

    /**
     * 픽셀 ([u], [w])에서 면 법선과 시선 사이 cos(하한 `tsdfMinCosIncidence`). 법선은 가로·세로마다 깊이가 가운데와 더 가까운 쪽 이웃
     * 하나와의 차(한쪽 차분)의 외적: 깊이 경계에서 앞뒤 면에 걸친 이웃으로 법선을 구하면 cos가 하한까지 떨어져 빈 곳 칸이 표면이 됐다
     * (막 없는 SC-21에서 상자–벽 사이 21칸). 이웃이 무효면 1(보정 없음).
     */
    private fun cosIncidence(depthMm: ShortArray, k: Intrinsics, u: Int, w: Int): Float {
        if (u < 1 || u >= k.width - 1 || w < 1 || w >= k.height - 1) return 1f
        fun d(uu: Int, ww: Int) = (depthMm[ww * k.width + uu].toInt() and 0xFFFF) / 1000f
        val dc = d(u, w)
        if (dc == 0f) return 1f
        fun p(uu: Int, ww: Int, z: Float) = Vec3((uu - k.cx) * z / k.fx, (ww - k.cy) * z / k.fy, z)
        val c = p(u, w, dc)
        /** 가운데에서 깊이가 더 가까운 쪽 이웃까지의 3D 차(두 이웃 모두 무효면 null). */
        fun side(a: Pair<Int, Int>, b: Pair<Int, Int>): Vec3? {
            val da = d(a.first, a.second)
            val db = d(b.first, b.second)
            val useA = da != 0f && (db == 0f || abs(da - dc) <= abs(db - dc))
            return when {
                useA -> p(a.first, a.second, da) - c
                db != 0f -> c - p(b.first, b.second, db)
                else -> null
            }
        }
        val gu = side(u + 1 to w, u - 1 to w) ?: return 1f
        val gw = side(u to w + 1, u to w - 1) ?: return 1f
        val n = gu cross gw
        val r = c
        val nn = n.norm()
        val rn = r.norm()
        if (nn <= 0f || rn <= 0f) return 1f
        return max(cfg.tsdfMinCosIncidence, abs(n dot r) / (nn * rn))
    }

    /** 픽셀 ([u], [w])의 3×3 이웃에 깊이 [z] ± [tau] 안의 유효 깊이가 있는지. */
    private fun nearSurfaceAround(depthMm: ShortArray, k: Intrinsics, u: Int, w: Int, z: Float, tau: Float): Boolean {
        for (dw in -1..1) for (du in -1..1) {
            val uu = u + du
            val ww = w + dw
            if (uu < 0 || uu >= k.width || ww < 0 || ww >= k.height) continue
            val d = depthMm[ww * k.width + uu].toInt() and 0xFFFF
            if (d != 0 && abs(d / 1000f - z) < tau) return true
        }
        return false
    }

    /** [pW]가 든 칸의 물체 번호(C3b), 칸이 없거나 번호가 없으면 0. */
    fun instanceAt(pW: Vec3): Int = voxels[key(index(pW.x), index(pW.y), index(pW.z))]?.instId ?: 0

    /**
     * [pW]가 든 칸에 물체 번호 [id]를 한 표(한 장에 한 번, 다수결 투표: 같은 번호면 +1, 다르면 −1이고 0이 되면 바뀐다). 칸이 없으면 무시.
     */
    fun voteInstance(pW: Vec3, id: Int) {
        val v = voxels[key(index(pW.x), index(pW.y), index(pW.z))] ?: return
        if (v.lastInstFrame == frame) return
        v.lastInstFrame = frame
        when {
            v.instId == id -> v.instVotes++
            v.instVotes > 0 -> v.instVotes--
            else -> {
                v.instId = id
                v.instVotes = 1
            }
        }
    }

    /** 물체 번호 [from]을 모두 [to]로 바꾼다(C3b 인스턴스 병합). */
    fun relabelInstance(from: Int, to: Int) {
        for (v in voxels.values) if (v.instId == from) v.instId = to
    }

    /** TSDF 투영 갱신(M13.6). 돌려주는 값은 갱신한 칸 수. */
    private fun updateTsdf(depthMm: ShortArray, k: Intrinsics, camFromWorld: Mat4): Int {
        var updated = 0
        val it = voxels.entries.iterator()
        while (it.hasNext()) {
            val (key, v) = it.next()
            val pCv = camFromWorld.transformPoint(center(key))
            if (pCv.z <= 0f) continue // 카메라 뒤: 시야 밖
            val u = (k.fx * pCv.x / pCv.z + k.cx).roundToInt()
            val w = (k.fy * pCv.y / pCv.z + k.cy).roundToInt()
            if (u < 0 || u >= k.width || w < 0 || w >= k.height) continue // 시야 밖
            val mm = depthMm[w * k.width + u].toInt() and 0xFFFF
            if (mm == 0) continue // 관측 없음
            val tau = truncation(pCv.z)
            val sd = (mm / 1000f - pCv.z) * cosIncidence(depthMm, k, u, w)
            if (sd < -tau) continue // 가려짐: 칸이 측정 표면보다 τ 넘게 뒤
            val wt = weight(pCv.z)
            if (sd >= tau) {
                if (nearSurfaceAround(depthMm, k, u, w, pCv.z, tau)) continue // 윤곽이 애매: 얇은 물체 보호
                v.sdWeight -= carveWeight * wt // 확실한 빈 곳: 공간 깎기
                updated++
                if (v.sdWeight <= 0f) it.remove()
                continue
            }
            v.sd = (v.sd * v.sdWeight + sd * wt) / (v.sdWeight + wt)
            v.sdWeight = min(cfg.tsdfMaxWeight, v.sdWeight + wt)
            updated++
        }
        return updated
    }

    /**
     * 현재 깊이 이미지로 빈 공간을 관측한 복셀을 감쇠한다. 이번 장에서 관측된 복셀은 건너뛴다.
     * [depthMm]은 이미 신뢰도 필터를 거친 깊이(0 = 무효). 돌려주는 값은 감쇠한 복셀 수.
     */
    fun decayFree(depthMm: ShortArray, k: Intrinsics, camFromWorld: Mat4): Int {
        if (tsdf) return updateTsdf(depthMm, k, camFromWorld)
        var decayed = 0
        val it = voxels.entries.iterator()
        while (it.hasNext()) {
            val (key, v) = it.next()
            if (v.lastHitFrame == frame) continue
            val pCv = camFromWorld.transformPoint(center(key))
            if (sight(pCv, depthMm, k) != CellView.FREE) continue // 시야 밖·관측 없음·칸 자리 또는 그 앞이 관측됨(가려짐 포함)
            if (logOdds) {
                v.logOdds = max(cfg.logMin, v.logOdds + cfg.logMiss * weight(pCv.z))
                decayed++
                if (v.logOdds <= cfg.logMin) it.remove()
                continue
            }
            v.score -= cfg.decayPerObservation
            if (cfg.freeEvidenceDecay > 0f) v.currentEvidence = max(0f, v.currentEvidence - cfg.freeEvidenceDecay)
            decayed++
            if (v.score <= 0f) it.remove()
        }
        return decayed
    }

    /**
     * 칸 중심 [centerW]가 깊이 [depthMm]에서 어떻게 보이는가([decayFree]와 같은 판정, M20 대표점 진단).
     * [hitThisFrame]이면 [CellView.HIT].
     */
    fun cellView(centerW: Vec3, hitThisFrame: Boolean, depthMm: ShortArray, k: Intrinsics, camFromWorld: Mat4): CellView =
        if (hitThisFrame) CellView.HIT else sight(camFromWorld.transformPoint(centerW), depthMm, k)

    /** 카메라 좌표 [pCv]의 칸이 이 깊이 장에서 어떻게 보이는가(관측 여부 제외). 빈 공간 여유는 지도 방식마다 다르다. */
    private fun sight(pCv: Vec3, depthMm: ShortArray, k: Intrinsics): CellView {
        if (pCv.z <= 0f) return CellView.OUT_OF_VIEW // 카메라 뒤
        val u = (k.fx * pCv.x / pCv.z + k.cx).roundToInt()
        val w = (k.fy * pCv.y / pCv.z + k.cy).roundToInt()
        if (u < 0 || u >= k.width || w < 0 || w >= k.height) return CellView.OUT_OF_VIEW
        val mm = depthMm[w * k.width + u].toInt() and 0xFFFF
        if (mm == 0) return CellView.NO_DEPTH
        val margin = if (logOdds) max(cfg.voxelSizeM, cfg.freeMarginRatio * pCv.z) else cfg.freeMarginM
        return if (mm / 1000f < pCv.z + margin) CellView.OCCLUDED else CellView.FREE
    }

    /**
     * 시야와 무관한 삭제 조건(§7.3): 사용자 뒤로 `passedMarginM` 이상 지나감(진행 방향 기준, 수평),
     * 마지막 관측 후 `maxUnseenS` 경과, 사용자에서 수평 `radiusM` 밖.
     */
    fun prune(userPosW: Vec3, headingW: Vec3, nowNs: Long): PruneCounts {
        val h = headingW.horizontal()
        val hn = h.norm()
        val dir = if (hn > 0f) h * (1f / hn) else null
        val maxUnseenNs = (cfg.maxUnseenS * 1e9).toLong()
        var passed = 0
        var unseen = 0
        var far = 0
        val it = voxels.entries.iterator()
        while (it.hasNext()) {
            val (key, v) = it.next()
            val d = (center(key) - userPosW).horizontal()
            when {
                dir != null && (d dot dir) < -cfg.passedMarginM -> passed++
                nowNs - v.lastSeenNs > maxUnseenNs -> unseen++
                d.norm() > cfg.radiusM -> far++
                else -> continue
            }
            it.remove()
        }
        return PruneCounts(passed, unseen, far)
    }

    /**
     * 군집화 대상 복셀: HITS는 표 수 ≥ `map.minHits`(DISTANCE면 가중합), score ≥ `map.minScore`. LOG_ODDS는 로그 오즈 ≥ `map.logOccupied`.
     */
    fun occupied(): List<VoxelView> = voxels.filter { (_, v) ->
        when {
            tsdf -> v.sdWeight >= cfg.minHits && abs(v.sd) <= surfaceBandM && v.hits > 0 // 측정점이 지난 적 없는 칸은 평균만으로 표면이 아님(윤곽 부풀림)
            logOdds -> v.logOdds >= cfg.logOccupied
            else -> {
                val historical = if (weighted) v.weightedHits else v.hits.toFloat()
                val evidence = if (cfg.freeEvidenceDecay > 0f) v.currentEvidence else historical
                evidence >= cfg.minHits && v.score >= cfg.minScore
            }
        }
    }.map { (key, v) -> view(key, v) }

    /** 모든 복셀. */
    fun views(): List<VoxelView> = voxels.map { (key, v) -> view(key, v) }

    private fun view(key: Long, v: Voxel): VoxelView {
        val historical = if (weighted) v.weightedHits else v.hits.toFloat()
        val evidence = when {
            tsdf -> v.sdWeight
            logOdds -> v.logOdds
            cfg.freeEvidenceDecay > 0f -> v.currentEvidence
            else -> historical
        }
        return VoxelView(ix(key), iy(key), iz(key), center(key), v.hits, v.score, v.lastSeenNs, v.logOdds, v.wallHits, v.instId, evidence)
    }

    /** 모든 score에 [factor]를 곱한다(추적 복귀 시 `state.recoverScoreScale`, §7.5). LOG_ODDS는 로그 오즈를 0(모름) 쪽으로 같은 비율만큼. */
    fun scaleScores(factor: Float) {
        if (tsdf) {
            for (v in voxels.values) v.sdWeight = max(0f, v.sdWeight * factor)
            return
        }
        if (logOdds) {
            for (v in voxels.values) v.logOdds = max(cfg.logMin, min(cfg.logMax, v.logOdds * factor))
            return
        }
        for (v in voxels.values) v.score = max(0f, min(1f, v.score * factor))
        voxels.values.removeAll { it.score <= 0f }
    }

    /** 맵을 비운다(자세 불연속, §7.5). */
    fun clear() {
        voxels.clear()
    }

    /** 월드 좌표 → 복셀 정수 좌표(음수도 내림). */
    fun index(c: Float): Int = floor(c / cfg.voxelSizeM).toInt()

    private fun center(key: Long) = Vec3(
        (ix(key) + 0.5f) * cfg.voxelSizeM,
        (iy(key) + 0.5f) * cfg.voxelSizeM,
        (iz(key) + 0.5f) * cfg.voxelSizeM,
    )

    private companion object {
        const val BITS = 21
        const val OFFSET = 1 shl (BITS - 1) // ±2^20 칸 (0.05 m면 ±52 km)
        const val MASK = (1L shl BITS) - 1

        fun key(ix: Int, iy: Int, iz: Int): Long {
            require(ix in -OFFSET until OFFSET && iy in -OFFSET until OFFSET && iz in -OFFSET until OFFSET) { "voxel index out of range" }
            return ((ix + OFFSET).toLong() shl (2 * BITS)) or ((iy + OFFSET).toLong() shl BITS) or (iz + OFFSET).toLong()
        }

        fun ix(key: Long) = ((key shr (2 * BITS)) and MASK).toInt() - OFFSET
        fun iy(key: Long) = ((key shr BITS) and MASK).toInt() - OFFSET
        fun iz(key: Long) = (key and MASK).toInt() - OFFSET
    }
}
