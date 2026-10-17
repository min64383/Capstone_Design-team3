package hearspace.core.truth

import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.DepthConfig
import hearspace.core.types.DepthFrame
import hearspace.core.types.PoseFrame
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 정답 없는 복셀 지도 검사(M33 설계 3.3절). 평가 전용이다: 스냅샷 시각 뒤의 자세·깊이 영상을 쓴다(파이프라인은 과거 값만 쓴다).
 *
 * - 발자국 헛 칸: 스냅샷 뒤 사용자의 몸이 지나간 공간(머리 둘레 [BODY_RADIUS_M] 기둥 + 카메라 둘레 [CAMERA_RADIUS_M] 관, 정답 바닥
 *   [FLOOR_BAND_M] 위부터 카메라 + [ABOVE_CAMERA_M]까지) 안의 점유 칸 수 ÷ 그 공간에서 지도 반경 안의 칸 수.
 * - 나중 장 모순(남겨둔 프레임 예측): 스냅샷의 점유 칸을 얼려 두고, 그 뒤 [LATER_WINDOW_S] 안의 깊이 영상(변형과 무관한 시험 깊이)마다
 *   칸 중심을 투영한 화소의 깊이 d와 칸 깊이 z를 비교한다. d > z + 여유면 모순(그 화소는 칸 너머를 봤다), d < z − 여유면 가려져 시험하지
 *   않는다. 여유 = max(τ / cos θ, |b(d)| + 2σ(d))(설계 성질 ③과 같은 식, θ는 그 화소의 면 입사각). 비율은 모순인 (칸, 장) 수 ÷ 시험한 (칸, 장) 수. 광선이 칸을 스치는지로 판정하면 표면이 지나는
 *   칸의 빈 부분을 비스듬히 지난 광선도 모순이 되므로(M33 구현 중 확인: B 152354 원시 0.57) 칸의 화소로 판정한다(설계 성질 ④와 같은 원리).
 * - 면 두께·바닥 평탄도: 정답 면 ±[FACE_BAND_M] 안이고 가장 가까운 정답 면이 그 면인 점유 칸에 그 칸들의 평면(최소제곱)을 맞춘 거리의
 *   p90 − p10. 정답은 칸을 면에 나누는 데만 쓴다(벽 모서리·바닥 가장자리 칸이 섞이지 않게).
 * - 왕복 일관성: 점유 칸을 마지막 관측 시각이 든 걷는 구간(갈 때·올 때)으로 나눠, 두 구간에서 관측된 옆 벽 칸 x 중앙값·바닥 칸 y 중앙값의
 *   차. 앞 구간에서 남은 칸이 뒤 구간 값에 섞이지 않게 마지막 관측 시각으로 가른다.
 * 상수의 출처는 M33 설계 표 5.
 */
object MapCheck {
    const val BODY_RADIUS_M = 0.2f
    const val CAMERA_RADIUS_M = 0.05f
    const val FLOOR_BAND_M = SceneEval.FLOOR_UNCERTAIN_M
    const val ABOVE_CAMERA_M = 0.10f
    const val LATER_WINDOW_S = 2.0
    const val FACE_BAND_M = 0.3f
    /** 왕복 판정의 걷는 구간: 카메라 진행 방향 속도(정답 z, m/s)가 이보다 큼(`metrics.py` WALKING_MPS와 같은 값). */
    const val WALKING_MPS = 0.25f
    private const val MIN_FACE_CELLS = 30
    private const val POSE_EVERY = 3

    /** 스냅샷: 점유 칸 중심(월드)과 칸마다 마지막 관측 시각. */
    class Snapshot(val tNs: Long, val occupiedW: List<Vec3>, val lastSeenNs: LongArray)

    /** 비율 하나(분자·분모를 함께 남겨 세션 합산과 세션 평균을 둘 다 할 수 있게). */
    class Count(var num: Long = 0, var den: Long = 0) {
        fun json() = """{"num": $num, "den": $den, "ratio": ${if (den == 0L) "null" else "%.5f".format(num.toDouble() / den)}}"""
    }

    /**
     * 세션 하나를 검사해 JSON을 돌려준다.
     * @param poses 세션의 모든 자세(추적 중인 것, 시각 순).
     * @param testDepths 나중 장 모순의 시험 깊이(이름 → 시각 순 깊이 영상, 예: raw·smoothed)와 그 깊이의 σ·편향 모형([raw] 여부).
     */
    fun run(
        snapshots: List<Snapshot>,
        poses: List<PoseFrame>,
        testDepths: Map<String, Pair<Boolean, List<DepthFrame>>>,
        depthModel: DepthConfig,
        al: Alignment,
        scene: SceneEval.Scene,
        voxelM: Float,
        radiusM: Float,
        headOffsetM: Vec3,
        /** 입사각 cos 하한(`map.tsdfMinCosIncidence`). */
        minCos: Float,
    ): String {
        val tau = SceneEval.tau(voxelM)
        val foot = Count()
        val contra = testDepths.mapValues { Count() }
        val contraAbove = testDepths.mapValues { Count() }
        val faces = HashMap<String, MutableList<Float>>()
        val floorFlat = ArrayList<Float>()
        val floorBelow = Count()
        val heads = poses.filterIndexed { i, _ -> i % POSE_EVERY == 0 }.map { p ->
            val camW = p.worldFromCam.translation()
            val fwd = p.worldFromCam.transformDir(Vec3(0f, 0f, 1f))
            Triple(p.tCaptureNs, al.toTruth(camW), al.toTruth(HeadPose.fromCamera(camW, fwd.horizontal().normalized(), headOffsetM).positionW))
        }
        for (s in snapshots) {
            val camT = heads.lastOrNull { it.first <= s.tNs }?.second ?: continue
            val occT = s.occupiedW.map { al.toTruth(it) }.filter { hypot(it.x - camT.x, it.z - camT.z) <= radiusM }
            footprint(occT, heads.filter { it.first > s.tNs }, camT, voxelM, radiusM, foot)
            for ((name, src) in testDepths) {
                val later = src.second.filter { it.tCaptureNs > s.tNs && it.tCaptureNs <= s.tNs + (LATER_WINDOW_S * 1e9).toLong() }
                val above = s.occupiedW.filter { al.toTruth(it).y > FLOOR_BAND_M }
                for (d in later) {
                    contradiction(s.occupiedW, d, src.first, depthModel, tau, minCos, contra.getValue(name))
                    contradiction(above, d, src.first, depthModel, tau, minCos, contraAbove.getValue(name))
                }
            }
            faceStats(occT, scene, tau, faces, floorFlat, floorBelow)
        }
        val roundTrip = roundTrip(snapshots, heads, al, scene, voxelM)
        fun med(v: List<Float>) = if (v.isEmpty()) "null" else "%.4f".format(v.sorted()[v.size / 2])
        return buildString {
            append("{\n  \"footprint\": ").append(foot.json())
            append(",\n  \"contradiction\": {").append(contra.entries.joinToString(", ") { "\"${it.key}\": ${it.value.json()}" }).append("}")
            append(",\n  \"contradictionAboveFloorBand\": {").append(contraAbove.entries.joinToString(", ") { "\"${it.key}\": ${it.value.json()}" }).append("}")
            append(",\n  \"thicknessM\": {").append(faces.entries.sortedBy { it.key }.joinToString(", ") { "\"${it.key}\": ${med(it.value)}" }).append("}")
            append(",\n  \"floorFlatnessM\": ").append(med(floorFlat))
            append(",\n  \"floorBelow\": ").append(floorBelow.json())
            append(",\n  \"roundTripM\": ").append(roundTrip)
            append("\n}\n")
        }
    }

    /** 발자국: 나중 몸 공간(정답 좌표 칸)의 (ix, iz) → 공간 위 끝 높이. */
    private fun footprint(occT: List<Vec3>, future: List<Triple<Long, Vec3, Vec3>>, camT: Vec3, voxelM: Float, radiusM: Float, out: Count) {
        val top = HashMap<Long, Float>()
        fun disc(c: Vec3, r: Float, t: Float) {
            val n = (r / voxelM).toInt() + 1
            val ix0 = floor(c.x / voxelM).toInt()
            val iz0 = floor(c.z / voxelM).toInt()
            for (ix in ix0 - n..ix0 + n) for (iz in iz0 - n..iz0 + n) {
                if (hypot((ix + 0.5f) * voxelM - c.x, (iz + 0.5f) * voxelM - c.z) > r) continue
                top.merge(key2(ix, iz), t, ::max)
            }
        }
        for ((_, cam, head) in future) {
            disc(head, BODY_RADIUS_M, cam.y + ABOVE_CAMERA_M)
            disc(cam, CAMERA_RADIUS_M, cam.y + ABOVE_CAMERA_M)
        }
        for ((k, t) in top) {
            val x = ((k shr 32).toInt() + 0.5f) * voxelM
            val z = (k.toInt() + 0.5f) * voxelM
            if (hypot(x - camT.x, z - camT.z) > radiusM) continue
            val lo = floor(FLOOR_BAND_M / voxelM).toInt()
            val hi = floor(t / voxelM).toInt()
            for (iy in lo..hi) if ((iy + 0.5f) * voxelM in FLOOR_BAND_M..t) out.den++
        }
        for (p in occT) {
            val t = top[key2(floor(p.x / voxelM).toInt(), floor(p.z / voxelM).toInt())] ?: continue
            if (p.y in FLOOR_BAND_M..t) out.num++
        }
    }

    private fun key2(ix: Int, iz: Int): Long = (ix.toLong() shl 32) or (iz.toLong() and 0xFFFFFFFFL)

    /** 나중 장 하나로 얼린 점유 칸을 시험한다(칸 중심을 투영한 화소의 깊이). */
    private fun contradiction(occW: List<Vec3>, d: DepthFrame, raw: Boolean, model: DepthConfig, tau: Float, minCos: Float, out: Count) {
        val k = d.K
        val camFromWorld = d.worldFromCam.rigidInverse()
        for (c in occW) {
            val p = camFromWorld.transformPoint(c)
            if (p.z <= 0f) continue
            val u = (k.fx * p.x / p.z + k.cx).roundToInt()
            val w = (k.fy * p.y / p.z + k.cy).roundToInt()
            if (u < 0 || u >= k.width || w < 0 || w >= k.height) continue
            val mm = d.depthMm[w * k.width + u].toInt() and 0xFFFF
            if (mm == 0) continue
            val z = mm / 1000f
            val margin = max(tau / Projection.cosIncidence(d.depthMm, k, u, w, minCos), abs(model.biasM(raw, z)) + 2f * model.sigmaM(raw, z))
            if (z < p.z - margin) continue
            out.den++
            if (z > p.z + margin) out.num++
        }
    }

    /** 정답 벽 면·바닥 ±[FACE_BAND_M] 안 점유 칸의 자기 평면 거리 폭. 벽 이름별·바닥. */
    private fun faceStats(occT: List<Vec3>, scene: SceneEval.Scene, tau: Float, faces: MutableMap<String, MutableList<Float>>, floorFlat: MutableList<Float>, below: Count) {
        for (s in scene.solids) {
            if (s.element != Element.WALL) continue
            val face = innerFace(s) ?: continue
            val (axis, c) = face
            val pts = occT.filter { p -> abs(p.c(axis) - c) <= FACE_BAND_M && p.y in FLOOR_BAND_M..scene.regionMax.y && scene.nearestSolid(p) === s }
            val r = planeResiduals(pts, axis) ?: continue
            faces.getOrPut(s.name) { ArrayList() } += spread(r)
        }
        val fl = occT.filter { abs(it.y) <= FACE_BAND_M && scene.inRegion(it) && scene.nearestSolid(it) == null }
        val r = planeResiduals(fl, 1) ?: return
        floorFlat += spread(r)
        below.num += r.count { it < -tau }
        below.den += r.size
    }

    /** 통로 쪽 면: 가장 얇은 축과 그 축에서 보행선(x = 0) 또는 시작(z = 0) 쪽 면의 좌표. */
    private fun innerFace(s: SceneEval.Solid): Pair<Int, Float>? {
        val ext = floatArrayOf(s.max.x - s.min.x, s.max.y - s.min.y, s.max.z - s.min.z)
        val axis = listOf(0, 2).minByOrNull { ext[it] } ?: return null
        if (ext[axis] > ext[1]) return null
        return axis to if (abs(s.min.c(axis)) < abs(s.max.c(axis))) s.min.c(axis) else s.max.c(axis)
    }

    /** [axis] 좌표를 나머지 두 축의 1차식으로 맞춘 잔차(최소제곱). 칸이 [MIN_FACE_CELLS]보다 적으면 null. */
    private fun planeResiduals(pts: List<Vec3>, axis: Int): FloatArray? {
        if (pts.size < MIN_FACE_CELLS) return null
        val (a1, a2) = (0..2).filter { it != axis }
        val m = Array(3) { DoubleArray(4) }
        for (p in pts) {
            val row = doubleArrayOf(1.0, p.c(a1).toDouble(), p.c(a2).toDouble())
            for (i in 0..2) { for (j in 0..2) m[i][j] += row[i] * row[j]; m[i][3] += row[i] * p.c(axis) }
        }
        val c = solve3(m) ?: return null
        val norm = sqrt(1.0 + c[1] * c[1] + c[2] * c[2])
        return FloatArray(pts.size) { i -> val p = pts[i]; ((p.c(axis) - (c[0] + c[1] * p.c(a1) + c[2] * p.c(a2))) / norm).toFloat() }
    }

    private fun solve3(m: Array<DoubleArray>): DoubleArray? {
        for (col in 0..2) {
            val piv = (col..2).maxByOrNull { abs(m[it][col]) }!!
            if (abs(m[piv][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0..2) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (k in col..3) m[r][k] -= f * m[col][k]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    private fun spread(r: FloatArray): Float { val s = r.sorted(); return s[(s.size - 1) * 9 / 10] - s[(s.size - 1) / 10] }

    /**
     * 왕복 일관성: 칸의 마지막 관측 시각에서 카메라의 정답 z 속도 부호로 갈 때(+)·올 때(−)를 정한다(걷지 않는 시각의 칸은 뺀다). 두 구간에서
     * 관측된 칸(칸 단위로 중복 제거) 가운데 왼·오른 벽 면 ±[FACE_BAND_M]의 x 중앙값과 바닥 ±[FACE_BAND_M]·보행선 ±0.4 m의 y 중앙값을 내고
     * 갈 때 − 올 때를 쓴다. 구간마다 칸이 [MIN_FACE_CELLS]보다 적은 항목은 뺀다. 왕복이 없으면 null.
     */
    private fun roundTrip(snapshots: List<Snapshot>, heads: List<Triple<Long, Vec3, Vec3>>, al: Alignment, scene: SceneEval.Scene, voxelM: Float): String {
        if (heads.size < 3) return "null"
        fun legAt(tNs: Long): Int {
            val i = heads.indexOfLast { it.first <= tNs }
            if (i < 1 || i >= heads.size - 1) return 0
            val v = (heads[i + 1].second.z - heads[i - 1].second.z) / ((heads[i + 1].first - heads[i - 1].first) / 1e9f)
            return if (abs(v) < WALKING_MPS) 0 else if (v > 0) 1 else -1
        }
        val cells = mapOf(1 to HashMap<Long, Vec3>(), -1 to HashMap())
        for (s in snapshots) for ((i, w) in s.occupiedW.withIndex()) {
            val leg = legAt(s.lastSeenNs[i])
            if (leg != 0) cells.getValue(leg)[SceneEval.key(w, voxelM)] = al.toTruth(w)
        }
        val walls = scene.solids.filter { it.element == Element.WALL }.mapNotNull { s -> innerFace(s)?.takeIf { it.first == 0 }?.let { s.name to it.second } }
        fun med(v: List<Float>) = if (v.size < MIN_FACE_CELLS) null else v.sorted()[v.size / 2]
        val out = ArrayList<String>()
        for ((name, x) in walls) {
            val m = listOf(1, -1).map { l -> med(cells.getValue(l).values.filter { abs(it.x - x) <= FACE_BAND_M && it.y > FLOOR_BAND_M }.map { it.x }) }
            if (m.all { it != null }) out += "\"$name\": %.4f".format(m[0]!! - m[1]!!)
        }
        val f = listOf(1, -1).map { l -> med(cells.getValue(l).values.filter { abs(it.y) <= FACE_BAND_M && abs(it.x) < 0.4f }.map { it.y }) }
        if (f.all { it != null }) out += "\"floor\": %.4f".format(f[0]!! - f[1]!!)
        return if (out.isEmpty()) "null" else out.joinToString(", ", "{", "}")
    }

    private fun Vec3.c(a: Int) = when (a) { 0 -> x; 1 -> y; else -> z }
}
