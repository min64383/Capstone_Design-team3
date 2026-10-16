package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.types.DepthFrame
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonNull
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonValue
import hearspace.core.types.MiniJson
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 정답 장면의 요소(M30, IMPROVE_SPEC §9.4). */
enum class Element { FLOOR, WALL, CEILING, OBJECT }

/** 지도 칸 상태. 지금 지도(HITS)는 빈 곳을 저장하지 않으므로 점유가 아니면 모름이다. */
enum class CellState { OCCUPIED, FREE, UNKNOWN }

/** 점유 칸의 정답과의 관계(M30 그림 2). */
enum class VoxelClass { HIT, INSIDE, FALSE }

/**
 * 장면 지표(M30, IMPROVE_SPEC §9.4). 평가 전용이다. 지도 칸을 정답 장면(바닥 y = 0, 정답 상자)과 비교한다. 좌표는 정답 좌표.
 *
 * - 점유 칸은 중심에서 가장 가까운 정답 표면까지 거리로 나눈다: τ 안이면 맞은 칸, 아니고 정답 고체 안이면 속 칸, 둘 다 아니면 헛 칸.
 *   표면 오차는 속 칸도 포함한다(평활 깊이가 벽을 밀어낸 칸은 정답 벽 상자 안에 들어가지만 오차다). 헛 칸은 빈 공간의 칸만이다.
 * - 관측한 표면: 평가하는 깊이 장에서 유효한 화소(간격 [stride])의 광선이 정답 장면과 처음 만나는 점. 그 점에서 τ 안에 점유 칸
 *   중심이 있으면 덮인 것이다(완전성). 광선이 표면 앞까지 지난 칸은 관측한 빈 칸이다(모름 비율·지워진 표면의 분모).
 * τ = √3/2 × 복셀: 표면이 지나는 칸의 중심은 표면에서 반 대각선 안에 있다(기하).
 */
object SceneEval {
    /** 바닥 아래 고체로 보는 두께(m). 정답 벽 두께 관례(0.1 m)와 같다. 그 아래 칸은 헛 칸. */
    const val FLOOR_SLAB_M = 0.1f

    /** 평가 영역의 바닥 아래 깊이(m): 가라앉은 바닥 칸을 헛 칸으로 세려고 영역에 넣는다. */
    private const val BELOW_FLOOR_M = 0.5f

    /** 헛 칸 거리 구간 경계(카메라 수평 거리, m): 0~2, 2~3.5, 3.5~. 3.5 = 통로 길이 `corridor.lengthM` 기본값과 같은 구간. */
    val FALSE_BANDS_M = floatArrayOf(2f, 3.5f)

    /** 헛 칸 높이 구간 경계(정답 바닥 위 m): 바닥 아래(< 0), 바닥 근처(0~0.3), 그 위. 0.3 = 누운 캐리어 높이(M12.0)로 낮은 물체 구간. */
    val FALSE_HEIGHTS_M = floatArrayOf(0f, 0.3f)

    /**
     * 정답 바닥 높이의 불확실성(m): 녹화 세션의 정답 바닥은 측정마다 다르다(M32: RGB 바닥과 깊이 바닥이 E03 152808에서 0.11 m 차이). 이보다
     * 높은 칸만 센 헛 칸 비율을 함께 낸다(바닥 근처 칸의 판정이 정답 바닥 선택에 휘둘리지 않게).
     */
    const val FLOOR_UNCERTAIN_M = 0.15f

    /** F 값을 내는 허용 배수(τ, 2τ, 3τ). */
    val F_MULTIPLES = intArrayOf(1, 2, 3)

    private const val EPS = 1e-4f

    fun tau(voxelM: Float) = sqrt(3f) / 2f * voxelM

    class Solid(val name: String, val element: Element, val min: Vec3, val max: Vec3)

    /** 정답 장면: 고체 상자들 + 바닥(y = 0, 그 아래 [FLOOR_SLAB_M] 고체). [regionMin]~[regionMax] 밖 칸은 보지 않는다. */
    class Scene(val solids: List<Solid>, val regionMin: Vec3, val regionMax: Vec3) {
        fun inRegion(p: Vec3) = p.x in regionMin.x..regionMax.x && p.y in regionMin.y..regionMax.y && p.z in regionMin.z..regionMax.z

        fun inside(p: Vec3) = (p.y < 0f && p.y > -FLOOR_SLAB_M) || solids.any { s ->
            p.x > s.min.x && p.x < s.max.x && p.y > s.min.y && p.y < s.max.y && p.z > s.min.z && p.z < s.max.z
        }

        /** 가장 가까운 정답 표면까지 거리(부호 없음)와 그 요소. */
        fun nearest(p: Vec3): Pair<Float, Element> {
            var best = abs(p.y)
            var el = Element.FLOOR
            for (s in solids) {
                val d = abs(boxDistance(p, s.min, s.max))
                if (d < best) { best = d; el = s.element }
            }
            return best to el
        }

        /** 광선 o + t·d (t > 0)의 첫 교차(t, 요소). [TruthDepth]와 같은 계산(바닥 y = 0 + 축 정렬 상자). */
        fun raycast(o: Vec3, d: Vec3): Pair<Float, Element>? {
            var best = if (abs(d.y) > 1e-9f) (-o.y / d.y).takeIf { it > EPS } else null
            var el = Element.FLOOR
            for (s in solids) {
                val t = slab(o, d, s.min, s.max) ?: continue
                if (best == null || t < best) { best = t; el = s.element }
            }
            return best?.let { it to el }
        }
    }

    /** 정답 상자의 요소: 물체는 물체, 구조물은 얇고(높이 < 0.5 m) 높은(바닥 2.0 m 이상) 것만 천장이고 나머지는 벽. */
    fun element(o: TruthObstacle): Element = when {
        o.kind == TruthKind.OBJECT -> Element.OBJECT
        o.maxM.y - o.minM.y < 0.5f && o.minM.y >= 2.0f -> Element.CEILING
        else -> Element.WALL
    }

    /**
     * [truth]의 정답 장면. 평가 영역은 구조물(벽·천장) 상자를 모두 덮는 상자의 수평 범위, 높이 −[BELOW_FLOOR_M]~[heightM].
     * 구조물이 없으면 정답이 장면 전체를 덮지 않으므로 null(평가하지 않음, M30 설계 3.1절).
     */
    fun scene(truth: GroundTruth, heightM: Float): Scene? {
        val structures = truth.obstacles.filter { it.kind == TruthKind.STRUCTURE }
        if (structures.isEmpty()) return null
        val solids = truth.obstacles.map { Solid(it.name, element(it), it.minM, it.maxM) }
        return Scene(
            solids,
            Vec3(structures.minOf { it.minM.x }, -BELOW_FLOOR_M, structures.minOf { it.minM.z }),
            Vec3(structures.maxOf { it.maxM.x }, heightM, structures.maxOf { it.maxM.z }),
        )
    }

    /** 표본 하나(깊이 한 장 시점)의 셈. 세션 값은 표본을 모아 [Pool]로 낸다. */
    class Sample(
        val tNs: Long,
        val occupied: Int,
        val inside: Int,
        val false_: Int,
        val falseByBand: IntArray,
        /** 점유 칸의 표면 거리와 요소(속 칸 포함: 정답 고체 안으로 들어간 칸도 표면에서 떨어진 만큼 오차다). */
        val dist: FloatArray,
        val distEl: Array<Element>,
        /** 관측한 표면 칸 수·덮인 수(요소 순서). */
        val visible: IntArray,
        val covered: IntArray,
        /** 관측 영역 칸(표면 + 빈 칸) 수와 그중 모름, 관측한 표면 중 빈 곳. */
        val observedCells: Int,
        val unknownCells: Int,
        val erasedSurface: Int,
        /** 맞은 칸의 [정답 요소][지도 라벨(요소 순서, 마지막 = 라벨 없음)]. */
        val confusion: Array<IntArray>,
        /** 관측했는데 덮이지 않은 표면 점(정답 좌표)과 요소(viewer 표시용). */
        val missed: List<Pair<Vec3, Element>> = emptyList(),
        /** 헛 칸의 높이 구간([FALSE_HEIGHTS_M]: 바닥 아래, 바닥 근처, 그 위)과 지워진 표면의 요소별 수(M32 원인 분해). */
        val falseByHeight: IntArray = IntArray(FALSE_HEIGHTS_M.size + 1),
        val erasedByElement: IntArray = IntArray(Element.entries.size),
        /** [FLOOR_UNCERTAIN_M]보다 높은 점유 칸과 그중 헛 칸. */
        val occupiedAboveBand: Int = 0,
        val falseAboveBand: Int = 0,
    )

    /**
     * 표본 하나를 센다.
     * @param occupiedW 점유 칸 중심(월드). [labels]는 같은 순서의 지도 라벨(null = 라벨 없음). 지금 지도는 라벨이 없어 모두 물체로 준다.
     * @param stateOf 월드 점의 지도 칸 상태.
     * @param depthMm 지도에 쓴 깊이(앞단 뒤). 0인 화소는 관측하지 않은 것이다.
     * @param radiusM 카메라에서 이 수평 거리 밖은 보지 않는다(`map.radiusM`, 지도가 그 밖 칸을 지운다).
     */
    fun sample(
        scene: Scene,
        al: Alignment,
        voxelM: Float,
        radiusM: Float,
        occupiedW: List<Vec3>,
        labels: List<Element?>,
        stateOf: (Vec3) -> CellState,
        depth: DepthFrame,
        depthMm: ShortArray,
        stride: Int,
    ): Sample {
        val tau = tau(voxelM)
        val cam = al.toTruth(depth.worldFromCam.translation())
        fun near(p: Vec3) = hypot(p.x - cam.x, p.z - cam.z) <= radiusM

        // 점유 칸 분류
        var occ = 0
        var inside = 0
        var falseN = 0
        val bands = IntArray(FALSE_BANDS_M.size + 1)
        val heights = IntArray(FALSE_HEIGHTS_M.size + 1)
        var occAbove = 0
        var falseAbove = 0
        val dist = ArrayList<Float>()
        val distEl = ArrayList<Element>()
        val confusion = Array(Element.entries.size) { IntArray(Element.entries.size + 1) }
        val grid = HashMap<Long, MutableList<Vec3>>()
        for ((i, w) in occupiedW.withIndex()) {
            val p = al.toTruth(w)
            // 덮임 판정용 격자에는 영역·반경 경계 밖 τ까지의 칸도 넣는다(경계 안 표면점을 경계 밖 칸이 덮는 경우)
            if (hypot(p.x - cam.x, p.z - cam.z) <= radiusM + tau) grid.getOrPut(key(p, voxelM)) { ArrayList(1) } += p
            if (!scene.inRegion(p) || !near(p)) continue
            occ++
            if (p.y > FLOOR_UNCERTAIN_M) occAbove++
            val (d, el) = scene.nearest(p)
            when (classify(scene, p, tau)) {
                VoxelClass.HIT -> confusion[el.ordinal][labels[i]?.ordinal ?: Element.entries.size]++
                VoxelClass.INSIDE -> inside++
                VoxelClass.FALSE -> {
                    falseN++
                    if (p.y > FLOOR_UNCERTAIN_M) falseAbove++
                    val r = hypot(p.x - cam.x, p.z - cam.z)
                    bands[FALSE_BANDS_M.indexOfFirst { r < it }.let { if (it < 0) FALSE_BANDS_M.size else it }]++
                    heights[FALSE_HEIGHTS_M.indexOfFirst { p.y < it }.let { if (it < 0) FALSE_HEIGHTS_M.size else it }]++
                }
            }
            dist += d
            distEl += el
        }

        // 관측한 표면과 빈 칸(간격 stride 화소의 광선)
        val k = depth.K
        val surface = HashMap<Long, Pair<Vec3, Element>>()
        val free = HashSet<Long>()
        for (v in stride / 2 until k.height step stride) for (u in stride / 2 until k.width step stride) {
            if (depthMm[v * k.width + u].toInt() == 0) continue
            val dir = al.toTruthDir(depth.worldFromCam.transformDir(Vec3((u - k.cx) / k.fx, (v - k.cy) / k.fy, 1f)))
            val (t, el) = scene.raycast(cam, dir) ?: continue
            if (t > TruthDepth.MAX_DEPTH_M) continue
            val hit = cam + dir * t
            if (!scene.inRegion(hit) || !near(hit)) continue
            surface.putIfAbsent(key(hit, voxelM), hit to el)
            val len = dir.norm()
            var s = voxelM / len
            while (s * len < t * len - tau) {
                val q = cam + dir * s
                // 스치는 광선이 지나는 표면 칸(중심이 표면에서 τ 안)은 빈 칸이 아니다
                if (scene.inRegion(q)) key(q, voxelM).let { kq -> if (kq !in free && scene.nearest(center(kq, voxelM)).first > tau) free += kq }
                s += voxelM / len
            }
        }
        free.removeAll(surface.keys)

        val visible = IntArray(Element.entries.size)
        val covered = IntArray(Element.entries.size)
        var unknown = 0
        var erased = 0
        val erasedEl = IntArray(Element.entries.size)
        val missed = ArrayList<Pair<Vec3, Element>>()
        for ((p, el) in surface.values) {
            visible[el.ordinal]++
            // 덮인 표면은 점유로 본다. 표면이 칸 경계(예: 바닥 y = 0)에 놓이면 든 칸이 반올림으로 갈리므로 덮임으로 판정한다
            if (anyWithin(grid, p, tau, voxelM)) { covered[el.ordinal]++; continue }
            missed += p to el
            when (stateOf(al.toWorld(p))) {
                CellState.UNKNOWN -> unknown++
                CellState.FREE -> { erased++; erasedEl[el.ordinal]++ }
                CellState.OCCUPIED -> Unit
            }
        }
        for (kf in free) if (stateOf(al.toWorld(center(kf, voxelM))) == CellState.UNKNOWN) unknown++
        return Sample(
            depth.tCaptureNs, occ, inside, falseN, bands, dist.toFloatArray(), distEl.toTypedArray(),
            visible, covered, surface.size + free.size, unknown, erased, confusion, missed, heights, erasedEl, occAbove, falseAbove,
        )
    }

    /** 점유 칸 하나의 분류. */
    fun classify(scene: Scene, p: Vec3, tau: Float): VoxelClass = when {
        scene.nearest(p).first <= tau -> VoxelClass.HIT
        scene.inside(p) -> VoxelClass.INSIDE
        else -> VoxelClass.FALSE
    }

    /** 지금 지도(점유만 저장)의 칸 상태: 점유 칸 중심 [occupiedW]가 든 칸이면 점유, 아니면 모름. */
    fun occupancyOnly(occupiedW: List<Vec3>, voxelM: Float): (Vec3) -> CellState {
        val keys = occupiedW.mapTo(HashSet()) { key(it, voxelM) }
        return { p -> if (key(p, voxelM) in keys) CellState.OCCUPIED else CellState.UNKNOWN }
    }

    /** 3상태 지도(M32 TRISTATE)의 칸 상태: 점유 칸 중심 [occupiedW], 빈 곳 칸 중심 [freeW], 나머지는 모름. */
    fun threeState(occupiedW: List<Vec3>, freeW: List<Vec3>, voxelM: Float): (Vec3) -> CellState {
        val occ = occupiedW.mapTo(HashSet()) { key(it, voxelM) }
        val free = freeW.mapTo(HashSet()) { key(it, voxelM) }
        return { p ->
            val k = key(p, voxelM)
            when (k) {
                in occ -> CellState.OCCUPIED
                in free -> CellState.FREE
                else -> CellState.UNKNOWN
            }
        }
    }

    /** 여러 표본을 모은 세션 값. */
    class Pool(val samples: List<Sample>, val voxelM: Float) {
        private val tau = tau(voxelM)
        val occupied = samples.sumOf { it.occupied }
        val inside = samples.sumOf { it.inside }
        val false_ = samples.sumOf { it.false_ }
        private val dist = samples.flatMap { it.dist.asList() }
        private val distEl = samples.flatMap { it.distEl.asList() }

        fun falseRatio() = ratio(false_, occupied)

        /** 바닥에 맞은 칸(가장 가까운 정답이 바닥이고 τ 안): 지도에 바닥을 넣으면 쉬운 맞은 칸이 늘어 비율 지표가 저절로 좋아진다(M32 설계 3.6절). */
        private val floorHit = dist.indices.count { distEl[it] == Element.FLOOR && dist[it] <= tau }
        private val distNoFloor = dist.filterIndexed { i, d -> !(distEl[i] == Element.FLOOR && d <= tau) }

        fun falseRatioNoFloor() = ratio(false_, occupied - floorHit)

        fun distQuantileNoFloor(q: Double): Double? = distNoFloor.sorted().let { if (it.isEmpty()) null else it[((it.size - 1) * q).toInt()].toDouble() }

        fun falseRatioBand(b: Int): Double = ratio(samples.sumOf { it.falseByBand[b] }, occupied)

        /** 점유 칸 표면 거리의 분위수(요소 null = 전체). 칸이 없으면 null. */
        fun distQuantile(q: Double, el: Element? = null): Double? {
            val d = (if (el == null) dist else dist.filterIndexed { i, _ -> distEl[i] == el }).sorted()
            return if (d.isEmpty()) null else d[((d.size - 1) * q).toInt()].toDouble()
        }

        fun visible(el: Element) = samples.sumOf { it.visible[el.ordinal] }

        fun covered(el: Element) = samples.sumOf { it.covered[el.ordinal] }

        fun completeness(vararg els: Element): Double? = els.sumOf { visible(it) }.let { v -> if (v == 0) null else els.sumOf { covered(it) }.toDouble() / v }

        /** 정확도(1 − 표면에서 m·τ 넘게 떨어진 칸 비율)와 완전성(τ 기준, 모든 요소)의 조화 평균. */
        fun fScore(m: Int): Double? {
            val r = completeness(*Element.entries.toTypedArray()) ?: return null
            if (occupied == 0) return null
            val p = 1.0 - dist.count { it > m * tau }.toDouble() / occupied
            return if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
        }

        fun unknownRatio() = ratio(samples.sumOf { it.unknownCells }, samples.sumOf { it.observedCells })

        fun erasedRatio() = ratio(samples.sumOf { it.erasedSurface }, Element.entries.sumOf { visible(it) })

        fun confusion(): Array<IntArray> = Array(Element.entries.size) { t -> IntArray(Element.entries.size + 1) { m -> samples.sumOf { it.confusion[t][m] } } }

        /** 맞은 칸 중 지도 라벨이 정답 요소와 같은 비율. */
        fun labelAgreement(): Double? {
            val c = confusion()
            val all = c.sumOf { it.sum() }
            return if (all == 0) null else Element.entries.sumOf { c[it.ordinal][it.ordinal] }.toDouble() / all
        }

        fun toJson(extra: Map<String, JsonValue> = emptyMap()): String {
            fun num(x: Double?) = x?.takeIf { !it.isNaN() }?.let { JsonNumber(Math.round(it * 10000.0) / 10000.0) } ?: JsonNull
            fun int(x: Int) = JsonNumber(x.toDouble())
            val byEl = linkedMapOf<String, JsonValue>()
            for (el in Element.entries) {
                byEl[el.name] = JsonObject(
                    linkedMapOf(
                        "surfaceErrorP50M" to num(distQuantile(0.5, el)),
                        "surfaceErrorP90M" to num(distQuantile(0.9, el)),
                        "visible" to int(visible(el)),
                        "covered" to int(covered(el)),
                        "completeness" to num(completeness(el)),
                        "erased" to int(samples.sumOf { it.erasedByElement[el.ordinal] }),
                    ),
                )
            }
            val c = confusion()
            val fields = linkedMapOf<String, JsonValue>(
                "voxelSizeM" to num(voxelM.toDouble()),
                "tauM" to num(tau.toDouble()),
                "samples" to int(samples.size),
                "occupied" to int(occupied),
                "inside" to int(inside),
                "false" to int(false_),
                "falseRatio" to num(falseRatio()),
                "falseRatioNoFloor" to num(falseRatioNoFloor()),
                "insideRatioNoFloor" to num(ratio(inside, occupied - floorHit)),
                "surfaceErrorP50NoFloorM" to num(distQuantileNoFloor(0.5)),
                "surfaceErrorP90NoFloorM" to num(distQuantileNoFloor(0.9)),
                "floorHit" to int(floorHit),
                "falseRatioByBand" to JsonArray((0..FALSE_BANDS_M.size).map { num(falseRatioBand(it)) }),
                "falseRatioAboveFloorBand" to num(ratio(samples.sumOf { it.falseAboveBand }, samples.sumOf { it.occupiedAboveBand })),
                "falseByHeight" to JsonArray((0..FALSE_HEIGHTS_M.size).map { b -> int(samples.sumOf { it.falseByHeight[b] }) }),
                "insideRatio" to num(ratio(inside, occupied)),
                "surfaceErrorP50M" to num(distQuantile(0.5)),
                "surfaceErrorP90M" to num(distQuantile(0.9)),
                "completenessWallObject" to num(completeness(Element.WALL, Element.OBJECT)),
                "completenessAll" to num(completeness(*Element.entries.toTypedArray())),
                "fScore" to JsonObject(F_MULTIPLES.associate { "${it}tau" to num(fScore(it)) }.toMap(LinkedHashMap())),
                "unknownRatio" to num(unknownRatio()),
                "erasedRatio" to num(erasedRatio()),
                "labelAgreement" to num(labelAgreement()),
                "labelConfusion" to JsonObject(
                    Element.entries.associate { t ->
                        t.name to JsonObject((Element.entries.map { it.name } + "NONE").mapIndexed { m, n -> n to int(c[t.ordinal][m]) }.toMap(LinkedHashMap<String, JsonValue>()))
                    }.toMap(LinkedHashMap()),
                ),
                "byElement" to JsonObject(byEl),
            )
            fields.putAll(extra)
            fields["perSample"] = JsonArray(
                samples.map { s ->
                    JsonObject(
                        linkedMapOf(
                            "tNs" to JsonNumber(s.tNs.toDouble()),
                            "occupied" to int(s.occupied),
                            "false" to int(s.false_),
                            "visible" to int(s.visible.sum()),
                            "covered" to int(s.covered.sum()),
                        ),
                    )
                },
            )
            return MiniJson.write(JsonObject(fields))
        }
    }

    private fun ratio(a: Int, b: Int): Double = if (b == 0) Double.NaN else a.toDouble() / b

    private fun anyWithin(grid: Map<Long, List<Vec3>>, p: Vec3, r: Float, voxelM: Float): Boolean {
        val ix = floor(p.x / voxelM).toInt()
        val iy = floor(p.y / voxelM).toInt()
        val iz = floor(p.z / voxelM).toInt()
        val r2 = r * r
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val cell = grid[pack(ix + dx, iy + dy, iz + dz)] ?: continue
            if (cell.any { (it - p).let { d -> d dot d } <= r2 }) return true
        }
        return false
    }

    /** 점 [p]가 든 [voxelM] 격자 칸의 키. */
    fun key(p: Vec3, voxelM: Float): Long = pack(floor(p.x / voxelM).toInt(), floor(p.y / voxelM).toInt(), floor(p.z / voxelM).toInt())

    private fun center(key: Long, voxelM: Float): Vec3 {
        fun c(shift: Int) = (((key shr shift) and MASK).toInt() - OFFSET + 0.5f) * voxelM
        return Vec3(c(2 * BITS), c(BITS), c(0))
    }

    private const val BITS = 21
    private const val MASK = (1L shl BITS) - 1
    private const val OFFSET = 1 shl (BITS - 1)
    private fun pack(ix: Int, iy: Int, iz: Int): Long =
        ((ix + OFFSET).toLong() shl (2 * BITS)) or ((iy + OFFSET).toLong() shl BITS) or (iz + OFFSET).toLong()

    /** 점과 축 정렬 상자의 부호 거리(밖 +, 안 −). */
    private fun boxDistance(p: Vec3, lo: Vec3, hi: Vec3): Float {
        val qx = max(lo.x - p.x, p.x - hi.x)
        val qy = max(lo.y - p.y, p.y - hi.y)
        val qz = max(lo.z - p.z, p.z - hi.z)
        val out = sqrt(max(qx, 0f).let { it * it } + max(qy, 0f).let { it * it } + max(qz, 0f).let { it * it })
        return if (out > 0f) out else max(qx, max(qy, qz))
    }

    private fun slab(o: Vec3, d: Vec3, lo: Vec3, hi: Vec3): Float? {
        var t0 = -Float.MAX_VALUE
        var t1 = Float.MAX_VALUE
        for (a in 0..2) {
            val oa = o.c(a)
            val da = d.c(a)
            if (abs(da) < 1e-12f) {
                if (oa < lo.c(a) || oa > hi.c(a)) return null
            } else {
                var ta = (lo.c(a) - oa) / da
                var tb = (hi.c(a) - oa) / da
                if (ta > tb) ta = tb.also { tb = ta }
                t0 = max(t0, ta)
                t1 = min(t1, tb)
                if (t0 > t1) return null
            }
        }
        return when {
            t0 > EPS -> t0
            t1 > EPS -> t1
            else -> null
        }
    }

    private fun Vec3.c(a: Int) = when (a) { 0 -> x; 1 -> y; else -> z }
}
