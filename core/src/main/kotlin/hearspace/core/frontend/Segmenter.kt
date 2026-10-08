package hearspace.core.frontend

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.Intrinsics
import kotlin.math.abs
import kotlin.math.min

/**
 * 깊이 영상 영역 분할(IMPROVE_SPEC §6 C3, §6.1.1 M13.1 ②). 지도에 쌓은 뒤 거리로 묶으면 막·벽 밑동 칸이 다리가 되어 물체와 벽이
 * 한 덩어리가 된다(선행 조사 §5.1). 깊이 영상에서는 앞 물체와 뒤 면 사이의 깊이 불연속이 바로 보이므로, 지도에 넣기 전에 영상에서
 * 나눈다(Voxblox++ 2019의 기하 분할과 같은 생각).
 *
 * 역투영과 같은 격자(`depth.subsample` 간격)에서 4방향 이웃의 깊이 차가 `edgeStepRatio` × 가까운 쪽 깊이 이하이거나, 같은 방향
 * 바로 앞 칸과의 깊이 역수 차가 `fitTolRatio` / 깊이 안으로 같으면(같은 평면이 비스듬히 이어짐: 평면의 깊이 역수는 픽셀에 대해 직선) 같은 영역으로 키운다(경계 판정의
 * "단차"·"같은 직선" 기준과 같은 뜻). 기울기 조건이 없으면 먼 쪽 비스듬한 벽이 조각났다(SC-14). 평활된 막은 경사가 완만해 이웃 차가
 * 작으므로 경계 판정으로 막을 지운 깊이에서 써야 한다. 무효 깊이와 [segment]의 `exclude`가 참인 점(바닥·벽 평면 등)은 어느 영역에도
 * 넣지 않는다. `minPixels`보다 작은 영역은 버린다(번호 0). 결정적이다.
 */
object Segmenter {

    /** 격자([gridW] × [gridH], 간격 [subsample]) 위 영역 번호(0 = 없음, 1..[count]). */
    class Result(val gridW: Int, val gridH: Int, val subsample: Int, val labels: IntArray, val count: Int) {
        /** 전체 해상도 픽셀 (u, v)가 속한 격자 칸의 번호. */
        fun labelAt(u: Int, v: Int): Int {
            val gu = u / subsample
            val gv = v / subsample
            return if (gu < gridW && gv < gridH) labels[gv * gridW + gu] else 0
        }

        /** [Projection.backprojectToWorld]와 같은 순서(유효 깊이 픽셀만)의 점마다 영역 번호. */
        fun pointLabels(depthMm: ShortArray, k: Intrinsics): IntArray {
            val out = IntArray(depthMm.size)
            var n = 0
            var v = 0
            while (v < k.height) {
                var u = 0
                while (u < k.width) {
                    if (depthMm[v * k.width + u].toInt() != 0) out[n++] = labels[(v / subsample) * gridW + u / subsample]
                    u += subsample
                }
                v += subsample
            }
            return out.copyOf(n)
        }
    }

    private val DIRS = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    /**
     * [depthMm](K [k], 자세 [worldFromCam])을 나눈다. [exclude]는 역투영 순서의 점 번호와 월드 점을 받아 영역에서 뺄지 정한다.
     */
    fun segment(
        depthMm: ShortArray, k: Intrinsics, worldFromCam: Mat4, subsample: Int, edgeStepRatio: Float, fitTolRatio: Float, minPixels: Int,
        exclude: (Int, Vec3) -> Boolean,
    ): Result {
        val gw = (k.width + subsample - 1) / subsample
        val gh = (k.height + subsample - 1) / subsample
        val d = FloatArray(gw * gh) // 0 = 영역에 넣지 않음
        var n = 0
        for (gv in 0 until gh) for (gu in 0 until gw) {
            val u = gu * subsample
            val v = gv * subsample
            val mm = depthMm[v * k.width + u].toInt() and 0xFFFF
            if (mm == 0) continue
            val p = worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), mm / 1000f, k))
            if (!exclude(n++, p)) d[gv * gw + gu] = mm / 1000f
        }
        val labels = IntArray(gw * gh)
        val queue = IntArray(gw * gh)
        val sizes = ArrayList<Int>()
        for (seed in d.indices) {
            if (d[seed] == 0f || labels[seed] != 0) continue
            val id = sizes.size + 1
            var head = 0
            var tail = 0
            queue[tail++] = seed
            labels[seed] = id
            while (head < tail) {
                val i = queue[head++]
                val gu = i % gw
                val gv = i / gw
                for ((du, dv) in DIRS) {
                    val nu = gu + du
                    val nv = gv + dv
                    if (nu < 0 || nu >= gw || nv < 0 || nv >= gh) continue
                    val j = nv * gw + nu
                    if (d[j] == 0f || labels[j] != 0) continue
                    val step = d[j] - d[i]
                    if (abs(step) > edgeStepRatio * min(d[i], d[j])) {
                        // 같은 기울기로 이어지는지: 평면의 깊이 역수는 픽셀을 따라 직선이므로(비스듬해도), 같은 방향 바로 앞 칸(i − 방향)과의
                        // 역수 차가 거의 같으면 같은 면. 깊이 자체로 보면 먼 쪽 비스듬한 면은 쌍곡선이라 기울기가 커져 조각났다
                        val pu = gu - du
                        val pv = gv - dv
                        if (pu < 0 || pu >= gw || pv < 0 || pv >= gh) continue
                        val q = pv * gw + pu
                        if (d[q] == 0f || labels[q] != id) continue
                        val second = (1f / d[j] - 1f / d[i]) - (1f / d[i] - 1f / d[q])
                        if (abs(second) > fitTolRatio / d[i]) continue
                    }
                    labels[j] = id
                    queue[tail++] = j
                }
            }
            sizes += tail
        }
        // 작은 영역은 버리고 번호를 1부터 다시 매긴다
        val remap = IntArray(sizes.size + 1)
        var count = 0
        for ((i, s) in sizes.withIndex()) if (s >= minPixels) remap[i + 1] = ++count
        for (i in labels.indices) labels[i] = remap[labels[i]]
        return Result(gw, gh, subsample, labels, count)
    }
}
