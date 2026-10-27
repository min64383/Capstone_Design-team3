package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.types.Config
import hearspace.core.types.DepthSource
import hearspace.core.types.FloorDecompose

// 요소 분해 단계(M43에서 SlowPath에서 옮김, 동작 같음). 바닥부터 요소 분해(M35)가 이 자리에 들어온다.

/**
 * TRISTATE(M32): 지도에는 바닥이 남으므로, 안내로 넘기기 전에 바닥 + max(τ, `map.sigmaMultiple` × σ(거리)) 이하의 칸(바닥 아래 포함)을
 * 뺀다. 거리는 지금 카메라 [cameraW]에서 칸 중심까지(깊이 Z보다 크거나 같아 띠가 넓은 쪽).
 * 바닥은 `floor.decompose` BAND면 전역 추정 [floorY](M32 임시 규칙), REGION_PLANE이면 영역별 바닥면([regionFloorPlanes], M35).
 */
fun withoutFloorBand(occupied: List<VoxelView>, floorY: Float, cameraW: Vec3, config: Config): List<VoxelView> {
    val raw = config.depth.source == DepthSource.RAW
    val tau = config.map.voxelSizeM * kotlin.math.sqrt(3f) / 2f
    fun band(p: Vec3) = kotlin.math.max(tau, config.map.sigmaMultiple * config.depth.sigmaM(raw, (p - cameraW).norm()))
    if (config.floor.decompose == FloorDecompose.BAND) return occupied.filter { it.centerW.y - floorY > band(it.centerW) }
    val planes = regionFloorPlanes(occupied, floorY, ::band, config)
    val r = config.floor.regionM
    return occupied.filter {
        val pl = planes[regionKey(it.centerW, r)]
        val floorHere = if (pl == null) floorY else pl[0] + pl[1] * it.centerW.x + pl[2] * it.centerW.z
        it.centerW.y - floorHere > band(it.centerW)
    }
}

private fun regionKey(p: Vec3, r: Float): Long =
    (kotlin.math.floor(p.x / r).toLong() shl 32) or (kotlin.math.floor(p.z / r).toLong() and 0xffffffffL)

/**
 * 영역별 바닥면(M35, Patchwork++의 영역별 평면): `floor.regionM` 정사각형 영역마다
 * ① 전역 바닥 ± `floor.searchBandM` 안 칸의 높이 최빈값(`floor.binM` 칸, 바닥 추정기와 같은 방식)을 씨앗으로,
 * ② 씨앗에서 띠 안의 칸에 y = a + b·x + c·z를 최소제곱으로 맞춘다.
 * 칸이 `floor.minPoints`보다 적거나 기울기가 `frontend.floorMaxLiftPerM`보다 크면(벽 밑동·물체 윗면) 그 영역은 없음(전역 바닥을 씀).
 * 값은 [a, b, c].
 */
internal fun regionFloorPlanes(occupied: List<VoxelView>, floorY: Float, band: (Vec3) -> Float, config: Config): Map<Long, FloatArray> {
    val f = config.floor
    val r = f.regionM
    val byRegion = occupied.filter { kotlin.math.abs(it.centerW.y - floorY) <= f.searchBandM }.groupBy { regionKey(it.centerW, r) }
    val out = HashMap<Long, FloatArray>()
    for ((key, cells) in byRegion) {
        if (cells.size < f.minPoints) continue
        val counts = HashMap<Int, Int>()
        for (c in cells) counts.merge(kotlin.math.floor(c.centerW.y / f.binM).toInt(), 1, Int::plus)
        val seedY = (counts.maxBy { it.value }.key + 0.5f) * f.binM
        val pts = cells.filter { kotlin.math.abs(it.centerW.y - seedY) <= band(it.centerW) }.map { it.centerW }
        if (pts.size < f.minPoints) continue
        val pl = fitPlane(pts) ?: continue
        if (kotlin.math.abs(pl[1]) > config.frontend.floorMaxLiftPerM || kotlin.math.abs(pl[2]) > config.frontend.floorMaxLiftPerM) continue
        out[key] = pl
    }
    return out
}

/** y = a + b·x + c·z 최소제곱(정규 방정식, 중심을 빼서 푼다). 퇴화하면 null. */
internal fun fitPlane(pts: List<Vec3>): FloatArray? {
    val n = pts.size.toDouble()
    val mx = pts.sumOf { it.x.toDouble() } / n
    val mz = pts.sumOf { it.z.toDouble() } / n
    val my = pts.sumOf { it.y.toDouble() } / n
    var sxx = 0.0; var sxz = 0.0; var szz = 0.0; var sxy = 0.0; var szy = 0.0
    for (p in pts) {
        val x = p.x - mx; val z = p.z - mz; val y = p.y - my
        sxx += x * x; sxz += x * z; szz += z * z; sxy += x * y; szy += z * y
    }
    val det = sxx * szz - sxz * sxz
    if (kotlin.math.abs(det) < 1e-9) return null
    val b = (sxy * szz - szy * sxz) / det
    val c = (szy * sxx - sxy * sxz) / det
    val a = my - b * mx - c * mz
    return floatArrayOf(a.toFloat(), b.toFloat(), c.toFloat())
}
