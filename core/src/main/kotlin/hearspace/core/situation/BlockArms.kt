package hearspace.core.situation

import hearspace.core.geometry.Vec3
import hearspace.core.types.SituationConfig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 팔 세기 중심 하나의 판단(M69 설계 표 4). */
enum class ArmKind { AREA, NO_BACK, STRAIGHT, FORK, TURN_LEFT, TURN_RIGHT, END, STAIRS_UP, STAIRS_DOWN, STAIRS_LABEL, UNSEEN, UNKNOWN }

/** 앞 팔이 없는 자리의 너머(설계 3.3절). RAISED_*는 깊이로 바닥면보다 높음: WALK = 노면 라벨(올라가는 계단), BUILDING = 배경 라벨(문·벽). */
enum class Beyond { NONE, FLOOR, STAIRS_LABEL, DROP, RAISED_WALK, RAISED_BUILDING, HAZARD, UNKNOWN }

/**
 * [center]는 띠 가운데로 옮긴 중심(월드), [aheadM]은 사용자에서 진행 방향 앞 거리, [armsDeg]는 팔 방향(수평면 각, x→z).
 * [beyondLowM]은 너머 띠의 낮음 중앙값(기록용, 없으면 NaN).
 */
class ArmDecision(val center: Vec3, val aheadM: Float, val kind: ArmKind, val beyond: Beyond, val armsDeg: List<Float>,
                  val beyondLowM: Float = Float.NaN)

/**
 * 깊이로 잰 노면 점 낮음([Situations.lowM], 음수면 높음)을 띠마다 내 준다: 점 (cx, cz)에서 수평 방향 (fx, fz)로 [fromM]~[toM], 옆 ±[halfM] 안의
 * 마지막 노면 영상 점. 필요한 띠만 계산한다(M69: 판단마다 점 전부를 계산하면 원래 크기 노면 영상에서 기기 상황 판단 p50 238 ms).
 */
fun interface LowSource {
    fun band(cx: Float, cz: Float, fx: Float, fz: Float, fromM: Float, toM: Float, halfM: Float): List<Float>
}

/**
 * 팔 세기(M69 설계 3.1~3.3절). 따라가는 블록 선 위 [SituationConfig.armCenterStepM]마다 중심을 두고, 중심에서 [SituationConfig.armStepDeg] 간격
 * 광선으로 블록 칸을 따라 나아가 [SituationConfig.armMinM]에 닿는 방향을 팔로 센다. 앞 팔이 없는 자리는 너머(노면 라벨과 깊이)로 끝·꺾임·갈림·계단을 가른다.
 * 칸은 [counts](칸 열쇠 → 클래스별 관측 수)로 읽고, 블록 칸 = 가장 많이 본 클래스가 블록이고 블록으로 본 관측이 [SituationConfig.confirmFrames] 이상.
 * T01·2026-11-24 녹화로 정한 것(사용자 확인 전 개발 자료): 중심을 띠 가로 가운데로 옮김(RANSAC 선이 띠 가장자리를 지남),
 * 앞·뒤 팔은 따라가는 방향 ±[SituationConfig.armAxisTolDeg]를 포함하는 묶음만, 옆 팔은 앞·뒤에서 [SituationConfig.armSideMinDeg] 넘게(휜 띠 조각을 세지 않음).
 */
class BlockArms(private val cfg: SituationConfig, private val key: (Float, Float) -> Long) {

    fun decide(user: Vec3, follow: BlockLine, counts: (Long) -> IntArray?, lows: LowSource): List<ArmDecision> {
        val fx = follow.dir.x
        val fz = follow.dir.z
        val fDeg = deg(atan2(fz, fx))
        val tu = follow.t(user)
        val out = ArrayList<ArmDecision>()
        var t = tu + cfg.endMinM
        val tEnd = minOf(tu + cfg.armDecideMaxM, follow.tMax + cfg.turnJoinM)
        while (t <= tEnd + 1e-4f) {
            var cx = follow.p0.x + fx * t
            var cz = follow.p0.z + fz * t
            // 띠 가운데로: 선 앞뒤 ±0.15 m, 옆 ±0.45 m 블록 칸의 옆 평균
            var sum = 0f
            var n = 0
            for (kx in -6..6) for (kz in -6..6) {
                val x = cx + kx * cfg.gridCellM
                val z = cz + kz * cfg.gridCellM
                val along = (x - cx) * fx + (z - cz) * fz
                val lat = (x - cx) * -fz + (z - cz) * fx
                if (abs(along) <= 0.15f && abs(lat) <= 0.45f && block(counts, x, z)) { sum += lat; n++ }
            }
            if (n > 0) {
                val m = sum / n
                cx -= fz * m
                cz += fx * m
                out.add(at(cx, cz, t - tu, fx, fz, fDeg, counts, lows))
            }
            t += cfg.armCenterStepM
        }
        return out
    }

    private fun at(cx: Float, cz: Float, ahead: Float, fx: Float, fz: Float, fDeg: Float, counts: (Long) -> IntArray?, lows: LowSource): ArmDecision {
        val c = Vec3(cx, 0f, cz)
        val groups = clusters(rays(counts, cx, cz))
        val mids = groups.map { it.mid }
        if (groups.any { it.width > cfg.armMaxSpreadDeg }) return ArmDecision(c, ahead, ArmKind.AREA, Beyond.NONE, mids)
        fun has(dir: Float, g: Group) = g.angles.any { angDiff(it, dir) <= cfg.armAxisTolDeg }
        val back = groups.any { has(fDeg + 180f, it) }
        val fwd = groups.any { has(fDeg, it) }
        val sides = groups.filter { !has(fDeg, it) && !has(fDeg + 180f, it) && angDiff(it.mid, fDeg) >= cfg.armSideMinDeg && angDiff(it.mid, fDeg + 180f) >= cfg.armSideMinDeg }
            .map { it.mid }
        // 뒤 팔 = 걸어온 블록(설계 3.2절): 뒤 방향 묶음이 있고, 중심에서 사용자 쪽으로 블록이 (앞 거리 − 0.5 m)까지 이어져야 한다(M69 고침:
        // T01 162 s 골목에서 인도로 올라서며 연석 따라 깔린 점형 줄을 비스듬히 밟아, 줄이 뒤·옆 팔로 잡혀 '갈림'이 됨)
        if (!back || !walkedIn(counts, cx, cz, fx, fz, ahead - 0.5f)) return ArmDecision(c, ahead, ArmKind.NO_BACK, Beyond.NONE, mids)
        if (fwd) return ArmDecision(c, ahead, if (sides.isEmpty()) ArmKind.STRAIGHT else ArmKind.FORK, Beyond.NONE, mids)
        val bz = beyond(counts, lows, cx, cz, fx, fz)
        val b = bz.beyond
        val anyBlock = bz.anyBlock
        val right = norm(fDeg + 90f) // rightOf(f) = (-fz, fx)
        val left = norm(fDeg - 90f)
        fun turn(side: Float): ArmKind {
            val isRight = angDiff(side, right) < angDiff(side, left)
            return if (sideClear(counts, cx, cz, if (isRight) left else right)) (if (isRight) ArmKind.TURN_RIGHT else ArmKind.TURN_LEFT) else ArmKind.UNSEEN
        }
        val kind = when (b) {
            Beyond.STAIRS_LABEL -> ArmKind.STAIRS_LABEL
            // 내려가는 계단은 가로줄 규칙([rowStairs])만 말한다(2026-11-25: 꺾임 모퉁이 너머를 낙차로 봐 거짓 '내려가는 계단' S10 3회 모두, 진짜 내려가는
            // 계단 11-24 둘·11-25 둘은 모두 가로줄 규칙이 말함). 계단 앞 가로줄은 시설 폭으로 선 양쪽에 깔리고 꺾임 팔은 한쪽뿐이라, 옆 팔이 하나면
            // 너머 깊이가 낮게 재져도 꺾임(135720 모퉁이 너머 평평한 광장을 0.5 m 넘게 낮게 잼, S10 3회 모두 꺾임 놓침), 둘 이상이면 가로줄일 수 있어 모름
            Beyond.DROP -> if (sides.size == 1) turn(sides[0]) else ArmKind.UNKNOWN
            // 높게 재진 너머는 올라가는 계단·문·벽을 깊이로 가르지 못한다(154133 계단·154036 유리문 둘 다 약 0.3 m 높음, 2026-11-25) → 끝만 말함
            Beyond.RAISED_WALK, Beyond.RAISED_BUILDING, Beyond.HAZARD -> ArmKind.END
            Beyond.FLOOR -> when {
                sides.size >= 2 -> ArmKind.FORK
                sides.size == 1 -> turn(sides[0])
                // 인도 위 끝: 너머에 블록 칠이 전혀 없고(칠 끊김을 끝으로 보지 않게, 옛 끝 규칙) 양옆이 블록 아닌 바닥으로 관측
                else -> if (!anyBlock && sideClear(counts, cx, cz, right) && sideClear(counts, cx, cz, left)) ArmKind.END else ArmKind.UNSEEN
            }
            else -> ArmKind.UNKNOWN
        }
        return ArmDecision(c, ahead, kind, b, mids, bz.beyondLow)
    }

    /**
     * 옆 팔이 띠 모양인지(M69, T01 169.8 s 선 옆 맨홀 노란 원 칠): 팔 방향 [armDeg]로 0.6~1.0 m에서 팔에 수직인 블록 칸 폭이 모두
     * [SituationConfig.armMaxWidthM] 이하. 선형블록 폭은 0.3 m(입구 앞 0.6 m, 표 3)이고, 둥근 칠 덩어리는 넓다.
     */
    private fun strip(counts: (Long) -> IntArray?, cx: Float, cz: Float, armDeg: Float): Boolean {
        val a = Math.toRadians(armDeg.toDouble())
        val dx = cos(a).toFloat()
        val dz = sin(a).toFloat()
        var d = 0.6f
        while (d <= cfg.armMinM + 1e-4f) {
            var n = 0
            var s = -1.0f
            while (s <= 1.0f + 1e-4f) {
                if (block(counts, cx + dx * d - dz * s, cz + dz * d + dx * s)) n++
                s += cfg.gridCellM
            }
            if (n * cfg.gridCellM > cfg.armMaxWidthM + 1e-4f) return false
            d += cfg.gridCellM
        }
        return true
    }

    /**
     * 앞을 가로지르는 가로줄 너머 계단(M69, 사용자: 154154 계단은 늦어도 270f, 154133 324f 무렵 올라가는 계단). 따라가는 선과 상관없이
     * 진행 방향 [SituationConfig.endMinM]~[SituationConfig.rowMaxM]에서 처음 만나는 블록 가로줄(옆 ±0.6 m 중 [SituationConfig.rowMinCells]칸 이상, 설치 기준:
     * 계단 30 cm 앞 시설 폭 점형)의 너머 0.3~1.5 m 노면 점으로 본다. 낮은 점(> [SituationConfig.dropMinM])이 [SituationConfig.stairsDepthFraction] 넘으면
     * 내려감, 낮음 중앙값 < −[SituationConfig.rowRaiseM]이면 올라감(평평한 바닥 먼 쪽 높게 보임 최대 +0.34 m보다 크게). 차도 라벨이면 판단 안 함.
     * 반환: (가로줄 앞 거리, 내려감 true / 올라감 false), 없으면 null.
     */
    fun rowStairs(user: Vec3, h: Vec3, counts: (Long) -> IntArray?, lows: LowSource): Pair<Float, Boolean>? {
        val rx = -h.z
        val rz = h.x
        var a = cfg.endMinM
        var row = Float.NaN
        while (a <= cfg.rowMaxM + 1e-4f) {
            var n = 0
            var lo = 0
            var hi = 0
            for (j in -6..6) if (block(counts, user.x + h.x * a + rx * j * cfg.gridCellM, user.z + h.z * a + rz * j * cfg.gridCellM)) {
                n++
                if (j <= 0) lo++
                if (j >= 0) hi++
            }
            // 계단 앞 가로줄은 시설 폭이라 진행 방향 양쪽에 깔린다: 양쪽 각각 rowMinCells의 절반 이상(2026-11-25: 한쪽으로만 뻗은 꺾임 모퉁이를 가로줄로 봐 거짓 '내려가는 계단')
            if (n >= cfg.rowMinCells && lo >= cfg.rowMinCells / 2 && hi >= cfg.rowMinCells / 2) { row = a; break }
            a += cfg.gridCellM
        }
        if (row.isNaN()) return null
        val band = lows.band(user.x, user.z, h.x, h.z, row + cfg.beyondFromM, row + cfg.beyondToM, cfg.beyondHalfWidthM).sorted()
        if (band.size < cfg.stairsMinPoints) return null
        var road = 0
        var seen = 0
        var t = row + cfg.beyondFromM
        while (t <= row + cfg.beyondToM + 1e-4f) {
            counts(key(user.x + h.x * t, user.z + h.z * t))?.let { v ->
                seen++
                if (major(v) in setOf(SurfaceClass.ROADWAY.pixel, SurfaceClass.CROSSWALK.pixel, SurfaceClass.ALLEY.pixel, SurfaceClass.BUMP.pixel)) road++
            }
            t += cfg.gridCellM
        }
        if (road * 2 > seen) return null
        if (band.count { it > cfg.dropMinM } > cfg.stairsDepthFraction * band.size) return row to true
        if (band[band.size / 2] < -cfg.rowRaiseM) return row to false
        return null
    }

    /** 중심에서 따라가는 방향 반대로 [lengthM]까지 블록 칸이 이어지는지(옆 ±0.15 m 안 블록 칸, 빈칸 [SituationConfig.armGapM]까지 허용). */
    private fun walkedIn(counts: (Long) -> IntArray?, cx: Float, cz: Float, fx: Float, fz: Float, lengthM: Float): Boolean {
        var d = 0f
        var gap = 0f
        val step = cfg.gridCellM / 2f
        while (d < lengthM) {
            d += step
            val x = cx - fx * d
            val z = cz - fz * d
            if (block(counts, x, z) || block(counts, x - fz * 0.15f, z + fx * 0.15f) || block(counts, x + fz * 0.15f, z - fx * 0.15f)) gap = 0f
            else {
                gap += step
                if (gap > cfg.armGapM + 1e-4f) return false
            }
        }
        return true
    }

    private fun block(counts: (Long) -> IntArray?, x: Float, z: Float): Boolean {
        val v = counts(key(x, z)) ?: return false
        return major(v) == SurfaceClass.BLOCK.pixel && v[SurfaceClass.BLOCK.pixel] >= cfg.confirmFrames
    }

    /**
     * 방향마다 띠 가장자리에서 [SituationConfig.armMinM](중심에서 armMinM + [SituationConfig.armEdgeM])에 닿는지. 블록 아닌 칸이 [SituationConfig.armGapM] 넘게
     * 이어지면 멈춘다. 가장자리에서 재는 까닭(M69): T01 169.6 s 선 옆 맨홀 노란 칠 덩어리의 먼 모서리가 중심에서 1.0 m라 뒤 대각선 팔이 됨.
     */
    private fun rays(counts: (Long) -> IntArray?, cx: Float, cz: Float): BooleanArray {
        val n = (360f / cfg.armStepDeg).toInt()
        val step = cfg.gridCellM / 2f
        return BooleanArray(n) { k ->
            val a = Math.toRadians((k * cfg.armStepDeg).toDouble())
            val dx = cos(a).toFloat()
            val dz = sin(a).toFloat()
            var d = 0f
            var gap = 0f
            var ok = false
            while (true) {
                d += step
                if (d > cfg.armMinM + cfg.armEdgeM + 1e-4f) { ok = true; break }
                if (block(counts, cx + dx * d, cz + dz * d)) gap = 0f else {
                    gap += step
                    if (gap > cfg.armGapM + 1e-4f) break
                }
            }
            ok
        }
    }

    private class Group(val angles: List<Float>, val mid: Float, val width: Float)

    private fun clusters(hits: BooleanArray): List<Group> {
        val n = hits.size
        if (hits.all { it }) return listOf(Group((0 until n).map { it * cfg.armStepDeg }, 0f, 360f))
        val start = hits.indexOfFirst { !it }
        val out = ArrayList<Group>()
        var cur = ArrayList<Int>()
        for (i in 1..n) {
            val k = (start + i) % n
            if (hits[k]) cur.add(k) else if (cur.isNotEmpty()) {
                out.add(Group(cur.map { it * cfg.armStepDeg }, norm((cur[0] + (cur.size - 1) / 2f) * cfg.armStepDeg), cur.size * cfg.armStepDeg))
                cur = ArrayList()
            }
        }
        return out
    }

private class BeyondResult(val beyond: Beyond, val anyBlock: Boolean, val beyondLow: Float)

    /**
     * 너머 띠의 판단과, 띠에 블록 칠이 조금이라도 있는지. 차도·횡단보도·골목 라벨을 깊이보다 먼저 본다(라벨이 확실한 곳을 깊이 잡음으로 뒤집지 않음,
     * T01 279 s 횡단보도 앞). 낮음은 바닥면 기준(지도 바닥)이다. 같은 장의 바로 앞 점과 견주는 방식은 그 점들이 화면 아래 끝이라 없거나
     * 크게 튀어(+1.8~+14 m, 2026-11-25 측정) 쓰지 않는다.
     */
    private fun beyond(counts: (Long) -> IntArray?, lows: LowSource, cx: Float, cz: Float, fx: Float, fz: Float): BeyondResult {
        val rx = -fz
        val rz = fx
        val keys = HashSet<Long>()
        val seen = ArrayList<IntArray>()
        var a = cfg.beyondFromM
        while (a <= cfg.beyondToM + 1e-4f) {
            var s = -cfg.beyondHalfWidthM
            while (s <= cfg.beyondHalfWidthM + 1e-4f) {
                val k = key(cx + fx * a + rx * s, cz + fz * a + rz * s)
                if (keys.add(k)) counts(k)?.let { seen.add(it) }
                s += cfg.gridCellM
            }
            a += cfg.gridCellM
        }
        val anyBlock = seen.any { major(it) == SurfaceClass.BLOCK.pixel || it[SurfaceClass.BLOCK.pixel] > 0 }
        val band = lows.band(cx, cz, fx, fz, cfg.beyondFromM, cfg.beyondToM, cfg.beyondHalfWidthM)
        val beyondLow = if (band.isEmpty()) Float.NaN else band.sorted()[band.size / 2]
        fun result(b: Beyond) = BeyondResult(b, anyBlock, beyondLow)
        if (seen.isEmpty()) return result(Beyond.UNKNOWN)
        val majors = seen.map { major(it) }
        if (seen.count { major(it) == SurfaceClass.STAIRS.pixel && it[SurfaceClass.STAIRS.pixel] >= cfg.confirmFrames } * 2 > seen.size) return result(Beyond.STAIRS_LABEL)
        // 방지턱은 차도 위에 있다(노면 모델이 T01 횡단보도를 방지턱으로 칠함, M50) → 차도와 같이 센다
        if (majors.count { it == SurfaceClass.ROADWAY.pixel || it == SurfaceClass.CROSSWALK.pixel || it == SurfaceClass.ALLEY.pixel || it == SurfaceClass.BUMP.pixel } * 2 > seen.size) return result(Beyond.HAZARD)
        val depthOk = band.size >= cfg.stairsMinPoints
        // 낙차는 계단 앞 가로줄(설치 기준: 계단 30 cm 앞에 시설 폭만큼 점형)이 너머 띠에 있을 때만: 너머 칸의 블록 몫 ≥ beyondRowFrac.
        // 가로줄 없는 낙차는 바닥 기준 흔들림의 거짓(T01 237 s, 블록 1~12%)과 가르지 못해 모름으로 둔다(진짜 계단 154230·154154는 35~61%)
        val rowFrac = majors.count { it == SurfaceClass.BLOCK.pixel }.toFloat() / seen.size
        if (depthOk && band.count { it > cfg.dropMinM } > cfg.stairsDepthFraction * band.size) return result(if (rowFrac >= cfg.beyondRowFrac) Beyond.DROP else Beyond.UNKNOWN)
        if (depthOk && band.count { it < -cfg.floorTolM } * 2 > band.size) {
            val bg = majors.count { it == SurfaceClass.BACKGROUND.pixel }
            return result(if (bg * 2 > seen.size) Beyond.RAISED_BUILDING else Beyond.RAISED_WALK)
        }
        val walk = majors.count { it == SurfaceClass.SIDEWALK.pixel || it == SurfaceClass.BLOCK.pixel }
        if (seen.size >= cfg.beyondSeenFrac * keys.size && walk >= cfg.beyondWalkFrac * seen.size && depthOk &&
            band.count { abs(it) > cfg.floorTolM } <= cfg.floorLowFrac * band.size
        ) return result(Beyond.FLOOR)
        return result(Beyond.UNKNOWN)
    }

    /** 옆 방향 [ang]° ±[SituationConfig.turnMinDeg], [SituationConfig.sideFromM]~[SituationConfig.armMinM] 칸의 절반 이상이 관측되고 본 칸의 과반이 블록 아님. */
    private fun sideClear(counts: (Long) -> IntArray?, cx: Float, cz: Float, ang: Float): Boolean {
        val keys = HashSet<Long>()
        var seen = 0
        var nonBlock = 0
        var dd = -cfg.turnMinDeg
        while (dd <= cfg.turnMinDeg + 1e-4f) {
            val a = Math.toRadians((ang + dd).toDouble())
            var d = cfg.sideFromM
            while (d <= cfg.armMinM + 1e-4f) {
                val k = key(cx + cos(a).toFloat() * d, cz + sin(a).toFloat() * d)
                if (keys.add(k)) counts(k)?.let { v ->
                    seen++
                    if (!(major(v) == SurfaceClass.BLOCK.pixel && v[SurfaceClass.BLOCK.pixel] >= cfg.confirmFrames)) nonBlock++
                }
                d += cfg.gridCellM / 2f
            }
            dd += cfg.armStepDeg
        }
        return seen * 2 >= keys.size && nonBlock * 2 > seen
    }

    companion object {
        fun major(v: IntArray): Int = v.indices.maxBy { v[it] }
        fun deg(rad: Float): Float = norm(Math.toDegrees(rad.toDouble()).toFloat())
        fun norm(d: Float): Float = ((d % 360f) + 360f) % 360f
        fun angDiff(a: Float, b: Float): Float = abs(((a - b + 180f) % 360f + 360f) % 360f - 180f)
    }
}
