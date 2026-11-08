package hearspace.core.frontend

import hearspace.core.geometry.Projection
import hearspace.core.types.DepthFrame
import hearspace.core.types.LabelImage
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 라벨 경계 띠(M54 설계 3.2절, `frontend.labelBandPx`). 장애물 역할 라벨 영역(라벨 없음·하늘·나무·구조물·물체·차량·사람·물)의 경계에서
 * 깊이 영상 화소 [bandPx] 안의 깊이 화소를 무효로 한다(원시 유무와 상관없이). 경계 화소 = 4이웃과 라벨이 다르고 둘 중 하나가 장애물 역할인
 * 라벨 영상 화소(프레임 라벨끼리의 경계는 보지 않는다). 거리는 라벨 영상 화소 중심 사이 유클리드 거리를 깊이 fx / 라벨 fx 비로 바꾼 값(분석
 * `label_band.signed_k`와 같은 정의). 장애물 역할 영역 안쪽 화소는 그 영역(같은 라벨, 4연결) 안쪽 반지름의 절반과 [bandPx] 중 작은 거리까지만
 * 뺀다(가는 물체도 폭의 절반은 남김).
 */
object LabelBand {
    /** 장애물 역할 라벨 값(M54 기본: 라벨 없음·하늘·물 포함). `frontend.bandLabels`로 바꿀 수 있다(M55 O는 하늘·물 뺌). */
    val OBSTACLE = booleanArrayOf(true, true, false, true, false, false, false, true, true, true, true, true)

    /** 표에 없는 값(12 이상)은 장애물로 본다(처음 보는 종류가 소리 없이 지나가지 않게). */
    private fun BooleanArray.obstacle(v: Int) = v !in indices || this[v]

    /** 라벨 영상 화소마다 경계까지 거리(깊이 화소, 창 밖은 +∞)와 그 화소의 빼는 한도(깊이 화소). [obstacle]은 장애물 역할 표. */
    class Field(val image: LabelImage, val distPx: FloatArray, val limitPx: FloatArray, val bandPx: Float, val obstacle: BooleanArray = OBSTACLE)

    /** [image]의 거리 마당. [depthFx]는 깊이 영상 fx(화소 단위 변환). 영역 반지름은 2 × [bandPx]까지만 재면 충분하다(넘으면 한도 = [bandPx]). */
    fun field(image: LabelImage, depthFx: Float, bandPx: Int, obs: BooleanArray = OBSTACLE): Field {
        fun obstacle(v: Int) = obs.obstacle(v)
        val w = image.K.width
        val h = image.K.height
        val lab = IntArray(w * h) { image.labels[it].toInt() and 0xFF }
        val ratio = depthFx / image.K.fx // 라벨 화소 → 깊이 화소
        val rLab = ceil((2 * bandPx + 1) / ratio).toInt() + 1
        val dist = FloatArray(w * h) { Float.POSITIVE_INFINITY }
        // 경계 화소마다 반지름 rLab 안에 거리를 찍는다(창 안 정확한 유클리드 거리)
        val offs = ArrayList<IntArray>()
        for (dy in -rLab..rLab) for (dx in -rLab..rLab) if (dx * dx + dy * dy <= rLab * rLab) offs += intArrayOf(dx, dy)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = lab[i]
            val edge = (x + 1 < w && lab[i + 1] != a && (obstacle(a) || obstacle(lab[i + 1]))) ||
                (x > 0 && lab[i - 1] != a && (obstacle(a) || obstacle(lab[i - 1]))) ||
                (y + 1 < h && lab[i + w] != a && (obstacle(a) || obstacle(lab[i + w]))) ||
                (y > 0 && lab[i - w] != a && (obstacle(a) || obstacle(lab[i - w])))
            if (!edge) continue
            for (o in offs) {
                val xx = x + o[0]
                val yy = y + o[1]
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val d = sqrt((o[0] * o[0] + o[1] * o[1]).toFloat()) * ratio
                val j = yy * w + xx
                if (d < dist[j]) dist[j] = d
            }
        }
        // 장애물 역할 영역(같은 라벨, 4연결)마다 안쪽 반지름 = 영역 안 최대 거리(창에서 잘림)
        val limit = FloatArray(w * h) { bandPx.toFloat() }
        val comp = IntArray(w * h) { -1 }
        val queue = IntArray(w * h)
        var nComp = 0
        for (s in 0 until w * h) {
            if (comp[s] >= 0 || !obstacle(lab[s])) continue
            var head = 0
            var tail = 0
            queue[tail++] = s
            comp[s] = nComp
            var r = 0f
            while (head < tail) {
                val i = queue[head++]
                r = maxOf(r, if (dist[i].isInfinite()) Float.MAX_VALUE else dist[i])
                val x = i % w
                val y = i / w
                for (j in intArrayOf(if (x + 1 < w) i + 1 else -1, if (x > 0) i - 1 else -1, if (y + 1 < h) i + w else -1, if (y > 0) i - w else -1)) {
                    if (j >= 0 && comp[j] < 0 && lab[j] == lab[s]) {
                        comp[j] = nComp
                        queue[tail++] = j
                    }
                }
            }
            val lim = min(bandPx.toFloat(), r / 2f)
            for (k in 0 until tail) limit[queue[k]] = lim
            nComp++
        }
        return Field(image, dist, limit, bandPx.toFloat(), obs)
    }

    /**
     * 깊이 경계 근처(M56 설계 3.6절, `frontend.bandDepthEdgeSigmas`): 깊이 경계 화소 = 4이웃과의 깊이 차가 [sigmas]·σ(Z)(두 화소 중 작은 쪽,
     * σ(Z) = [sigma0M] + [perM2]·Z², 하한 1 mm)를 넘는 화소. 그 화소에서 맨해튼 거리 [radiusPx] 안이면 true. 막(깊이 경계의 앞·뒤 사이 값 화소)은
     * 깊이가 튀는 경계에만 생기므로(Sabov·Krüger 2008, M56 0-5: 깊이가 이어지는 라벨 경계의 어긋남은 바탕 수준) 띠를 이곳에만 둔다.
     */
    fun depthEdgeNear(depthMm: ShortArray, width: Int, height: Int, sigmas: Float, sigma0M: Float, perM2: Float, radiusPx: Int): BooleanArray {
        fun sig(mm: Int): Float {
            val z = mm / 1000f
            return sigmas * maxOf(sigma0M + perM2 * z * z, 0.001f)
        }
        var near = BooleanArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val a = depthMm[i].toInt() and 0xFFFF
            if (a == 0) continue
            for (j in intArrayOf(if (x + 1 < width) i + 1 else -1, if (y + 1 < height) i + width else -1)) {
                if (j < 0) continue
                val b = depthMm[j].toInt() and 0xFFFF
                if (b == 0) continue
                if (kotlin.math.abs(a - b) / 1000f > minOf(sig(a), sig(b))) { near[i] = true; near[j] = true }
            }
        }
        repeat(radiusPx) { // 4이웃 팽창
            val n2 = near.copyOf()
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                if (!near[i]) continue
                if (x > 0) n2[i - 1] = true
                if (x + 1 < width) n2[i + 1] = true
                if (y > 0) n2[i - width] = true
                if (y + 1 < height) n2[i + width] = true
            }
            near = n2
        }
        return near
    }

    /**
     * 거리 비례 띠 폭(M56, `frontend.bandWidthM`): 화소마다 띠 폭(깊이 화소) = [widthM]를 그 자리 깊이에서 화소로 바꾼 값([fx]·[widthM]/Z)을
     * [minPx]~[maxPx]로 자른 값. Z = 그 화소 둘레 [maxPx]화소 창 안의 가장 가까운 깊이(경계 옆 장애물 쪽 거리). 근거: M56 0-5 측정에서 막 반폭이
     * 3.5 m 안에서는 실제 폭 3~5 cm, 밖에서는 2~3화소로 일정했다. 깊이 없는 창은 [maxPx].
     */
    fun widthByDepth(depthMm: ShortArray, width: Int, height: Int, fx: Float, widthM: Float, minPx: Int, maxPx: Int): FloatArray {
        val near = nearestDepthMm(depthMm, width, height, maxPx)
        return FloatArray(width * height) { i ->
            val m = near[i]
            if (m == Int.MAX_VALUE) maxPx.toFloat() else (fx * widthM / (m / 1000f)).coerceIn(minPx.toFloat(), maxPx.toFloat())
        }
    }

    /**
     * 계단 띠 폭(M56, `frontend.bandNearM`·`bandFarPx`): 그 화소 둘레 [maxPx]화소 창 안의 가장 가까운 깊이가 [nearM]보다 가까우면 [maxPx], 아니면
     * [farPx]. 근거: M56 0-5 막 전체 폭(라벨 경계 몫이 그 구간 바탕의 세션 부트스트랩 95% 구간에 드는 거리) — 0.5~1.5 m는 8화소 넘음(최대로 묶음),
     * 1.5~5 m는 2~3화소. 깊이 없는 창은 [maxPx].
     */
    fun stepByDepth(depthMm: ShortArray, width: Int, height: Int, nearM: Float, farPx: Int, maxPx: Int): FloatArray {
        val near = nearestDepthMm(depthMm, width, height, maxPx)
        val nearMm = nearM * 1000f
        return FloatArray(width * height) { i -> if (near[i] == Int.MAX_VALUE || near[i] < nearMm) maxPx.toFloat() else farPx.toFloat() }
    }

    /** 화소마다 둘레 (2·[radiusPx] + 1)² 창 안의 가장 가까운 깊이(mm, 0 = 없음은 건너뜀, 없으면 Int.MAX_VALUE). 가로 → 세로 분리 최소 필터. */
    private fun nearestDepthMm(depthMm: ShortArray, width: Int, height: Int, radiusPx: Int): IntArray {
        val inf = Int.MAX_VALUE
        val row = IntArray(width * height) { inf }
        for (y in 0 until height) for (x in 0 until width) {
            var m = inf
            for (dx in -radiusPx..radiusPx) {
                val xx = x + dx
                if (xx < 0 || xx >= width) continue
                val v = depthMm[y * width + xx].toInt() and 0xFFFF
                if (v in 1 until m) m = v
            }
            row[y * width + x] = m
        }
        val out = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var m = inf
            for (dy in -radiusPx..radiusPx) {
                val yy = y + dy
                if (yy < 0 || yy >= height) continue
                val v = row[yy * width + x]
                if (v < m) m = v
            }
            out[y * width + x] = m
        }
        return out
    }

    /**
     * [depthMm]에서 띠 안 화소를 0으로 만든 복사본과 뺀 화소 수. 깊이 화소의 월드 점을 라벨 영상 촬영 시각의 자세로 투영해 그 라벨 화소의 거리를 본다
     * (과거 값만, `vision.maxLabelAgeMs` 검사는 부르는 쪽이 한다). 영상 밖 화소는 그대로 둔다. [edgeNear]가 있으면 그 화소가 true일 때만 뺀다(M56 깊이 경계 띠).
     * [pxLimit]가 있으면 화소마다 띠 폭을 그 값 이하로 줄인다(M56 거리 비례 띠, [widthByDepth]).
     */
    fun apply(depthMm: ShortArray, depth: DepthFrame, f: Field, edgeNear: BooleanArray? = null, pxLimit: FloatArray? = null): Pair<ShortArray, Int> {
        val k = depth.K
        val out = depthMm.copyOf()
        var removed = 0
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val i = v * k.width + u
            val mm = depthMm[i].toInt() and 0xFFFF
            if (mm == 0) continue
            if (edgeNear != null && !edgeNear[i]) continue
            val p = depth.worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), mm / 1000f, k))
            val j = f.image.indexAt(p)
            if (j < 0) continue
            val d = f.distPx[j]
            if (d.isInfinite()) continue
            val inside = f.obstacle.obstacle(f.image.labels[j].toInt() and 0xFF)
            val lim = if (inside) f.limitPx[j] else f.bandPx
            if (d <= (if (pxLimit != null) minOf(lim, pxLimit[i]) else lim)) {
                out[i] = 0
                removed++
            }
        }
        return out to removed
    }
}
