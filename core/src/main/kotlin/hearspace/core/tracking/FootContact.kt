package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import hearspace.core.types.LabelImage
import hearspace.core.types.VisionConfig
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 발 접점 측정 하나(M53 설계 3.2절). [pW]는 바닥면 위 월드 점(y = 바닥), [distM]은 카메라에서의 수평 거리, [sigmaM]은 측정 잡음 σ(d),
 * [label]은 덩어리의 라벨 화소 값, [nPx]는 덩어리 화소 수.
 */
data class FootMeas(val tNs: Long, val pW: Vec3, val distM: Float, val sigmaM: Float, val label: Int, val nPx: Int)

/**
 * 깊이 밖 사람 거리(M53, IMPROVE_SPEC §13 v0.4.32). 의미 영상의 움직이는 라벨(사람·차량) 화소를 같은 라벨끼리 4-이웃 덩어리로 묶고, 덩어리의 땅 쪽 끝
 * 화소 광선을 바닥면과 만나게 한다(클릭 정답 `truth/ClickTruth.planePoint`와 같은 기하). `prototypes/python/foot_contact/foot.py`의 이식이다.
 */
object FootContact {

    /**
     * [img]의 발 접점 측정들. [pixels]는 움직이는 라벨의 화소 값, [floorY]는 바닥면 월드 높이(카메라 높이 중앙값으로 만든 값, 3.4절).
     * 규칙(설계 3.2절): 영상 가장자리 [VisionConfig.footEdgePx] 안에 닿은 덩어리는 버림(발·몸이 잘림), [VisionConfig.footMinPx]보다 작은 덩어리는
     * 라벨 점 잡음으로 버림, 땅 쪽 끝 화소의 땅 쪽 바깥 가장자리 광선(옆 방향은 끝 두 줄 화소 평균), [VisionConfig.footMaxDistM]보다 멀면 버림.
     */
    fun measure(img: LabelImage, pixels: Set<Int>, floorY: Float, cfg: VisionConfig, floorSigmaM: Float = cfg.footFloorSigmaM): List<FootMeas> {
        val k = img.K
        val w = k.width
        val h = k.height
        val cam = img.worldFromCam.translation()
        val camH = cam.y - floorY
        if (camH <= 0f) return emptyList()
        // 영상에서 땅 쪽: 월드 아래(−Y)를 카메라로 돌린 방향의 큰 성분(폰 세로면 +u). 영상 가운데에서 정한다
        val down = img.worldFromCam.rigidInverse().transformDir(Vec3(0f, -1f, 0f))
        val alongU = abs(down.x * k.fx) >= abs(down.y * k.fy)
        val sign = if (alongU) (if (down.x >= 0f) 1 else -1) else (if (down.y >= 0f) 1 else -1)
        val f = if (alongU) k.fx else k.fy
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val out = ArrayList<FootMeas>()
        for (start in 0 until w * h) {
            if (seen[start]) continue
            val lab = img.labels[start].toInt() and 0xFF
            if (lab !in pixels) continue
            // 같은 라벨 4-이웃 덩어리(너비 우선)
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var cut = false
            while (head < tail) {
                val i = queue[head++]
                val u = i % w
                val v = i / w
                if (u <= cfg.footEdgePx || u >= w - 1 - cfg.footEdgePx || v <= cfg.footEdgePx || v >= h - 1 - cfg.footEdgePx) cut = true
                for (j in intArrayOf(if (u > 0) i - 1 else -1, if (u < w - 1) i + 1 else -1, if (v > 0) i - w else -1, if (v < h - 1) i + w else -1)) {
                    if (j < 0 || seen[j] || (img.labels[j].toInt() and 0xFF) != lab) continue
                    seen[j] = true
                    queue[tail++] = j
                }
            }
            if (cut || tail < cfg.footMinPx) continue
            // 땅 쪽 끝: 땅 쪽 축 좌표가 가장 땅 쪽인 화소들, 옆 방향은 끝 두 줄(끝과 1 px 안) 평균
            var end = if (sign > 0) Int.MIN_VALUE else Int.MAX_VALUE
            for (q in 0 until tail) {
                val a = if (alongU) queue[q] % w else queue[q] / w
                end = if (sign > 0) maxOf(end, a) else minOf(end, a)
            }
            var sum = 0.0
            var n = 0
            for (q in 0 until tail) {
                val i = queue[q]
                val a = if (alongU) i % w else i / w
                if (abs(a - end) <= 1) { sum += if (alongU) i / w else i % w; n++ }
            }
            val edge = end + if (sign > 0) 1f else 0f // 땅 쪽 바깥 가장자리
            val side = (sum / n).toFloat() + 0.5f
            val (u, v) = if (alongU) edge to side else side to edge
            val dir = img.worldFromCam.transformDir(Vec3((u - k.cx) / k.fx, (v - k.cy) / k.fy, 1f))
            if (dir.y >= -1e-6f) continue
            val t = (floorY - cam.y) / dir.y
            val p = cam + dir * t
            val dist = (p - cam).horizontal().norm()
            if (dist > cfg.footMaxDistM) continue
            out += FootMeas(img.tCaptureNs, p, dist, sigmaAt(dist, camH, f, cfg, floorSigmaM), lab, tail)
        }
        return out
    }

    /** 측정 잡음(설계 표 6 규칙 1): σ(d)² = σ0² + (σpx · (h² + d²)/(h·f))² + (d/h · σh)², σh = [floorSigmaM]. */
    fun sigmaAt(distM: Float, camHeightM: Float, fPx: Float, cfg: VisionConfig, floorSigmaM: Float = cfg.footFloorSigmaM): Float {
        val px = cfg.footSigmaPx * (camHeightM * camHeightM + distM * distM) / (camHeightM * fPx)
        val fl = distM / camHeightM * floorSigmaM
        return sqrt(cfg.footSigma0M * cfg.footSigma0M + px * px + fl * fl)
    }
}
