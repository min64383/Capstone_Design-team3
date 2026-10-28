package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.VoxelView
import hearspace.core.tracking.Cluster
import hearspace.core.types.Config
import hearspace.core.types.CorridorConfig

// 통로 판정 단계(M43에서 SlowPath에서 옮김, 동작 같음): 통로 안 칸에서 나란한 가장자리 구조물을 뺀다.
// 걸을 수 있는 영역 판정(M44)이 이 자리에 들어온다.

/**
 * 나란한 가장자리 구조물(벽·담장·난간·길가 차량 등)의 복셀을 뺀다. 가장자리 구역(|좌우| ≥ `corridor.edgeInnerM`)의
 * 복셀만 따로 군집해, 진행 방향으로 `corridor.edgeMinLengthM` 이상 이어진 군집을 구조물로 본다.
 * 좁은 복도에서 사용자가 조금만 치우쳐도 옆 벽 한 줄이 통로 안에 들어와 계속 경고하던 문제(M5 실측) 대응.
 * 벽에 붙은 물체는 가장자리 구역 안 부분만 벽과 함께 빠지고 안쪽으로 나온 부분은 남는다.
 * 실내 벽에 한정하지 않는 규칙이다(실외 확장, §15).
 *
 * `corridor.edgeOutsideM` > 0이면(M22 W) 통로 밖 그 폭까지의 칸([occupied])과 함께 한 번 더 묶어, 길게 이어진 묶음의 칸 중 통로 경계에서
 * 한 칸 안(|좌우| > 반폭 − 칸 크기)의 통로 안 칸을 더 뺀다. 통로가 벽면을 가로지르면 경계 안 몇 칸만 짧은 조각으로 남아 위 규칙에 걸리지 않았다.
 * 한 칸으로 좁힌 것은 벽에 붙은 물체·벽과 이어진 문의 가장자리 구역까지 빼면 확인용 세션에서 경고가 늦어졌기 때문이다(M22 설계 2.6절).
 */
fun withoutEdgeStructures(occupied: List<VoxelView>, voxels: List<VoxelView>, corridor: Corridor, config: Config): List<VoxelView> {
    val edge = voxels.filter { kotlin.math.abs(corridor.lateralM(it.centerW)) >= config.corridor.edgeInnerM }
    if (edge.isEmpty() && !config.corridor.cutEnd) return voxels
    val removed = HashSet<VoxelView>()
    for (idx in Cluster.dbscanXZ(edge.map { it.centerW }, config.cluster.epsM, config.cluster.minSamples)) {
        val along = idx.map { corridor.alongM(edge[it].centerW) }
        if (along.max() - along.min() >= config.corridor.edgeMinLengthM) idx.forEach { removed += edge[it] }
    }
    if (config.corridor.edgeOutsideM > 0f) {
        val half = config.corridor.widthM / 2
        val band = occupied.filter {
            corridor.contains(it.centerW, half + config.corridor.edgeOutsideM) &&
                kotlin.math.abs(corridor.lateralM(it.centerW)) >= config.corridor.edgeInnerM
        }
        for (idx in Cluster.dbscanXZ(band.map { it.centerW }, config.cluster.epsM, config.cluster.minSamples)) {
            val along = idx.map { corridor.alongM(band[it].centerW) }
            if (along.max() - along.min() < config.corridor.edgeMinLengthM) continue
            for (i in idx) if (kotlin.math.abs(corridor.lateralM(band[i].centerW)) > half - config.map.voxelSizeM) removed += band[i]
        }
    }
    if (config.corridor.cutEnd) removed += endFragments(occupied, corridor, config)
    return if (removed.isEmpty()) voxels else voxels.filter { it !in removed }
}

/**
 * 통로 끝에 잘린 구조물 조각(M25 E, `corridor.cutEnd`). 진행 방향 `policy.silentMaxM` ~ 끝 + `cluster.epsM`, 통로 높이, 좌우는 W의 바깥 띠까지인
 * 점유 칸([occupied], 끝 너머 포함)을 묶어 끝 너머 칸이 있는 묶음을 돌려준다. 끝 벽이 통로 끝에 잘려 몇 칸만 군집이 되어 깜빡이던 문제.
 * 빼는 칸은 모두 무음 거리라 안내는 바뀌지 않고, 다가가 무음 거리 안에 들어온 칸은 대상이 아니다(M25 설계 3.1절).
 */
private fun endFragments(occupied: List<VoxelView>, corridor: Corridor, config: Config): List<VoxelView> {
    val c = config.corridor
    val reach = c.widthM / 2 + c.edgeOutsideM
    val end = c.lengthM
    val band = occupied.filter {
        val along = corridor.alongM(it.centerW)
        val h = it.centerW.y - corridor.floorY
        along >= config.policy.silentMaxM && along <= end + config.cluster.epsM && h >= 0f && h <= c.heightM &&
            kotlin.math.abs(corridor.lateralM(it.centerW)) <= reach
    }
    return Cluster.dbscanXZ(band.map { it.centerW }, config.cluster.epsM, config.cluster.minSamples)
        .filter { idx -> idx.any { corridor.alongM(band[it].centerW) > end } }
        .flatMap { idx -> idx.map { band[it] } }
}

/**
 * 벽 방향 맞춤(M42 C, `corridor.wallAlign`): [headingW]를 ±α 안에서 돌려 보며, 사용자 둘레(수평 `lengthM` 안, 바닥 위 0~`heightM`)의 점유 칸이
 * 그 방향과 나란한 한 줄(좌우 칸 하나 폭)에 진행 방향으로 가장 많이(서로 다른 칸 수) 들어가는 방향을 고른다. 그 줄이 `edgeMinLengthM` 이상 이어져야
 * 하고(가장자리 구조물과 같은 "길게 이어짐"), 없으면 [headingW] 그대로. 같은 길이면 덜 돌린 쪽.
 * α = asin((widthM/2 − edgeInnerM)/edgeMinLengthM): 이 각보다 덜 기운 벽은 가장자리 구역(폭 widthM/2 − edgeInnerM) 안에 edgeMinLengthM 넘게
 * 머물 수 있어 가장자리 구조물 규칙이 이미 나란한 벽으로 본다. 각 간격 atan(칸/lengthM)은 통로 끝에서 좌우 한 칸이 바뀌는 각이다.
 * 정답 진행 방향(H)이 복도 축으로 맞추는 것을 지도에서 보이는 벽으로 대신한다(C1 상한 D + H).
 */
fun wallAlignedHeading(headingW: Vec3, cellsW: List<Vec3>, userPosW: Vec3, floorY: Float, cfg: CorridorConfig, voxelM: Float): Vec3 {
    val h = headingW.horizontal().normalized()
    val minBins = kotlin.math.round(cfg.edgeMinLengthM / voxelM).toInt()
    val pts = cellsW.filter {
        val dy = it.y - floorY
        dy >= 0f && dy <= cfg.heightM && (it - userPosW).horizontal().norm() <= cfg.lengthM
    }
    if (pts.size < minBins) return h
    val maxRad = kotlin.math.asin(((cfg.widthM / 2 - cfg.edgeInnerM) / cfg.edgeMinLengthM).coerceIn(0f, 1f))
    val step = kotlin.math.atan(voxelM / cfg.lengthM)
    val n = (maxRad / step).toInt()
    var best = h
    var bestBins = minBins - 1
    for (i in 0..2 * n) {
        val k = if (i == 0) 0 else if (i % 2 == 1) (i + 1) / 2 else -(i / 2)
        val a = k * step
        val c = kotlin.math.cos(a)
        val s = kotlin.math.sin(a)
        val d = Vec3(h.x * c - h.z * s, 0f, h.x * s + h.z * c)
        val r = d cross Vec3.UP
        val seen = HashSet<Long>()
        val perLine = HashMap<Int, Int>()
        var longest = 0
        for (p in pts) {
            val q = (p - userPosW).horizontal()
            val lat = kotlin.math.floor((q dot r) / voxelM).toInt()
            val along = kotlin.math.floor((q dot d) / voxelM).toInt()
            if (seen.add((lat.toLong() shl 32) or (along.toLong() and 0xffffffffL))) {
                val m = (perLine[lat] ?: 0) + 1
                perLine[lat] = m
                if (m > longest) longest = m
            }
        }
        if (longest > bestBins) {
            bestBins = longest
            best = d
        }
    }
    return best
}

/**
 * 걸을 수 있는 영역 판정(M44, `corridor.judge` WALKABLE): 진행 방향 거리를 칸 크기 단면으로 나누고, 단면마다 보행선(좌우 0)을 포함하는
 * 빈 좌우 구간을 점유 칸(통로 높이 안, 칸 폭 ± 반 칸)으로 잰다. 보행선이 칸에 막혔거나 구간 폭이 몸 폭(`corridor.widthM`)보다 좁으면
 * 그 단면의 몸 폭 안(|좌우| ≤ widthM/2) 칸을 장애물로 넘긴다. 벽만 있는 복도는 구간 폭이 복도 폭이라 넘기지 않는다.
 * 구간 폭은 진행 방향과 수직으로 재므로 진행 방향이 θ 틀려도 복도 폭/cosθ로 거의 바뀌지 않는다(고정 상자는 벽이 거리 × tanθ만큼 들어온다).
 * 찾는 좌우 범위는 ±widthM: 폭 widthM 이상인 구간이 보행선을 포함하면 그 안에 있다.
 * Yang 2016(걸을 수 있는 영역)·Corridor-Walker의 걸을 수 있는 격자를 진행 방향 단면으로 근사한 것이다(M44 설계 3.1~3.2절).
 * 옆으로 비켜 지나갈 수 있는 물체(구간 폭 ≥ widthM)는 넘기지 않는다: 경고의 뜻이 "진행선 위 물체"에서 "길이 막힘"으로 바뀐다(결정은 M36).
 */
fun walkableInPath(occupied: List<VoxelView>, corridor: Corridor, config: Config): List<VoxelView> {
    val c = config.corridor
    val half = config.map.voxelSizeM / 2
    val reach = c.widthM
    val cand = occupied.filter { corridor.contains(it.centerW, reach + half) }
    val out = ArrayList<VoxelView>()
    for ((_, cells) in cand.groupBy { kotlin.math.floor(corridor.alongM(it.centerW) / config.map.voxelSizeM).toInt() }) {
        var left = -reach
        var right = reach
        var lineBlocked = false
        for (v in cells) {
            val l = corridor.lateralM(v.centerW)
            when {
                l - half <= 0f && l + half >= 0f -> lineBlocked = true
                l + half < 0f -> left = kotlin.math.max(left, l + half)
                else -> right = kotlin.math.min(right, l - half)
            }
        }
        if (lineBlocked || right - left < c.widthM) out += cells.filter { kotlin.math.abs(corridor.lateralM(it.centerW)) <= c.widthM / 2 }
    }
    return out
}


/**
 * 나란한 경계 판정(M44 재설계, `corridor.judge` BOUNDARY): [kept](상자 + 가장자리 규칙 뒤 통로 안 칸)에서 옆 경계 칸을 더 뺀다.
 * - 경계 찾기: 방향 φ를 ±α(= asin((widthM/2 − edgeInnerM)/edgeMinLengthM), 지금 가장자리 규칙이 견디는 각도와 같은 값)에서 칸 크기/lengthM 각
 *   간격으로 훑어, φ 방향 좌우 칸 줄마다 진행 방향 칸이 `cluster.epsM` 이하 틈으로 이어진 가장 긴 사슬이 `edgeMinLengthM` 이상이면 경계 줄로 본다.
 *   칸은 줄 ±1칸(벽 두께·잡음)이고 사슬 범위 안이면 그 줄에 속하고, 칸마다 가장 긴 줄을 고른다.
 * - 줄의 좌우 위치는 φ 방향 수직 거리라 진행 방향 오차가 α 안이면 오차와 무관하다. 그 거리가 widthM/2 − edgeInnerM 이상인 줄만 옆 경계다
 *   (사용자 바로 앞의 긴 물체는 물체로 남는다).
 * - 옆 경계 칸은 뺀다. 다만 반대쪽 옆 경계 칸까지의 수평 거리가 widthM보다 짧은 칸(길이 몸 폭보다 좁아지는 곳)은 남긴다.
 * 짧은 물체는 상자 규칙 그대로라 경고의 뜻은 바뀌지 않는다. 찾는 좌우 범위는 ±widthM(경계 사이 폭을 재려면 몸 폭 바깥 경계도 봐야 함).
 * 벽을 앞길의 장애물이 아니라 길의 경계로 다루는 것은 Corridor-Walker·VFH+와 같은 생각이다(M44 재설계 2~3절).
 */
fun withoutParallelBoundaries(occupied: List<VoxelView>, kept: List<VoxelView>, corridor: Corridor, config: Config): List<VoxelView> {
    if (kept.isEmpty()) return kept
    val c = config.corridor
    val v = config.map.voxelSizeM
    val pts = occupied.filter { corridor.contains(it.centerW, c.widthM + v / 2) }
    val side = c.widthM / 2 - c.edgeInnerM
    val maxRad = kotlin.math.asin((side / c.edgeMinLengthM).coerceIn(0f, 1f))
    val step = kotlin.math.atan(v / c.lengthM)
    val n = (maxRad / step).toInt()
    val maxGap = kotlin.math.floor(config.cluster.epsM / v).toInt() + 1
    val h = corridor.headingW
    val bestLen = FloatArray(pts.size)
    val bestOff = FloatArray(pts.size)
    val lat = IntArray(pts.size)
    val alo = IntArray(pts.size)
    for (k in -n..n) {
        val cs = kotlin.math.cos(k * step)
        val sn = kotlin.math.sin(k * step)
        val d = Vec3(h.x * cs - h.z * sn, 0f, h.x * sn + h.z * cs)
        val r = d cross Vec3.UP
        val lines = HashMap<Int, java.util.TreeSet<Int>>()
        for ((i, p) in pts.withIndex()) {
            val q = (p.centerW - corridor.originW).horizontal()
            lat[i] = kotlin.math.floor((q dot r) / v).toInt()
            alo[i] = kotlin.math.floor((q dot d) / v).toInt()
            lines.getOrPut(lat[i]) { java.util.TreeSet() } += alo[i]
        }
        // 줄마다 가장 긴 사슬(진행 방향 칸 범위)
        val chain = HashMap<Int, IntArray>()
        for ((line, bins) in lines) {
            var start = bins.first()
            var prev = start
            var best = intArrayOf(start, start)
            for (b in bins) {
                if (b - prev > maxGap) start = b
                prev = b
                if (b - start > best[1] - best[0]) best = intArrayOf(start, b)
            }
            chain[line] = best
        }
        for (i in pts.indices) for (dl in -1..1) {
            val ch = chain[lat[i] + dl] ?: continue
            if (alo[i] < ch[0] || alo[i] > ch[1]) continue
            val len = (ch[1] - ch[0] + 1) * v
            if (len > bestLen[i]) {
                bestLen[i] = len
                bestOff[i] = (lat[i] + dl + 0.5f) * v
            }
        }
    }
    val boundary = pts.indices.filter { bestLen[it] >= c.edgeMinLengthM && kotlin.math.abs(bestOff[it]) >= side }
    if (boundary.isEmpty()) return kept
    val left = boundary.filter { bestOff[it] < 0f }.map { pts[it].centerW }
    val right = boundary.filter { bestOff[it] > 0f }.map { pts[it].centerW }
    val w2 = c.widthM * c.widthM
    val removed = HashSet<VoxelView>()
    for (i in boundary) {
        val p = pts[i].centerW
        // ponytail: 양쪽 경계 칸 전부 짝 비교(O(n²)), 칸이 수천 개를 넘어 느려지면 격자 해시로
        val narrow = (if (bestOff[i] < 0f) right else left).any { val dx = it.x - p.x; val dz = it.z - p.z; dx * dx + dz * dz < w2 }
        if (!narrow) removed += pts[i]
    }
    return if (removed.isEmpty()) kept else kept.filter { it !in removed }
}
