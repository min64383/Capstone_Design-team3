package hearspace.core.segment

import hearspace.core.mapping.VoxelView
import hearspace.core.types.SegmentConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 구조물(큰 수직 평면) 찾기 (IMPROVE_SPEC §6 C2, M13).
 *
 * 점유 복셀을 위에서 본 기둥(같은 x, z 칸)으로 묶고, 허프 변환으로 직선 ρ = x cosθ + z sinθ를 찾는다. 기둥마다 복셀 수만큼
 * 표를 줘서 키 큰 벽·문이 먼저 잡히고 천장·가짜 면처럼 납작한 기둥은 묻힌다. 직선마다 ±`planeInlierM` 띠 안의 표를 세어
 * 가장 많은 직선을 고른다. 띠 안 기둥을 직선 방향 칸(복셀 크기)으로 모아 칸마다 높이 폭을 재고, 높이 폭 ≥ `planeMinHeightM`인
 * 칸이 `planeMaxGapM` 이하 빈틈으로 `planeMinLengthM` 이상 이어진 구간의 **키 큰 칸만** 구조물로 본다. 같은 직선 위의 낮은 칸
 * (벽 앞 낮은 물체, 물체 뒤로 비스듬히 올라가는 가짜 면)은 물체로 남는다(M13 실측 S02: 왼쪽 벽 – 가짜 면 – 캐리어가 한 직선으로
 * 이어져 캐리어 일부가 구조물이 됐다). 구조물이 된 기둥은 다음 찾기에서 빼고, 구조물이 없던 직선은 그 근처
 * (θ ±2칸, ρ ±띠, 0°와 180°는 이어짐)를 다시 고르지 않는다. 직선을 `maxPlanes`개 살펴본다.
 * (탈락한 직선의 띠 안 기둥까지 빼면, 길고 낮은 의자와 기둥을 지나는 직선이 끝 벽 일부를 가져가 버린다.)
 *
 * 결과는 입력 순서에만 의존한다(같은 입력이면 같은 결과).
 */
object StructurePlanes {

    private class Column(val x: Float, val z: Float, var yMin: Float, var yMax: Float, var votes: Int)

    /** [voxels]마다 구조물인지. 크기 [voxelSizeM]의 복셀 중심이라고 본다. */
    fun find(voxels: List<VoxelView>, cfg: SegmentConfig, voxelSizeM: Float): BooleanArray {
        val columnOf = IntArray(voxels.size)
        val index = HashMap<Long, Int>()
        val columns = ArrayList<Column>()
        for ((i, v) in voxels.withIndex()) {
            val key = (v.ix.toLong() shl 32) or (v.iz.toLong() and 0xFFFFFFFFL)
            val c = index.getOrPut(key) {
                columns += Column(v.centerW.x, v.centerW.z, v.centerW.y, v.centerW.y, 0)
                columns.size - 1
            }
            columnOf[i] = c
            columns[c].apply { yMin = minOf(yMin, v.centerW.y); yMax = maxOf(yMax, v.centerW.y); votes++ }
        }
        val structure = BooleanArray(columns.size)
        if (columns.isEmpty()) return BooleanArray(voxels.size)

        // ρ는 기둥들의 평균 위치 기준(값을 작게, 배열 크기를 정하려고)
        val x0 = columns.sumOf { it.x.toDouble() }.toFloat() / columns.size
        val z0 = columns.sumOf { it.z.toDouble() }.toFloat() / columns.size
        val reach = columns.maxOf { maxOf(abs(it.x - x0), abs(it.z - z0)) } * 1.5f
        val nRho = 2 * ceil(reach / voxelSizeM).toInt() + 1
        val half = nRho / 2
        val nTheta = ceil(180f / cfg.planeAngleStepDeg).toInt()
        val cosT = FloatArray(nTheta) { cos(it * cfg.planeAngleStepDeg * PI / 180).toFloat() }
        val sinT = FloatArray(nTheta) { sin(it * cfg.planeAngleStepDeg * PI / 180).toFloat() }
        val band = (cfg.planeInlierM / voxelSizeM).roundToInt()
        val remaining = BooleanArray(columns.size) { true }
        val blocked = BooleanArray(nTheta * nRho)

        repeat(cfg.maxPlanes) {
            val acc = IntArray(nTheta * nRho)
            for ((ci, c) in columns.withIndex()) {
                if (!remaining[ci]) continue
                for (k in 0 until nTheta) {
                    val r = ((c.x - x0) * cosT[k] + (c.z - z0) * sinT[k]) / voxelSizeM
                    acc[k * nRho + (r.roundToInt() + half).coerceIn(0, nRho - 1)] += c.votes
                }
            }
            // 띠(±band 칸) 안 표가 가장 많은 직선
            var best = 0
            var bestK = -1
            var bestR = 0
            for (k in 0 until nTheta) {
                var sum = 0
                for (r in 0 until minOf(band, nRho)) sum += acc[k * nRho + r]
                for (r in 0 until nRho) {
                    if (r + band < nRho) sum += acc[k * nRho + r + band]
                    if (r - band - 1 >= 0) sum -= acc[k * nRho + r - band - 1]
                    if (sum > best && !blocked[k * nRho + r]) { best = sum; bestK = k; bestR = r }
                }
            }
            if (bestK < 0) return@repeat
            val rho = (bestR - half) * voxelSizeM
            val inliers = columns.indices.filter { ci ->
                remaining[ci] && abs((columns[ci].x - x0) * cosT[bestK] + (columns[ci].z - z0) * sinT[bestK] - rho) <= cfg.planeInlierM
            }
            // 띠 안 기둥을 직선 방향 칸으로 모아 칸마다 높이 폭을 잰다
            val binOf = inliers.associateWith { ci ->
                floor((-(columns[ci].x - x0) * sinT[bestK] + (columns[ci].z - z0) * cosT[bestK]) / voxelSizeM).toInt()
            }
            val lo = HashMap<Int, Float>()
            val hi = HashMap<Int, Float>()
            for (ci in inliers) {
                val bin = binOf.getValue(ci)
                lo[bin] = minOf(lo[bin] ?: Float.MAX_VALUE, columns[ci].yMin)
                hi[bin] = maxOf(hi[bin] ?: -Float.MAX_VALUE, columns[ci].yMax)
            }
            val tall = lo.keys.filter { hi.getValue(it) - lo.getValue(it) + voxelSizeM >= cfg.planeMinHeightM }.sorted()
            // 키 큰 칸이 빈틈 planeMaxGapM 이하로 이어진 구간 중 길이 ≥ planeMinLengthM인 구간의 키 큰 칸
            val gapBins = (cfg.planeMaxGapM / voxelSizeM).roundToInt()
            val structureBins = HashSet<Int>()
            var start = 0
            for (j in 1..tall.size) {
                if (j < tall.size && tall[j] - tall[j - 1] <= gapBins) continue
                if ((tall[j - 1] - tall[start] + 1) * voxelSizeM >= cfg.planeMinLengthM) structureBins += tall.subList(start, j)
                start = j
            }
            val found = inliers.filter { binOf.getValue(it) in structureBins }
            found.forEach { structure[it] = true; remaining[it] = false }
            if (found.isEmpty()) for (dk in -2..2) for (r in bestR - band..bestR + band) {
                // θ가 0°·180°를 넘으면 같은 직선이 ρ 부호만 바뀐다
                val k = bestK + dk
                val (kk, rr) = when {
                    k < 0 -> (k + nTheta) to (nRho - 1 - r)
                    k >= nTheta -> (k - nTheta) to (nRho - 1 - r)
                    else -> k to r
                }
                if (rr in 0 until nRho) blocked[kk * nRho + rr] = true
            }
        }
        return BooleanArray(voxels.size) { structure[columnOf[it]] }
    }
}
