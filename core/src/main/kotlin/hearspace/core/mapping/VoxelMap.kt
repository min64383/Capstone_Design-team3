package hearspace.core.mapping

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.HitWeighting
import hearspace.core.types.Intrinsics
import hearspace.core.types.MapConfig
import hearspace.core.types.MapMode
import hearspace.core.types.TriFreeRule
import kotlin.math.abs
import kotlin.math.exp
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
    /** TRISTATE의 빈 증거 F(광선이 지난 장의 관측 가중 합). 다른 모드는 0. */
    val free: Float = 0f,
)

/**
 * 칸이 깊이 한 장에서 어떻게 보이는가(M20 대표점 진단). HIT = 이번 장에 점이 들어옴, FREE = 칸 뒤가 관측됨(빈 공간, 감쇠 대상),
 * OCCLUDED = 칸 자리가 관측됨(여유 안), NO_DEPTH = 투영 화소 깊이 무효, OUT_OF_VIEW = 카메라 뒤·영상 밖,
 * BEHIND = 칸보다 여유 넘게 앞에 면이 관측됨(앞면 뒤, M24: `map.decayBehind`면 감쇠 대상).
 */
enum class CellView { HIT, FREE, OCCLUDED, NO_DEPTH, OUT_OF_VIEW, BEHIND }

/** 삭제 사유별 개수(로그·테스트용). */
data class PruneCounts(val passed: Int, val unseen: Int, val outOfRadius: Int)

/**
 * 월드 좌표 희소 해시 복셀 점유 맵 (§7.3). 복셀 크기 `map.voxelSizeM`.
 * - 관측: 깊이 한 장에서 비바닥 점이 들어간 복셀은 hits += 1, score += hitGain(최대 1). 같은 장 안에서는 한 번만.
 * - 빈 공간 감쇠: **시야 안에서** 복셀 중심을 깊이 이미지에 투영했을 때 유효 깊이가 복셀보다 `freeMarginM` 이상 멀면
 *   score −= decayPerObservation. 시야 밖·깊이 무효 복셀은 감쇠하지 않는다(§2.2-6). 칸 자리(여유 안)가 관측된 복셀도 그대로다.
 *   칸보다 여유 넘게 앞에 면이 관측된 복셀(앞면 뒤)은 `decayBehind`면 같은 양만큼 감쇠한다(M24: 시선이 칸 앞에서 면에 닿았으므로
 *   시야 안 관측으로 본다. 끄면 그대로).
 * - 삭제: score ≤ 0, 또는 [prune]의 세 조건(지나감·오래 안 보임·반경 밖)뿐이다.
 * - `map.hitWeighting` DISTANCE(M13.5): 한 장의 표 = 거리 가중치([weight]). 점유는 표의 가중합 ≥ `minHits`, score 증가도 같은
 *   가중치를 곱한다. 먼 관측(평활 깊이의 바닥 들림·꼬리)은 문턱까지 더 많은 장이 필요하고 score가 낮아, 다가가며 얻는 가까운
 *   빈 공간 관측이 먼저 지운다. 감쇠는 가중하지 않는다.
 *
 * `map.mode` LOG_ODDS(M13.2, OctoMap 방식): 칸마다 로그 오즈. 점이 들면 + `logHit` × 가중치, 시야 안에서 칸을 지나 더 멀리
 * 보이면(여유 = max(복셀, `freeMarginRatio` × 칸 깊이)) + `logMiss` × 가중치, [`logMin`, `logMax`]로 묶고, `logOccupied` 이상이면
 * 점유, 하한에 닿으면 지운다. 가중치 = min(1, (`weightRefM` / 거리)²): 먼 관측일수록 약해, 다가가며 얻는 가까운 관측이 멀리서
 * 생긴 칸(끌림·잡음)을 덮어쓴다. 시야 밖·깊이 무효·칸 자리 관측 칸은 그대로고, 앞면 뒤 칸은 `decayBehind`면 빈 칸처럼 갱신한다(§2.2-6). 지나감·오래 안 보임·반경 밖 삭제는 같다.
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
 *
 * `map.mode` TRISTATE(M32, 점유·빈 곳·모름): [insertRay]로 점마다 카메라에서 측정점 앞 여유까지 광선이 지난 칸에 빈 증거 F, 측정점 칸에
 * 맞은 증거 H를 장마다 한 번씩 관측 가중(σ(Z)에서 유도, [LocalMap])만큼 더한다. 점유 = H ≥ `minPrecision`이고 H ≥ F(계수 모형의 최대우도),
 * 기록이 있고 점유가 아니면 빈 곳, 기록이 없으면 모름. 투영 감쇠([decayFree])는 하지 않는다: 광선이 닿지 않는 시야 밖·깊이 무효 칸은
 * 그대로다(§2.2-6). 삭제(지나감·오래 안 보임·반경 밖)된 칸은 모름이 된다.
 */
class VoxelMap(private val cfg: MapConfig) {

    private class Voxel(
        var hits: Int, var score: Float, var lastSeenNs: Long, var lastHitFrame: Long, var logOdds: Float, var weightedHits: Float,
        var wallHits: Int, var lastWallFrame: Long, var sd: Float = 0f, var sdWeight: Float = 0f,
        var instId: Int = 0, var instVotes: Int = 0, var lastInstFrame: Long = -1,
        var currentEvidence: Float = weightedHits,
        var free: Float = 0f, var lastFreeFrame: Long = -1,
        /** 마지막으로 증거를 받은 시각(M33 시간 감쇠). MIN = 아직 없음. */
        var lastUpdNs: Long = Long.MIN_VALUE,
    )

    private val logOdds = cfg.mode == MapMode.LOG_ODDS
    private val tsdf = cfg.mode == MapMode.TSDF
    private val tristate = cfg.mode == MapMode.TRISTATE
    /** 표면이 칸을 지나면 그 칸 중심까지의 거리는 반 대각선 이하(기하, 설정값이 아님). */
    private val surfaceBandM = cfg.voxelSizeM * sqrt(3f) / 2f
    /** TSDF 빈 곳 관측 한 번에 깎는 가중치: HITS의 감쇠 ÷ 증가(관측 한 장의 무게 1에 대한 비율). */
    private val carveWeight = cfg.decayPerObservation / cfg.hitGain

    /** TSDF 잘림 폭: 깊이 [depthM]에서 max(`tsdfTruncMinM`, `tsdfTruncPerM` × 깊이). */
    fun truncation(depthM: Float): Float = max(cfg.tsdfTruncMinM, cfg.tsdfTruncPerM * depthM)
    private val weighted = cfg.hitWeighting == HitWeighting.DISTANCE || tristate

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

    /**
     * TRISTATE: 카메라 [cameraW]에서 점 [pW]까지의 광선이 측정점 앞 [marginM]까지 지나는 칸에 빈 증거 F += [weight], [pW] 칸에 맞은 증거
     * H += [weight](둘 다 장마다 한 번). 측정점 칸은 빈 증거를 받지 않는다. 빈 증거만 받은 칸은 마지막 관측 시각을 새로 해 삭제 규칙이
     * 같게 적용되고, 맞은 적이 있는 칸의 마지막 관측 시각은 맞은 시각 그대로다(추적의 나이 판단).
     */
    fun insertRay(pW: Vec3, cameraW: Vec3, tNs: Long, weight: Float, marginM: Float) {
        val endKey = key(index(pW.x), index(pW.y), index(pW.z))
        traverse(cameraW, pW, marginM) { k ->
            if (k == endKey) return@traverse
            val v = voxels.getOrPut(k) { Voxel(0, 0f, tNs, -1, 0f, 0f, 0, -1) }
            if (v.lastFreeFrame != frame) {
                v.free += weight
                v.lastFreeFrame = frame
                if (v.hits == 0) v.lastSeenNs = max(v.lastSeenNs, tNs)
            }
        }
        hit(endKey, tNs, weight, false)
    }

    /**
     * TRISTATE 한 장(M33, `map.triSameFrame`). [pointsW]는 점(x, y, z 교차), [weights]·[margins]는 점마다 관측 가중과 빈 곳 여유.
     * 1. 점이 든 칸(맞은 칸)마다 그 장 가중의 최댓값을 모은다.
     * 2. 빈 증거: [projection]이 null이면 광선이 측정점 앞 여유까지 지난 칸에 그 장 광선 가중의 최댓값을 더한다(RAY). 있으면 광선은 칸을
     *    만드는 데만 쓰고, 기록이 있는 모든 칸을 그 칸이 투영된 화소의 깊이와 비교해 화소 깊이가 칸 깊이 + 그 화소의 여유보다 멀 때 그 화소의
     *    가중을 더한다(PROJECTION, 성질 ④). 어느 쪽이든 맞은 칸은 빈 증거를 받지 않는다(성질 ①, OctoMap computeUpdate).
     * 3. 맞은 칸에 맞은 증거를 더한다.
     * 증거를 받는 칸만 받기 직전에 H·F에 exp(−Δt / `triDecayS`)를 곱한다(성질 ⑤). 증거를 받지 않는 칸(시야 밖·깊이 무효·표면 자리)은 그대로다.
     */
    fun insertTriFrame(pointsW: FloatArray, weights: FloatArray, margins: FloatArray, cameraW: Vec3, tNs: Long, projection: TriProjection?) {
        val n = weights.size
        val hitW = HashMap<Long, Float>()
        for (i in 0 until n) hitW.merge(key(index(pointsW[3 * i]), index(pointsW[3 * i + 1]), index(pointsW[3 * i + 2])), weights[i], ::max)
        if (projection == null) {
            val freeW = HashMap<Long, Float>()
            for (i in 0 until n) {
                traverse(cameraW, Vec3(pointsW[3 * i], pointsW[3 * i + 1], pointsW[3 * i + 2]), margins[i]) { k ->
                    if (k !in hitW) freeW.merge(k, weights[i], ::max)
                }
            }
            for ((k, w) in freeW) addFree(voxels.getOrPut(k) { emptyVoxel(tNs) }, w, tNs)
        } else {
            for (i in 0 until n) {
                traverse(cameraW, Vec3(pointsW[3 * i], pointsW[3 * i + 1], pointsW[3 * i + 2]), margins[i]) { k ->
                    if (k !in hitW) voxels.getOrPut(k) { emptyVoxel(tNs) }
                }
            }
            val kk = projection.k
            for ((k, v) in voxels) {
                if (k in hitW) continue
                val pCv = projection.camFromWorld.transformPoint(center(k))
                if (pCv.z <= 0f) continue
                val u = (kk.fx * pCv.x / pCv.z + kk.cx).roundToInt()
                val w = (kk.fy * pCv.y / pCv.z + kk.cy).roundToInt()
                if (u < 0 || u >= kk.width || w < 0 || w >= kk.height) continue
                val mm = projection.depthMm[w * kk.width + u].toInt() and 0xFFFF
                if (mm == 0) continue
                val d = mm / 1000f
                if (d > pCv.z + projection.marginAt(u, w, d)) addFree(v, projection.weightAt(u, w, d), tNs)
            }
        }
        for ((k, w) in hitW) {
            val v = voxels.getOrPut(k) { emptyVoxel(tNs) }
            decay(v, tNs)
            hit(k, tNs, w, false)
            if (cfg.triClamp > 0f) v.weightedHits = min(v.weightedHits, cfg.triClamp)
        }
    }

    /** M33 투영 판정의 입력: 이 장의 깊이와 화소별 가중·여유(LocalMap이 깊이 오차 모형에서 준다). */
    class TriProjection(
        val depthMm: ShortArray,
        val k: Intrinsics,
        val camFromWorld: Mat4,
        val weightAt: (u: Int, w: Int, depthM: Float) -> Float,
        val marginAt: (u: Int, w: Int, depthM: Float) -> Float,
    )

    private fun emptyVoxel(tNs: Long) = Voxel(0, 0f, tNs, -1, 0f, 0f, 0, -1)

    private fun addFree(v: Voxel, w: Float, tNs: Long) {
        decay(v, tNs)
        v.free += w
        if (cfg.triClamp > 0f) v.free = min(v.free, cfg.triClamp)
        if (v.hits == 0) v.lastSeenNs = max(v.lastSeenNs, tNs)
    }

    /** 성질 ⑤: 증거를 받기 직전, 마지막으로 받은 뒤 지난 시간만큼 H·F를 줄인다(`triDecayS` 0이면 그대로). */
    private fun decay(v: Voxel, tNs: Long) {
        if (cfg.triDecayS > 0f && v.lastUpdNs != Long.MIN_VALUE && tNs > v.lastUpdNs) {
            val f = exp(-((tNs - v.lastUpdNs) / 1e9) / cfg.triDecayS).toFloat()
            v.weightedHits *= f
            v.free *= f
        }
        v.lastUpdNs = tNs
    }

    /** [from]에서 [to] 쪽으로 길이 |to − from| − [marginM]까지 광선이 지나는 칸을 차례로(3차원 칸 순회, Amanatides–Woo 1987). */
    private inline fun traverse(from: Vec3, to: Vec3, marginM: Float, visit: (Long) -> Unit) {
        val d = to - from
        val len = d.norm()
        val end = len - marginM
        if (end <= 0f) return
        val s = cfg.voxelSizeM
        val dx = d.x / len
        val dy = d.y / len
        val dz = d.z / len
        var ix = index(from.x)
        var iy = index(from.y)
        var iz = index(from.z)
        val sx = if (dx > 0f) 1 else -1
        val sy = if (dy > 0f) 1 else -1
        val sz = if (dz > 0f) 1 else -1
        var tx = firstCrossing(from.x, ix, dx, sx)
        var ty = firstCrossing(from.y, iy, dy, sy)
        var tz = firstCrossing(from.z, iz, dz, sz)
        val ddx = if (dx == 0f) Float.MAX_VALUE else s / abs(dx)
        val ddy = if (dy == 0f) Float.MAX_VALUE else s / abs(dy)
        val ddz = if (dz == 0f) Float.MAX_VALUE else s / abs(dz)
        var t = 0f
        while (t < end) {
            visit(key(ix, iy, iz))
            if (tx <= ty && tx <= tz) {
                t = tx; tx += ddx; ix += sx
            } else if (ty <= tz) {
                t = ty; ty += ddy; iy += sy
            } else {
                t = tz; tz += ddz; iz += sz
            }
        }
    }

    /** 광선 o + t·dc가 칸 [i]의 진행 쪽 경계를 처음 지나는 t(축 하나). */
    private fun firstCrossing(o: Float, i: Int, dc: Float, st: Int): Float =
        if (dc == 0f) Float.MAX_VALUE else ((i + (if (st > 0) 1 else 0)) * cfg.voxelSizeM - o) / dc

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
            val sd = (mm / 1000f - pCv.z) * Projection.cosIncidence(depthMm, k, u, w, cfg.tsdfMinCosIncidence)
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
        if (tristate) return 0 // 빈 증거는 광선이 쌓는다([insertRay])
        var decayed = 0
        val it = voxels.entries.iterator()
        while (it.hasNext()) {
            val (key, v) = it.next()
            if (v.lastHitFrame == frame) continue
            val pCv = camFromWorld.transformPoint(center(key))
            val seen = sight(pCv, depthMm, k)
            if (seen != CellView.FREE && !(seen == CellView.BEHIND && cfg.decayBehind)) continue // 시야 밖·관측 없음·칸 자리 관측
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
        val d = mm / 1000f
        return when {
            d < pCv.z - margin -> CellView.BEHIND
            d < pCv.z + margin -> CellView.OCCLUDED
            else -> CellView.FREE
        }
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
                cfg.prunePassed && dir != null && (d dot dir) < -cfg.passedMarginM -> passed++
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
    fun occupied(): List<VoxelView> = voxels.filter { (_, v) -> isOccupied(v) }.map { (key, v) -> view(key, v) }

    private fun isOccupied(v: Voxel): Boolean = when {
        tsdf -> v.sdWeight >= cfg.minHits && abs(v.sd) <= surfaceBandM && v.hits > 0 // 측정점이 지난 적 없는 칸은 평균만으로 표면이 아님(윤곽 부풀림)
        logOdds -> v.logOdds >= cfg.logOccupied
        tristate -> v.weightedHits >= cfg.minPrecision && v.weightedHits >= v.free
        else -> {
            val historical = if (weighted) v.weightedHits else v.hits.toFloat()
            val evidence = if (cfg.freeEvidenceDecay > 0f) v.currentEvidence else historical
            evidence >= cfg.minHits && v.score >= cfg.minScore
        }
    }

    /**
     * TRISTATE의 빈 곳 칸 중심. 다른 모드는 빈 곳을 저장하지 않으므로 빈 목록.
     * - `map.triFreeRule` NOT_OCCUPIED(M32): 기록이 있고 점유가 아닌 칸.
     * - EVIDENCE(M33): 점유 판정의 반대쪽, F > H이고 F ≥ `minPrecision`(증거가 충분할 때만 빈 곳). 맞은 증거가 쌓였지만 정밀도 문턱에 못 미친
     *   칸이나 증거가 없는 칸(광선이 골랐지만 표면 자리로 판정된 칸)은 모름이다. M33 구현 중 발견: 정답 깊이(합성 s = 0)에서 M32 "지워진 표면"
     *   0.37의 대부분이 이 규칙 차이였다(NOT_OCCUPIED면 같은 장 우선·투영 판정을 켜도 0.36·0.34).
     */
    fun freeCenters(): List<Vec3> = if (!tristate) emptyList() else voxels.filter { (_, v) -> isFree(v) }.map { (key, _) -> center(key) }

    private fun isFree(v: Voxel): Boolean =
        if (cfg.triFreeRule == TriFreeRule.EVIDENCE) v.free > v.weightedHits && v.free >= cfg.minPrecision else !isOccupied(v) && (v.free > 0f || v.weightedHits > 0f)

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
        return VoxelView(ix(key), iy(key), iz(key), center(key), v.hits, v.score, v.lastSeenNs, v.logOdds, v.wallHits, v.instId, evidence, v.free)
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
        if (tristate) { // 맞은·빈 증거를 같은 비율로: 비율 판정은 그대로, 정밀도 문턱 쪽으로 모름에 가까워진다
            for (v in voxels.values) { v.weightedHits *= factor; v.free *= factor }
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
