package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.types.GeometryConfig
import hearspace.core.types.GeometryFinding
import hearspace.core.types.GroundProfileBin
import hearspace.core.types.IndoorGeometryResult
import hearspace.core.types.IndoorGeometryType
import hearspace.core.types.ClusterConfig
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/**
 * RGB 의미 인식 없이 Depth/복셀의 기하만으로 실내 보행 형태를 분류한다.
 *
 * 안전 원칙:
 * - 바닥 점이 안 보였다는 이유만으로 FLOOR_LOSS라고 하지 않는다. 앞쪽 정상 지면 + 연속 결손 + 뒤쪽 유효 지면(또는 확실한 하강)이
 *   함께 있을 때만 후보로 낸다. 그 외는 UNKNOWN.
 * - 이 결과는 현재 평가/로그용이며 Guidance에 직접 연결하지 않는다.
 */
object IndoorGeometryAnalyzer {

    fun analyze(
        pointsW: FloatArray,
        occupied: List<VoxelView>,
        userPosW: Vec3,
        headingW: Vec3,
        floorY: Float,
        corridorWidthM: Float,
        maxAlongM: Float,
        floorToleranceM: Float,
        cluster: ClusterConfig,
        cfg: GeometryConfig,
    ): IndoorGeometryResult {
        val h0 = headingW.horizontal()
        if (h0.norm() <= 1e-6f) return IndoorGeometryResult(IndoorGeometryType.UNKNOWN, emptyList(), emptyList())
        val h = h0.normalized()
        val right = Vec3(-h.z, 0f, h.x)
        fun along(p: Vec3) = (p - userPosW) dot h
        fun lateral(p: Vec3) = (p - userPosW) dot right

        val limitM = min(maxAlongM, cfg.maxDistanceM)
        if (!floorY.isFinite() || limitM <= 0f) return IndoorGeometryResult(IndoorGeometryType.UNKNOWN, emptyList(), emptyList())
        val profile = buildGroundProfile(pointsW, userPosW, h, right, floorY, corridorWidthM, limitM, floorToleranceM, cfg)
        val findings = ArrayList<GeometryFinding>()
        findings += groundFindings(profile, cfg)
        findings += occupancyFindings(occupied, ::along, ::lateral, floorY, corridorWidthM, limitM, cluster, cfg)

        val primary = findings.maxWithOrNull(compareBy<GeometryFinding>({ priority(it.type) }, { it.confidence }))?.type
            ?: IndoorGeometryType.UNKNOWN
        return IndoorGeometryResult(primary, findings.sortedBy { it.distanceM }, profile)
    }

    private fun buildGroundProfile(
        pointsW: FloatArray,
        user: Vec3,
        h: Vec3,
        right: Vec3,
        floorY: Float,
        widthM: Float,
        maxAlongM: Float,
        floorTolM: Float,
        cfg: GeometryConfig,
    ): List<GroundProfileBin> {
        val nBins = max(1, kotlin.math.ceil(maxAlongM / cfg.profileBinM).toInt())
        val ys = Array(nBins) { ArrayList<Float>() }
        val half = widthM / 2f
        for (i in 0 until pointsW.size / 3) {
            val p = Vec3(pointsW[3 * i], pointsW[3 * i + 1], pointsW[3 * i + 2])
            val d = p - user
            val a = d dot h
            if (a < 0f || a >= maxAlongM) continue
            val l = d dot right
            if (abs(l) > half) continue
            // 지면 형상 후보: 현재 바닥 기준 ±0.6 m. 높은 물체 표면은 프로파일에서 배제.
            if (p.y < floorY - cfg.groundHeightRangeM || p.y > floorY + cfg.groundHeightRangeM) continue
            ys[min(nBins - 1, (a / cfg.profileBinM).toInt())] += p.y
        }
        return List(nBins) { b ->
            val values = ys[b]
            val height = if (values.size < cfg.minBinPoints) null else dominantHeight(values, max(cfg.histogramMinBinM, floorTolM), floorY)
            GroundProfileBin(b * cfg.profileBinM, min(maxAlongM, (b + 1) * cfg.profileBinM), height, values.size)
        }
    }

    /** 장애물 윗면보다 바닥/계단 tread처럼 점이 많이 모인 높이를 고르기 위한 높이 히스토그램 최빈값. */
    private fun dominantHeight(values: List<Float>, binM: Float, floorY: Float): Float {
        val hist = HashMap<Int, MutableList<Float>>()
        for (y in values) hist.getOrPut(kotlin.math.floor(y / binM).toInt()) { ArrayList() } += y
        val best = hist.maxWithOrNull(compareBy<Map.Entry<Int, MutableList<Float>>>({ it.value.size }, { -abs(it.value.average().toFloat() - floorY) }, { -it.key }))!!.value
        return best.sorted()[best.size / 2]
    }

    private fun groundFindings(profile: List<GroundProfileBin>, cfg: GeometryConfig): List<GeometryFinding> {
        val valid = profile.mapIndexedNotNull { i, b -> b.heightM?.let { Triple(i, (b.alongStartM + b.alongEndM) / 2f, it) } }
        if (valid.size < 2) return listOf(GeometryFinding(IndoorGeometryType.UNKNOWN, 0f, cfg.unknownConfidence, "insufficient ground support"))
        val out = ArrayList<GeometryFinding>()

        val steps = ArrayList<Pair<Int, Float>>()
        for (k in 1 until valid.size) {
            val (i0, _, y0) = valid[k - 1]
            val (i1, _, y1) = valid[k]
            if (i1 - i0 > cfg.maxStepGapBins) continue // 큰 결손을 단차로 연결하지 않는다
            val dy = y1 - y0
            if (abs(dy) >= cfg.stepHeightM) steps += i1 to dy
        }
        val ups = steps.count { it.second > 0f }
        val downs = steps.count { it.second < 0f }
        // Preserve both directions, including a raised platform with an up and a down edge.
        for ((rising, count) in listOf(true to ups, false to downs)) {
            val events = steps.filter { (it.second > 0f) == rising }
            if (count >= cfg.stairMinSteps) {
                out += GeometryFinding(if (rising) IndoorGeometryType.STAIRS_UP else IndoorGeometryType.STAIRS_DOWN,
                    profile[events.first().first].alongStartM, confidence(count, cfg.stairMinSteps, cfg), "$count steps")
            } else for ((bin, dy) in events) {
                out += GeometryFinding(if (rising) IndoorGeometryType.STEP_UP else IndoorGeometryType.STEP_DOWN,
                    profile[bin].alongStartM, cfg.stepConfidence, "height jump ${dy}m")
            }
        }

        // 큰 단차가 없을 때만 전체 추세를 경사로 본다.
        if (steps.isEmpty() && valid.size >= cfg.minSlopeBins) {
            val xs = valid.map { it.second }
            val ys = valid.map { it.third }
            val xm = xs.average().toFloat(); val ym = ys.average().toFloat()
            var num = 0f; var den = 0f
            for (i in xs.indices) { val dx = xs[i] - xm; num += dx * (ys[i] - ym); den += dx * dx }
            if (den > 1e-6f) {
                val slope = num / den
                val deg = (atan(slope.toDouble()) * 180.0 / PI).toFloat()
                val residual = ys.indices.map { abs(ys[it] - (ym + slope * (xs[it] - xm))) }.average().toFloat()
                if (abs(deg) >= cfg.slopeMinDeg && residual < cfg.stepHeightM / 2f) {
                    out += GeometryFinding(if (deg > 0) IndoorGeometryType.SLOPE_UP else IndoorGeometryType.SLOPE_DOWN, xs.first(), (cfg.slopeConfidenceBase + min(cfg.slopeConfidenceIncrementMax, abs(deg) / cfg.slopeConfidenceScaleDeg)).coerceAtMost(cfg.maxConfidence), "slope=${"%.1f".format(deg)}deg")
                }
            }
        }

        // FLOOR_LOSS: 앞에 유효 지면, 최소 길이 이상의 연속 결손, 그 뒤 다시 지면이 보여야 한다.
        val minGapBins = max(1, kotlin.math.ceil(cfg.floorLossMinGapM / cfg.profileBinM).toInt())
        var i = 1
        while (i < profile.lastIndex) {
            if (profile[i].heightM != null) { i++; continue }
            val start = i
            while (i < profile.size && profile[i].heightM == null) i++
            val len = i - start
            val before = profile.getOrNull(start - 1)?.heightM
            val after = profile.getOrNull(i)?.heightM
            if (len >= minGapBins && before != null && after != null) {
                out += GeometryFinding(IndoorGeometryType.FLOOR_LOSS, profile[start].alongStartM, min(cfg.floorLossConfidenceMax, cfg.floorLossConfidenceBase + len * cfg.floorLossConfidencePerBin), "ground missing for ${len * cfg.profileBinM}m")
                break
            }
        }
        // Missing evidence anywhere in the tested interval is not evidence of a flat floor.
        if (profile.any { it.heightM == null } && out.none { it.type == IndoorGeometryType.FLOOR_LOSS }) {
            out += GeometryFinding(IndoorGeometryType.UNKNOWN, profile.first { it.heightM == null }.alongStartM,
                cfg.unknownConfidence, "incomplete ground support")
        }
        if (out.isEmpty()) out += GeometryFinding(IndoorGeometryType.FLAT, valid.first().second, cfg.flatConfidence, "stable ground profile")
        return out
    }

    private fun occupancyFindings(
        voxels: List<VoxelView>,
        along: (Vec3) -> Float,
        lateral: (Vec3) -> Float,
        floorY: Float,
        widthM: Float,
        maxAlongM: Float,
        cluster: ClusterConfig,
        cfg: GeometryConfig,
    ): List<GeometryFinding> {
        val roi = voxels.filter { along(it.centerW) in 0f..maxAlongM && abs(lateral(it.centerW)) <= widthM / 2f }
        if (roi.isEmpty()) return emptyList()
        val out = ArrayList<GeometryFinding>()
        val obstacle = roi.filter { it.centerW.y - floorY > cfg.obstacleMinHeightM && it.centerW.y - floorY < cluster.headMinM }
        if (obstacle.size >= cluster.minSamples) {
            out += GeometryFinding(IndoorGeometryType.OCCUPIED, obstacle.minOf { along(it.centerW) }, min(cfg.maxConfidence, cfg.occupiedConfidenceBase + obstacle.size / cfg.occupiedConfidenceCount), "${obstacle.size} occupied voxels")
        }
        val head = roi.filter { it.centerW.y - floorY >= cluster.headMinM }
        if (head.size >= cluster.minSamples) {
            out += GeometryFinding(IndoorGeometryType.OVERHANG, head.minOf { along(it.centerW) }, min(cfg.maxConfidence, cfg.structureConfidenceBase + head.size / cfg.occupiedConfidenceCount), "${head.size} head-height voxels")
        }
        val wall = roi.filter { it.hits > 0 && it.wallHits >= cluster.wallFraction * it.hits }
        if (wall.size >= cluster.minSamples) {
            out += GeometryFinding(IndoorGeometryType.WALL, wall.minOf { along(it.centerW) }, min(cfg.maxConfidence, cfg.structureConfidenceBase + wall.size / cfg.wallConfidenceCount), "${wall.size} wall-labelled voxels")
        }

        // 진행방향 bin마다 좌/우 점유 경계를 보고 유효 통로 폭을 추정한다. 양쪽 증거가 있을 때만 좁은 통로로 판정.
        val boundary = roi.filter { it.centerW.y - floorY > cfg.obstacleMinHeightM && it.centerW.y - floorY < cluster.headMinM }
        val groups = hearspace.core.tracking.Cluster.dbscanXZ(boundary.map { it.centerW }, cluster.epsM, 1)
        val groupOf = IntArray(boundary.size)
        groups.forEachIndexed { id, indices -> indices.forEach { groupOf[it] = id } }
        var openingActive = false
        val bins = max(1, kotlin.math.ceil(maxAlongM / cfg.profileBinM).toInt())
        for (b in 0 until bins) {
            val lo = b * cfg.profileBinM; val hi = min(maxAlongM, (b + 1) * cfg.profileBinM)
            val slice = boundary.indices.filter { along(boundary[it].centerW) >= lo && along(boundary[it].centerW) < hi }
            val leftIndex = slice.filter { lateral(boundary[it].centerW) < 0f }.maxByOrNull { lateral(boundary[it].centerW) }
            val rightIndex = slice.filter { lateral(boundary[it].centerW) > 0f }.minByOrNull { lateral(boundary[it].centerW) }
            var isOpening = false
            // The two sides must belong to different components. A central object is OCCUPIED.
            if (leftIndex != null && rightIndex != null && groupOf[leftIndex] != groupOf[rightIndex]) {
                val left = lateral(boundary[leftIndex].centerW)
                val right = lateral(boundary[rightIndex].centerW)
                val gap = right - left
                if (gap < cfg.narrowPassageM) {
                    out += GeometryFinding(IndoorGeometryType.NARROW_PASSAGE, lo, (1f - gap / cfg.narrowPassageM).coerceIn(min(cfg.narrowConfidenceMin, cfg.maxConfidence), cfg.maxConfidence), "clear width=${"%.2f".format(gap)}m")
                    break
                }
                if (wall.isNotEmpty() && gap >= cfg.narrowPassageM && gap <= widthM * cfg.openingWidthFactor) {
                    isOpening = true
                    if (!openingActive) out += GeometryFinding(IndoorGeometryType.OPENING, lo, cfg.openingConfidence, "wall opening=${"%.2f".format(gap)}m")
                }
            }
            openingActive = isOpening
        }
        return out
    }

    private fun confidence(count: Int, required: Int, cfg: GeometryConfig) = min(cfg.maxConfidence, cfg.stairConfidenceBase + cfg.stairConfidenceIncrement * (count - required + 1))

    private fun priority(t: IndoorGeometryType): Int = when (t) {
        IndoorGeometryType.FLOOR_LOSS, IndoorGeometryType.STAIRS_DOWN, IndoorGeometryType.STEP_DOWN -> 100
        IndoorGeometryType.OVERHANG -> 90
        IndoorGeometryType.NARROW_PASSAGE -> 85
        IndoorGeometryType.STAIRS_UP, IndoorGeometryType.STEP_UP -> 80
        IndoorGeometryType.OCCUPIED -> 70
        IndoorGeometryType.SLOPE_UP, IndoorGeometryType.SLOPE_DOWN -> 60
        IndoorGeometryType.WALL -> 50
        IndoorGeometryType.OPENING -> 30
        IndoorGeometryType.FLAT -> 10
        IndoorGeometryType.UNKNOWN -> 0
    }
}
