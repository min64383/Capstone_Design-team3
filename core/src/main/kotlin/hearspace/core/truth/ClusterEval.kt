package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.CellView
import hearspace.core.types.CorridorConfig

/**
 * 군집 칸 단위 평가(M23, `cluster_eval.csv`). 평가 전용이다(정렬이 세션 전체의 바닥 중앙값을 쓴다). 좌표는 정답 좌표.
 *
 * 느린 경로 단계마다 정답 물체(`kind: object`)별로, 그 단계의 정답 통로(머리 x ± 폭/2, 높이 `corridor.heightM`)로 자른 정답 상자에
 * 군집이 어떻게 붙었는지 본다.
 * - 붙은 군집: 칸(중심)의 과반이 정답 상자 ± [TOL_M] 안. 과반이 밖이면 다른 물체로 본다.
 * - 주 군집: 붙은 군집 중 칸이 가장 많은 것. 그 상자(칸 중심 ± 반 칸)의 면과 정답 면의 차를 쓴다.
 * - 순도: 주 군집 칸 중 정답 상자 ± [TOL_M] 안 비율. 밖 칸은 이 장에서 어떻게 보였는지([CellView])로 나눈다
 *   (남은 늘어남이 매 프레임 깊이 몫(HIT)인지 쌓인 칸 몫(그 밖)인지 가른다).
 */
object ClusterEval {
    /** 면 허용(m): 칸 0.05 m의 반 칸 + 정렬 각 불확실성 약 0.9°가 3 m에서 내는 약 0.05 m(M23 설계 표 4). */
    const val TOL_M = 0.10f

    /** 군집 하나: 칸 중심(정답 좌표)과 이 장의 칸 상태([CellView] 순서, −1 = 판정 못 함). */
    class Cells(val centers: List<Vec3>, val states: ByteArray)

    /**
     * 정답 물체 하나의 한 단계. 면 차(m)는 군집 − 정답(앞: 음수 = 사람 쪽으로 나옴, 뒤: 양수 = 뒤로 늘어남, 왼쪽·오른쪽: 양수 = 밖으로 넘침,
     * 위: 양수 = 위로 넘침). 주 군집이 없으면 NaN. [outside]는 주 군집 밖 칸 수([CellView] 순서).
     */
    class Row(
        val name: String,
        val alongM: Float,
        val nAttached: Int,
        val nVoxels: Int,
        val frontM: Float,
        val backM: Float,
        val leftM: Float,
        val rightM: Float,
        val topM: Float,
        val purity: Float,
        val outside: IntArray,
        /** 주 군집의 번호(그 단계 군집 목록 순서 = `cluster_debug.csv`의 clusterIndex), 없으면 −1(M22: 주 군집 → 출력 track). */
        val mainIndex: Int = -1,
    ) {
        /** 다섯 면이 모두 ±[TOL_M] 안. */
        val fits get() = nAttached > 0 && listOf(frontM, backM, leftM, rightM, topM).all { kotlin.math.abs(it) <= TOL_M }
    }

    /**
     * 머리 [headT](정답 좌표)에서 정답 통로 안에 있는 물체마다 한 줄. [clusters]는 그 단계의 군집 전부. 통로와 앞·뒤 면은 +z로 걷는 것으로
     * 본다(한 방향 보행 장면용. 왕복의 돌아오는 길은 맞지 않는다, A1 MA3).
     */
    fun evaluate(headT: Vec3, clusters: List<Cells>, truth: GroundTruth, corridor: CorridorConfig, voxelM: Float): List<Row> =
        truth.obstacles.mapNotNull { o ->
            if (o.kind != TruthKind.OBJECT) return@mapNotNull null
            val near = o.nearestInCorridor(headT.x, headT.z, corridor) ?: return@mapNotNull null
            val lo = Vec3(maxOf(o.minM.x, headT.x - corridor.widthM / 2), o.minM.y, o.minM.z)
            val hi = Vec3(minOf(o.maxM.x, headT.x + corridor.widthM / 2), minOf(o.maxM.y, corridor.heightM), o.maxM.z)
            fun inside(p: Vec3) = p.x in lo.x - TOL_M..hi.x + TOL_M && p.y in lo.y - TOL_M..hi.y + TOL_M && p.z in lo.z - TOL_M..hi.z + TOL_M
            val attached = clusters.filter { c -> c.centers.count(::inside) * 2 > c.centers.size }
            val main = attached.maxByOrNull { it.centers.size }
                ?: return@mapNotNull Row(o.name, near.alongM, 0, 0, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, IntArray(CellView.entries.size))
            val half = voxelM / 2
            val outside = IntArray(CellView.entries.size)
            var nIn = 0
            for ((i, p) in main.centers.withIndex()) {
                if (inside(p)) nIn++ else main.states[i].toInt().let { s -> if (s >= 0) outside[s]++ }
            }
            Row(
                o.name, near.alongM, attached.size, main.centers.size,
                frontM = main.centers.minOf { it.z } - half - lo.z,
                backM = main.centers.maxOf { it.z } + half - hi.z,
                leftM = lo.x - (main.centers.minOf { it.x } - half),
                rightM = main.centers.maxOf { it.x } + half - hi.x,
                topM = main.centers.maxOf { it.y } + half - hi.y,
                purity = nIn.toFloat() / main.centers.size,
                outside = outside,
                mainIndex = clusters.indexOf(main),
            )
        }

    val CSV_HEADER = "tCaptureNs,name,alongM,nAttached,nVoxels,frontM,backM,leftM,rightM,topM,fits,purity," +
        CellView.entries.joinToString(",") { "out_${it.name}" } + ",mainCluster"

    fun csvLine(tNs: Long, r: Row): String {
        fun f(x: Float) = if (x.isNaN()) "" else "%.3f".format(java.util.Locale.ROOT, x)
        return listOf(tNs, r.name, f(r.alongM), r.nAttached, r.nVoxels, f(r.frontM), f(r.backM), f(r.leftM), f(r.rightM), f(r.topM), r.fits, f(r.purity))
            .joinToString(",") + "," + r.outside.joinToString(",") + "," + r.mainIndex
    }
}
