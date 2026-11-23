package hearspace.core.situation

import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.AudioCmd
import hearspace.core.types.DepthFrame
import hearspace.core.types.LabelImage
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.SemanticLabel
import hearspace.core.types.SituationConfig
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt

/** 걷는 환경(M50 설계 표 5). UNKNOWN은 말하지 않는다. */
enum class Environment(val text: String?, val priority: Int) {
    BLOCK_SIDEWALK("점자블록 인도", 3), SIDEWALK("인도", 3), ALLEY("골목길, 차 조심", 2), ROADWAY("차도", 2), UNKNOWN(null, 0)
}

/** 상황 판단 한 차례의 입력. 모두 그 시각까지 받은 과거 값이다. */
class SituationInput(
    /** 판단을 내는 시각(재생 가상 시계 = 앱 수신 시계, 정보 나이의 기준). */
    val nowNs: Long,
    /** 이번 느린 경로의 깊이 장(의미 영상이 붙어 있으면 1g 라벨에 쓴다). */
    val depth: DepthFrame,
    val snapshot: ObstacleSnapshot,
    /** 지금 안내 정책이 내는 음원(장애물 출력). 없으면 빈 목록. */
    val outputs: List<AudioCmd>,
    /** 진행 방향(수평 단위). 모르면 null. */
    val headingW: Vec3?,
    /** 쓸 수 있는 가장 최근 노면 영상. 없으면 null. */
    val surface: LabelImage?,
    /** 앱에서 지금 말하는 중인 줄의 등급(0 = 쉼, [Speech.tick]). PC 재생은 null(음절 수 어림). */
    val outputPriority: Int? = null,
)

/** 한 차례의 상태 기록(`situation.csv`, 1b 블록 방향 값 포함). 없는 값은 NaN. */
data class SituationRecord(
    val tNs: Long,
    val env: Environment,
    val envCandidate: Environment,
    val nLines: Int,
    val followAngleDeg: Float,
    val lateralM: Float,
    val blockYawDeg: Float,
    val endAheadM: Float,
    val stairsAheadM: Float,
    val stairsDir: String,
    val nCells: Int,
    /** 낙차 앞 거리(M50 고침), 머리 위 천장을 봤는지(실내 고침). */
    val dropAheadM: Float = Float.NaN,
    val ceiling: Boolean = false,
)

/**
 * 보행상황 규칙(M50 설계 3.5절, 표 5). 지금 출력(장애물·움직이는 물체·통로)과 노면 격자를 읽기만 하고 말 요청을 낸다.
 * 관측한 것만 말한다: 블록 끝은 끝 너머를 블록 아닌 노면으로 봤을 때만, 내려가는 계단·낙차는 깊이가 바닥면보다 멀 때만.
 * 말 열쇠는 상황 종류(계단·방지턱·꺾임·끝·낙차)다. 자리(1 m 칸)를 열쇠에 넣으면 걸으며 가장 가까운 칸이 바뀔 때마다 같은 말이
 * 되풀이됐다(M50 첫 평가, T01 방지턱 19회).
 */
class Situations(
    private val cfg: SituationConfig,
    private val corridorWidthM: Float,
    /** 통로 높이(corridor.heightM). 이보다 높은 아래 향한 면을 천장으로 본다. */
    private val corridorHeightM: Float,
    private val heightWindowS: Float,
    maxInfoAgeNs: Long,
) {
    val grid = SurfaceGrid(cfg)
    val speech = Speech(cfg, maxInfoAgeNs)

    private val heights = ArrayDeque<Pair<Long, Float>>()
    private var lastSurfaceNs = Long.MIN_VALUE
    private var lastStepDepthNs = Long.MIN_VALUE
    /** 이번 판단의 깊이 장 시각. 노면 지도에 기대는 말의 근거 시각이다(M65 설계 승인 뒤 변경, 아래 [step]). */
    private var stepNs = Long.MIN_VALUE
    private var lastStepSurfaceNs = Long.MIN_VALUE

    /**
     * 판단할 차례인지(M65, `situation.stepIntervalMs`): 지난 판단 뒤 새 노면 영상이 왔으면 바로, 아니면 깊이 장 시각으로 그 간격이 지났을 때.
     * 노면 격자는 노면 영상(5 Hz)으로만 바뀌고 말은 같은 상황을 한 번·2 s 간격으로 내므로, 처리마다(약 30 Hz) 판단하면 같은 답을 되풀이한다.
     * 깊이 장 시각 기준이라 PC 재생과 앱이 같은 차례에 판단한다. 0이면 늘(M50까지).
     */
    fun due(depthTNs: Long, surfaceTNs: Long?): Boolean {
        if (cfg.stepIntervalMs <= 0) return true
        if (surfaceTNs != null && surfaceTNs > lastStepSurfaceNs) return true
        return lastStepDepthNs == Long.MIN_VALUE || depthTNs - lastStepDepthNs >= cfg.stepIntervalMs * 1_000_000L
    }
    private var env = Environment.UNKNOWN
    private var candidate = Environment.UNKNOWN
    private var candidateSinceNs = Long.MIN_VALUE
    private var driftOverSinceNs = Long.MIN_VALUE
    private var driftEpisode = 0
    private var drifting = false
    private var driftRight = false

    /** PC 재생과 기존 앱 호출은 말하기 규칙까지 적용한 결과를 받는다. */
    fun step(inp: SituationInput): Pair<List<Utterance>, SituationRecord> {
        val (requests, record) = detect(inp)
        return speech.tick(inp.nowNs, requests, inp.outputPriority) to record
    }

    /** 실시간 TTS는 상태·장애물 안내와 우선순위를 함께 판단할 수 있도록 후보 요청을 받는다. */
    fun detect(inp: SituationInput): Pair<List<SpeechRequest>, SituationRecord> {
        lastStepDepthNs = inp.depth.tCaptureNs
        stepNs = inp.depth.tCaptureNs
        inp.surface?.let { lastStepSurfaceNs = maxOf(lastStepSurfaceNs, it.tCaptureNs) }
        val camW = inp.depth.worldFromCam.translation()
        val tNs = inp.depth.tCaptureNs
        inp.snapshot.floorY?.let { f ->
            heights.addLast(tNs to camW.y - f)
            val windowNs = (heightWindowS * 1e9).toLong()
            while (heights.first().first < tNs - windowNs) heights.removeFirst()
        }
        val s = inp.surface
        if (s != null && s.tCaptureNs > lastSurfaceNs && heights.isNotEmpty()) {
            lastSurfaceNs = s.tCaptureNs
            grid.add(s, s.worldFromCam.translation().y - medianHeight())
        }
        val req = ArrayList<SpeechRequest>()
        val memory = grid.cells(tNs, cfg.gridMemoryS)
        val h = inp.headingW
        var rec = SituationRecord(tNs, env, candidate, 0, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, "", memory.size)
        if (h != null) {
            val user = camW.horizontal()
            val right = BlockLine.rightOf(h)
            // 블록 점은 좌표순으로 정렬해 넣는다: RANSAC은 씨앗을 고정했으므로 점 순서가 결과를 정한다(격자 표의 도는 순서와 무관하게, M65)
            val lines = BlockLines.fit(memory.values.filter { it.major == SurfaceClass.BLOCK.pixel }.sortedWith(compareBy({ it.ix }, { it.iz })).map { it.centerW }, cfg.lineInlierM, cfg.lineMinLengthM, cfg.gridCellM)
                .map { if (it.dir dot h < 0f) it.flipped() else it }
            val ceiling = heights.isNotEmpty() && ceilingSeen(inp.depth, user, h, right, camW.y - medianHeight())
            rec = rec.copy(nLines = lines.size, ceiling = ceiling)
            updateEnvironment(tNs, grid.cells(tNs, cfg.envWindowS), lines, user, h, right, ceiling)
            rec = rec.copy(env = env, envCandidate = candidate)
            if (cfg.envSpeech) env.text?.let { req.add(SpeechRequest("env:$env", it, env.priority, stepNs)) }

            val follow = lines.filter { angleDeg(it.dir, h) <= cfg.lineMaxAngleDeg && abs(it.lateral(user)) <= cfg.envHalfWidthM }
                .minByOrNull { abs(it.lateral(user)) }
            if (follow != null) {
                noteFollow(tNs, user)
                val lat = -follow.lateral(user) // + = 블록이 사용자 오른쪽
                rec = rec.copy(followAngleDeg = angleDeg(follow.dir, h), lateralM = lat, blockYawDeg = yawDeg(follow.dir))
                drift(tNs, lat, req)
                onBlock(inp, follow, user, h, req)
                rec = rec.copy(endAheadM = endAndTurns(follow, lines, memory, user, h, right, req))
            } else {
                driftOverSinceNs = Long.MIN_VALUE
            }
            val (stairsAhead, dir) = stairs(inp, memory, user, h, right, req)
            val dropM = dropAhead(inp.depth, user, h, right)
            if (cfg.dropEnabled && !dropM.isNaN() && dir != "down") req.add(SpeechRequest("drop", "내려가는 곳, ${meters(dropM)}미터", 1, stepNs))
            rec = rec.copy(stairsAheadM = stairsAhead, stairsDir = dir, dropAheadM = dropM)
            if (cfg.bumpSpeech) bump(memory, user, h, right, req)
            vehicles(inp, user, req)
        }
        // M65(사용자 승인 2026-11-21): 노면 격자는 지난 gridMemoryS를 기억하는 지도이고 노면은 움직이지 않으므로, 노면 지도에 기대는 말의 근거 시각은
        // 노면 영상 촬영 시각이 아니라 판단에 쓴 사용자 위치(이 깊이 장)의 시각이다(장애물 소리가 느린 경로 결과 시각으로 나이를 재는 것과 같다).
        // 안전장치: 마지막 노면 영상이 surfaceMaxAgeMs보다 오래되면(모델이 멈춤 등) 노면 지도에 기대는 말은 내지 않는다. 차·사람 다가옴은 노면과 무관하다.
        val fresh = lastSurfaceNs != Long.MIN_VALUE && inp.depth.tCaptureNs - lastSurfaceNs <= cfg.surfaceMaxAgeMs * 1_000_000L
        return (if (fresh) req else req.filter { it.key.startsWith("veh#") || it.key == "ped" }) to rec
    }

    fun reset() {
        lastStepDepthNs = Long.MIN_VALUE
        lastStepSurfaceNs = Long.MIN_VALUE
        grid.reset()
        heights.clear()
        lastSurfaceNs = Long.MIN_VALUE
        env = Environment.UNKNOWN
        candidate = Environment.UNKNOWN
        episodeStart = null
        lastFollowNs = Long.MIN_VALUE
        junctions.clear()
        drifting = false
        driftOverSinceNs = Long.MIN_VALUE
    }

    private fun medianHeight(): Float {
        val hs = heights.map { it.second }.sorted()
        return if (hs.size % 2 == 1) hs[hs.size / 2] else (hs[hs.size / 2 - 1] + hs[hs.size / 2]) / 2f
    }

    /** 앞 띠의 관측된 노면 다수결(블록은 인도에 셈), [SituationConfig.envHoldS] 이어진 뒤에 바꾼다. 머리 위 천장이 보이면 모름(실내). */
    private fun updateEnvironment(tNs: Long, window: Map<Long, SurfaceCell>, lines: List<BlockLine>, user: Vec3, h: Vec3, right: Vec3, ceiling: Boolean) {
        var side = 0; var alley = 0; var road = 0
        for (c in window.values) {
            val rel = c.centerW - user
            val a = rel dot h
            if (a < cfg.envAheadMinM || a > cfg.envAheadMaxM || abs(rel dot right) > cfg.envHalfWidthM) continue
            when (c.major) {
                SurfaceClass.SIDEWALK.pixel, SurfaceClass.BLOCK.pixel -> side++
                SurfaceClass.ALLEY.pixel -> alley++
                SurfaceClass.ROADWAY.pixel -> road++
            }
        }
        val total = side + alley + road
        val cellArea = cfg.gridCellM * cfg.gridCellM
        val top = maxOf(side, alley, road)
        val now = when {
            ceiling -> Environment.UNKNOWN
            total * cellArea < cfg.envMinAreaM2 || top <= cfg.envMajority * total -> Environment.UNKNOWN
            top == side -> if (lines.any { inBand(it, user, h) }) Environment.BLOCK_SIDEWALK else Environment.SIDEWALK
            top == alley -> Environment.ALLEY
            else -> Environment.ROADWAY
        }
        if (now != candidate) {
            candidate = now
            candidateSinceNs = tNs
        }
        if (candidate != env && tNs - candidateSinceNs >= (cfg.envHoldS * 1e9).toLong()) env = candidate
    }

    private fun inBand(l: BlockLine, user: Vec3, h: Vec3): Boolean {
        val a = (l.at(l.tMin) - user) dot h
        val b = (l.at(l.tMax) - user) dot h
        return maxOf(a, b) >= cfg.envAheadMinM && minOf(a, b) <= cfg.envAheadMaxM
    }

    /** 1a: 늘인 블록 선이 몸에서 [SituationConfig.driftOnM] 넘게 [SituationConfig.driftHoldS] 떨어지면 블록이 있는 쪽을 말한다. */
    private fun drift(tNs: Long, lat: Float, req: MutableList<SpeechRequest>) {
        if (drifting && abs(lat) < cfg.driftOffM) drifting = false
        if (abs(lat) > cfg.driftOnM) {
            if (driftOverSinceNs == Long.MIN_VALUE) driftOverSinceNs = tNs
            if (!drifting && tNs - driftOverSinceNs >= (cfg.driftHoldS * 1e9).toLong()) {
                drifting = true
                driftEpisode++
                driftRight = lat > 0f
            }
        } else {
            driftOverSinceNs = Long.MIN_VALUE
        }
        if (drifting) req.add(SpeechRequest("drift#$driftEpisode", if (driftRight) "블록 오른쪽" else "블록 왼쪽", 2, stepNs))
    }

    /** 1c: 장애물 출력의 대표점이 따라가는 블록 선에서 [SituationConfig.onBlockBandM] 안이고 앞에 있으면. */
    private fun onBlock(inp: SituationInput, line: BlockLine, user: Vec3, h: Vec3, req: MutableList<SpeechRequest>) {
        val byId = inp.snapshot.obstacles.associateBy { it.id }
        for (cmd in inp.outputs) {
            if (!cmd.inCorridor) continue
            val o = byId[cmd.obstacleId] ?: continue
            if (((o.repPointW - user).horizontal() dot h) <= 0f) continue
            // 상자 네 모서리(수평)의 블록 선 옆 거리 범위가 블록 띠 ±onBlockBandM과 겹쳐야 블록 위(M51)
            val lats = listOf(o.aabbMinW, o.aabbMaxW, Vec3(o.aabbMinW.x, 0f, o.aabbMaxW.z), Vec3(o.aabbMaxW.x, 0f, o.aabbMinW.z)).map { line.lateral(it) }
            if (lats.min() > cfg.onBlockBandM || lats.max() < -cfg.onBlockBandM) continue
            req.add(SpeechRequest("obj#${o.id}", "블록 위 물체, ${meters(cmd.distanceM)}미터", 2,
                inp.snapshot.tCaptureNs, onBlockObstacleId = o.id))
        }
    }

    /** 잡은 갈림·꺾임 자리(교점, 월드). 번호 = 목록 위치. */
    private val junctions = ArrayList<Vec3>()

    /** 블록 따라가기 구간을 시작한 사용자 위치. 따라갈 선이 [SituationConfig.gridMemoryS](노면 격자가 블록 칸을 기억하는 길이) 동안 없다가 다시 잡히면 새로 정한다. */
    private var episodeStart: Vec3? = null
    private var lastFollowNs = Long.MIN_VALUE

    private fun noteFollow(tNs: Long, user: Vec3) {
        if (lastFollowNs == Long.MIN_VALUE || tNs - lastFollowNs >= (cfg.gridMemoryS * 1e9).toLong()) episodeStart = user
        lastFollowNs = tNs
    }

    /** 교점 [x]가 이미 잡은 자리에서 [SituationConfig.junctionMergeM] 안이면 그 번호, 아니면 새 번호. */
    private fun junctionId(x: Vec3): Int {
        val i = junctions.indexOfFirst { (it - x).horizontal().norm() <= cfg.junctionMergeM }
        if (i >= 0) return i
        junctions += x
        return junctions.size - 1
    }

    /** 1d: 끝(너머를 블록 아닌 노면으로 관측)·꺾임·갈림. 반환값은 끝까지의 앞 거리(없으면 NaN). */
    private fun endAndTurns(line: BlockLine, lines: List<BlockLine>, memory: Map<Long, SurfaceCell>, user: Vec3, h: Vec3, right: Vec3, req: MutableList<SpeechRequest>): Float {
        val endP = line.at(line.tMax)
        val endAhead = (endP - user) dot h
        var turnAtEnd = false
        for (o in lines) {
            if (o === line || angleDeg(o.dir, line.dir).let { minOf(it, 180f - it) } < cfg.turnMinDeg) continue
            // 칠해진 블록 선은 끊기지 않는다(M51): 격자 한 칸 길이마다 평균 한 칸 이상. 흩어진 노란 칠(바닥 원형 표시·연석 칠)을 이은 선을 뺀다
            // (T01: 진짜 선 1 m에 12~35칸, 흩어진 칠을 이은 선 1.4~4.7칸)
            if (o.nInliers < (o.tMax - o.tMin) / cfg.gridCellM) continue
            val x = intersect(line, o) ?: continue
            val tx = line.t(x)
            val to = o.t(x)
            if (tx < line.tMin - cfg.turnJoinM || tx > line.tMax + cfg.turnJoinM || to < o.tMin - cfg.turnJoinM || to > o.tMax + cfg.turnJoinM) continue
            val ahead = (x - user) dot h
            if (ahead < cfg.endMinM || ahead > cfg.endMaxM) continue
            // 선의 시작(M51 사용자): 선형블록은 ㅗ자처럼 점형블록 가로줄에서 시작한다. 따라가지 않다가 선을 새로 잡은 자리에서 말하는 범위(endMaxM) +
            // 같은 자리로 묶는 거리(junctionMergeM) 안의 교점은 갈림이 아니라 시작 구조로 보고 말하지 않는다(T01 160 s: 선을 처음 잡자마자 가로줄을 갈림으로
            // 말함). 앞 거리가 아니라 수평 거리로 잰다: 차도에서 보도로 꺾어 들어오며 선을 잡으면 그때 진행 방향이 흔들린다
            episodeStart?.let { p0 -> if ((x - p0).horizontal().norm() <= cfg.endMaxM + cfg.junctionMergeM) continue }
            val atEnd = line.tMax - tx <= cfg.turnJoinM
            // 갈래 줄기(M51): 교점에서 o를 따라 각 쪽으로 뻗은 길이가 블록 선 최소 길이 이상인 쪽만 센다. 선 시작·끝의 점형블록 판처럼
            // 따라가는 선 양옆으로 짧게 넓은 칠은 줄기가 없어 갈림·꺾임이 아니다(T01 160 s, 노면 모델은 점형·선형을 가르지 않음)
            val arms = listOf(to - o.tMin to line.lateral(o.at(o.tMin)), o.tMax - to to line.lateral(o.at(o.tMax)))
                .filter { (len, _) -> len >= cfg.lineMinLengthM }.map { (_, lat) -> lat }
            if (arms.isEmpty()) continue
            val text = when {
                !atEnd || (arms.any { it > 0f } && arms.any { it < 0f }) -> "블록 갈림"
                arms.maxBy { abs(it) } > 0f -> "블록 오른쪽으로 꺾임"
                else -> "블록 왼쪽으로 꺾임"
            }
            if (atEnd) turnAtEnd = true
            // 자리마다 따로 말한다(M51: 같은 키 "turn"이면 앞 갈림을 말한 뒤 repeatS 동안 다른 갈림도 막혔다, T01 246 s)
            req.add(SpeechRequest("turn#${junctionId(x)}", text, 2, stepNs))
        }
        if (turnAtEnd || endAhead < cfg.endMinM || endAhead > cfg.endMaxM) return if (endAhead in cfg.endMinM..cfg.endMaxM) endAhead else Float.NaN
        var t = cfg.gridCellM
        while (t <= cfg.endBeyondM + 1e-4f) {
            val c = memory[grid.keyAt(line.at(line.tMax + t))] ?: return Float.NaN
            if (c.major !in SurfaceClass.WALKABLE || c.major == SurfaceClass.BLOCK.pixel || c.frames[SurfaceClass.BLOCK.pixel] > 0) return Float.NaN
            t += cfg.gridCellM
        }
        req.add(SpeechRequest("end", "블록 끝, ${meters(endAhead)}미터", 2, stepNs))
        return endAhead
    }

    /**
     * 1e 계단: 통로 폭 안 계단 칸(서로 다른 영상 [SituationConfig.confirmFrames]개 이상). 올라감 = 그 칸에 걸친 장애물(지도 점유의 군집),
     * 내려감 = 가장 가까운 계단 칸보다 [SituationConfig.stairsBeyondM] 넘게 먼 계단 화소의 깊이가 바닥면 교점보다 광선 방향으로
     * [SituationConfig.stairsDepthExcessM] 넘게 먼 몫 > [SituationConfig.stairsDepthFraction]. 둘 다 없으면 방향 없이 "계단".
     */
    private fun stairs(inp: SituationInput, memory: Map<Long, SurfaceCell>, user: Vec3, h: Vec3, right: Vec3, req: MutableList<SpeechRequest>): Pair<Float, String> {
        val cells = inCorridor(memory, SurfaceClass.STAIRS, user, h, right)
        val nearest = cells.minByOrNull { (it.centerW - user) dot h } ?: return Float.NaN to ""
        val ahead = (nearest.centerW - user) dot h
        val up = inp.snapshot.obstacles.any { o -> cells.any { c -> c.centerW.x in o.aabbMinW.x - cfg.gridCellM..o.aabbMaxW.x + cfg.gridCellM && c.centerW.z in o.aabbMinW.z - cfg.gridCellM..o.aabbMaxW.z + cfg.gridCellM } }
        val down = !up && downEvidence(inp.depth, user, h, ahead)
        val key = "stairs"
        when {
            down -> req.add(SpeechRequest(key, "내려가는 계단, ${meters(ahead)}미터", 1, inp.depth.tCaptureNs, level = 2))
            up -> req.add(SpeechRequest(key, "올라가는 계단", 3, stepNs, level = 1))
            else -> req.add(SpeechRequest(key, "계단, ${meters(ahead)}미터", 2, stepNs, level = 1))
        }
        return ahead to if (down) "down" else if (up) "up" else "unknown"
    }

    private fun downEvidence(depth: DepthFrame, user: Vec3, h: Vec3, nearestAheadM: Float): Boolean {
        val camFromWorld = depth.worldFromCam.rigidInverse()
        val k = depth.K
        var valid = 0
        var far = 0
        for (sp in grid.lastPoints) {
            if (sp.cls != SurfaceClass.STAIRS.pixel || ((sp.pW - user) dot h) < nearestAheadM + cfg.stairsBeyondM) continue
            val c = camFromWorld.transformPoint(sp.pW)
            if (c.z <= 0f) continue
            val u = (k.fx * c.x / c.z + k.cx).toInt()
            val v = (k.fy * c.y / c.z + k.cy).toInt()
            if (u < 0 || v < 0 || u >= k.width || v >= k.height) continue
            val zM = (depth.depthMm[v * k.width + u].toInt() and 0xFFFF) / 1000f
            if (zM <= 0f) continue
            valid++
            if ((zM - c.z) * c.norm() / c.z > cfg.stairsDepthExcessM) far++
        }
        return valid >= cfg.stairsMinPoints && far > cfg.stairsDepthFraction * valid
    }

    /**
     * 1e 낙차(M50 고침 한 번, 2026-11-16): 노면 모델이 위에서 내려다본 계단을 계단으로 칠하지 않아(O04) 계단 라벨 없이 깊이로 본다.
     * 마지막 노면 영상의 통로 안 노면 점(바닥면 투영)마다, 그 점으로 가는 광선의 깊이가 바닥 교점보다 멀면 실제 면은 바닥보다
     * 카메라 높이 × (깊이 / 교점 깊이 − 1)만큼 낮다. [SituationConfig.dropMinM] 넘게 낮은 점이 그보다 먼 깊이 있는 점의
     * [SituationConfig.stairsDepthFraction] 넘고 [SituationConfig.stairsMinPoints]개 이상인 가장 가까운 앞 거리(0.1 m 칸 시작, 가까운 쪽). 없으면 NaN.
     */
    private fun dropAhead(depth: DepthFrame, user: Vec3, h: Vec3, right: Vec3): Float {
        if (heights.isEmpty()) return Float.NaN
        val camH = medianHeight()
        val camFromWorld = depth.worldFromCam.rigidInverse()
        val k = depth.K
        val nb = (cfg.envAheadMaxM / cfg.gridCellM).toInt() + 1
        val valid = IntArray(nb + 1)
        val low = IntArray(nb + 1)
        for (sp in grid.lastPoints) {
            if (sp.cls !in SurfaceClass.WALKABLE && sp.cls != SurfaceClass.STAIRS.pixel) continue
            val rel = sp.pW - user
            val a = rel dot h
            if (a <= 0f || a > cfg.envAheadMaxM || abs(rel dot right) > corridorWidthM / 2f) continue
            val c = camFromWorld.transformPoint(sp.pW)
            if (c.z <= 0f) continue
            val u = (k.fx * c.x / c.z + k.cx).toInt()
            val v = (k.fy * c.y / c.z + k.cy).toInt()
            if (u < 0 || v < 0 || u >= k.width || v >= k.height) continue
            val zM = (depth.depthMm[v * k.width + u].toInt() and 0xFFFF) / 1000f
            if (zM <= 0f) continue
            val b = (a / cfg.gridCellM).toInt()
            valid[b]++
            if (camH * (zM / c.z - 1f) > cfg.dropMinM) low[b]++
        }
        for (b in nb - 1 downTo 0) {
            valid[b] += valid[b + 1]
            low[b] += low[b + 1]
        }
        for (b in 0 until nb) {
            if (low[b] > low[b + 1] && low[b] >= cfg.stairsMinPoints && low[b] > cfg.stairsDepthFraction * valid[b]) return b * cfg.gridCellM
        }
        return Float.NaN
    }

    /**
     * 실내 고침(M50 설계 5.2절 미리 정한 후보 "머리 위 천장 점유가 있으면 환경을 모름으로"): 깊이 화소의 월드 점이 바닥에서 통로 높이
     * 넘게 위이고 환경 띠 안이며, 면 법선(이웃 화소 차의 외적)이 수직에서 [SituationConfig.ceilingMaxTiltDeg] 안인 점의 수평 칸 넓이가
     * [SituationConfig.envMinAreaM2] 이상. 법선을 보는 까닭은 골목의 옆 벽·앞 벽(법선이 수평)을 천장으로 잡지 않으려는 것이다.
     */
    private fun ceilingSeen(depth: DepthFrame, user: Vec3, h: Vec3, right: Vec3, floorY: Float): Boolean {
        val k = depth.K
        val w = depth.worldFromCam
        val cosTilt = cos(Math.toRadians(cfg.ceilingMaxTiltDeg.toDouble())).toFloat()
        fun z(u: Int, v: Int) = (depth.depthMm[v * k.width + u].toInt() and 0xFFFF) / 1000f
        val cells = HashSet<Long>()
        for (v in 0 until k.height - 1) for (u in 0 until k.width - 1) {
            val zc = z(u, v)
            if (zc <= 0f) continue
            val c = Projection.backproject(u.toFloat(), v.toFloat(), zc, k)
            val p = w.transformPoint(c)
            if (p.y - floorY <= corridorHeightM) continue
            val rel = p - user
            val a = rel dot h
            if (a < 0f || a > cfg.envAheadMaxM || abs(rel dot right) > cfg.envHalfWidthM) continue
            val zr = z(u + 1, v)
            val zd = z(u, v + 1)
            if (zr <= 0f || zd <= 0f) continue
            val n = w.transformDir((Projection.backproject(u + 1f, v.toFloat(), zr, k) - c) cross (Projection.backproject(u.toFloat(), v + 1f, zd, k) - c))
            val nn = n.norm()
            if (nn <= 0f || abs(n.y) < cosTilt * nn) continue
            cells.add(grid.keyAt(p))
        }
        return cells.size * cfg.gridCellM * cfg.gridCellM >= cfg.envMinAreaM2
    }

    private fun bump(memory: Map<Long, SurfaceCell>, user: Vec3, h: Vec3, right: Vec3, req: MutableList<SpeechRequest>) {
        val nearest = inCorridor(memory, SurfaceClass.BUMP, user, h, right).minByOrNull { (it.centerW - user) dot h } ?: return
        req.add(SpeechRequest("bump", "방지턱", 3, stepNs))
    }

    private fun inCorridor(memory: Map<Long, SurfaceCell>, cls: SurfaceClass, user: Vec3, h: Vec3, right: Vec3): List<SurfaceCell> =
        memory.values.filter { c ->
            val rel = c.centerW - user
            c.frames[cls.pixel] >= cfg.confirmFrames && (rel dot h) > 0f && abs(rel dot right) <= corridorWidthM / 2f
        }

    /**
     * 1g: 통로 안 움직이는 출력이 [SituationConfig.vehicleApproachMps] 넘게 다가오고, 의미 영상에서 그 물체 자리의 사람·차량 라벨 다수가 차량이면 "차 다가옴",
     * 사람이면 "사람 다가옴"(M51 사용자 요청: M50에서 사람은 STOP 소리로만 알리기로 했던 것을 바꿈). 의미 영상이 없으면(실내·옛 녹화) 둘 다 말하지 않는다.
     */
    private fun vehicles(inp: SituationInput, user: Vec3, req: MutableList<SpeechRequest>) {
        val labels = inp.depth.labels ?: return
        val byId = inp.snapshot.obstacles.associateBy { it.id }
        for (cmd in inp.outputs) {
            if (!cmd.inCorridor) continue
            val o = byId[cmd.obstacleId] ?: continue
            val v = o.velocityW ?: continue
            if (!o.moving) continue
            val rel = (o.repPointW - user).horizontal()
            val n = rel.norm()
            if (n < 1e-3f || -(v.horizontal() dot rel) / n <= cfg.vehicleApproachMps) continue
            when (majorityMover(labels, o.aabbMinW, o.aabbMaxW)) {
                SemanticLabel.VEHICLE -> req.add(SpeechRequest("veh#${o.id}", "차 다가옴", 1, inp.snapshot.tCaptureNs))
                // 사람은 키 하나: 한 사람이 발 접점 → 깊이 track 넘김·track 갈림으로 번호가 바뀌어 같은 사람에게 되풀이했다(T 150300·150338·150439).
                // 다가오는 사람이 이어지는 동안 한 번, 사라졌다가 repeatS 뒤에 다시. 사람마다의 위치는 장애물 소리가 맡는다
                SemanticLabel.PERSON -> req.add(SpeechRequest("ped", "사람 다가옴", 1, inp.snapshot.tCaptureNs))
                else -> Unit
            }
        }
    }

    /** 물체 상자 27점에서 사람·차량 라벨 중 많은 쪽(둘 다 없거나 같으면 null). */
    private fun majorityMover(labels: LabelImage, lo: Vec3, hi: Vec3): SemanticLabel? {
        var veh = 0; var person = 0
        for (i in 0..2) for (j in 0..2) for (k in 0..2) {
            val p = Vec3(lo.x + (hi.x - lo.x) * i / 2f, lo.y + (hi.y - lo.y) * j / 2f, lo.z + (hi.z - lo.z) * k / 2f)
            when (labels.pixelAt(p)) {
                SemanticLabel.VEHICLE.pixel -> veh++
                SemanticLabel.PERSON.pixel -> person++
            }
        }
        return when {
            veh > person -> SemanticLabel.VEHICLE
            person > veh -> SemanticLabel.PERSON
            else -> null
        }
    }

    companion object {
        fun angleDeg(a: Vec3, b: Vec3): Float = Math.toDegrees(acos((a.horizontal().normalized() dot b.horizontal().normalized()).coerceIn(-1f, 1f)).toDouble()).toFloat()

        /** [hearspace.core.guidance.Heading.toDeg]와 같은 방위(−Z가 0°, +X가 90°). */
        fun yawDeg(d: Vec3): Float = Math.toDegrees(kotlin.math.atan2(d.x, -d.z).toDouble()).toFloat()

        /** 안내 출력의 진행 방위를 수평 단위 벡터로(모르면 null). PC 재생과 앱(M65)이 같이 쓴다. */
        fun headingOf(g: hearspace.core.types.GuidanceOutput?): Vec3? =
            g?.headingDeg?.takeIf { it.isFinite() }?.let { Math.toRadians(it.toDouble()) }?.let { Vec3(kotlin.math.sin(it).toFloat(), 0f, -cos(it).toFloat()) }

        /** 말할 거리: 1 m 단위 반올림, 1 m 아래로 내리지 않는다. */
        fun meters(m: Float): Int = maxOf(1, m.roundToInt())


        private fun intersect(a: BlockLine, b: BlockLine): Vec3? {
            val den = a.dir.x * b.dir.z - a.dir.z * b.dir.x
            if (abs(den) < 1e-4f) return null
            val dx = b.p0.x - a.p0.x
            val dz = b.p0.z - a.p0.z
            val t = (dx * b.dir.z - dz * b.dir.x) / den
            return a.at(t)
        }
    }
}
