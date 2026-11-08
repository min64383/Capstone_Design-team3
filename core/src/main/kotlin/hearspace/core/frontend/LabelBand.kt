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
     * [depthMm]에서 띠 안 화소를 0으로 만든 복사본과 뺀 화소 수. 깊이 화소의 월드 점을 라벨 영상 촬영 시각의 자세로 투영해 그 라벨 화소의 거리를 본다
     * (과거 값만, `vision.maxLabelAgeMs` 검사는 부르는 쪽이 한다). 영상 밖 화소는 그대로 둔다.
     */
    fun apply(depthMm: ShortArray, depth: DepthFrame, f: Field): Pair<ShortArray, Int> {
        val k = depth.K
        val out = depthMm.copyOf()
        var removed = 0
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val i = v * k.width + u
            val mm = depthMm[i].toInt() and 0xFFFF
            if (mm == 0) continue
            val p = depth.worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), mm / 1000f, k))
            val j = f.image.indexAt(p)
            if (j < 0) continue
            val d = f.distPx[j]
            if (d.isInfinite()) continue
            val inside = f.obstacle.obstacle(f.image.labels[j].toInt() and 0xFF)
            if (d <= (if (inside) f.limitPx[j] else f.bandPx)) {
                out[i] = 0
                removed++
            }
        }
        return out to removed
    }
}
