package hearspace.core.synth

import hearspace.core.geometry.Vec3
import hearspace.core.truth.Alignment
import hearspace.core.types.HeightClass
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 무작위 합성 장면(M30, IMPROVE_SPEC §12): 시드 하나로 공간·물체·보행을 고른다(M30 설계 표 4). 범위는 실내 보행 공간을 덮도록 정한
 * 설계값이며 녹화 결과를 보고 정하지 않았다. 열화 강도 s는 [noise]로 따로 준다(s = 0은 정답 깊이).
 *
 * 월드: 바닥 y = 0, 보행은 시작점(x = [RandomScene.walkX], z = 0)에서 −z 방향(합성 보행 `headingDeg` 0).
 */
object RandomScenes {
    /** s = 1의 깊이 거리 제곱 잡음 k(σ = k·Z², m): M30 오차 모델, 평활 깊이 바닥 0.0091·벽 0.0089(`depth_model.json`). */
    const val QUAD_K = 0.009f

    /** s = 1의 가장자리 평활·배경 당김 폭(화소): 실측 경사 폭 p50 2~4화소(M13.0)의 가운데. */
    const val EDGE_PX = 3

    private const val WALL_T = 0.1f
    private const val GAP = 0.1f
    private const val STAND_S = 1f
    private const val STOP_BEFORE_M = 0.8f
    private const val OPEN_WALK_M = 6f

    enum class Kind { LOW, BODY, HEAD, POLE }
    enum class Place { ON_LINE, SIDE, AGAINST_WALL }

    data class Item(val kind: Kind, val place: Place, val min: Vec3, val max: Vec3)

    /** 장면 하나와 그 요약(분석용). */
    data class RandomScene(
        val seed: Long,
        val room: Boolean,
        val widthM: Float,
        val endWallM: Float?,
        val ceilingM: Float?,
        val walkX: Float,
        val items: List<Item>,
        val scene: Scene,
        val walk: Walk,
    ) {
        /** 정답 정렬(합성 월드 → 정답 좌표): 원점 = 시작 때 머리 아래 바닥, 보행선 −z, 바닥 y 0. */
        fun alignment(): Alignment {
            val (_, head) = SyntheticGenerator.cameraPose(walk, 0f)
            return Alignment(head.positionW.x.toDouble(), head.positionW.z.toDouble(), 0.0, -1.0, 0.0, 0.0, false, false, 0, 0.0, 0.0)
        }

        fun summaryJson(): String = buildString {
            append("{\"seed\": $seed, \"room\": $room, \"widthM\": ${r(widthM)}, \"endWallM\": ${endWallM?.let(::r)}, \"ceilingM\": ${ceilingM?.let(::r)}, ")
            append("\"walkX\": ${r(walkX)}, \"speedMps\": ${r(walk.speedMps)}, \"pitchDownDeg\": ${r(walk.pitchDownDeg)}, \"durationS\": ${r(walk.durationS)}, ")
            append("\"items\": [")
            append(items.joinToString(", ") { "{\"kind\": \"${it.kind}\", \"place\": \"${it.place}\", \"min\": ${v(it.min)}, \"max\": ${v(it.max)}}" })
            append("]}")
        }

        private fun r(x: Float) = (x * 1000).roundToInt() / 1000.0
        private fun v(p: Vec3) = "[${r(p.x)}, ${r(p.y)}, ${r(p.z)}]"
    }

    /** 열화 강도 [s]의 잡음: 깊이 σ = s·[QUAD_K]·Z², 가장자리 평활·배경 당김 round(s·[EDGE_PX])화소. */
    fun noise(s: Float, seed: Long) = Noise(
        seed = seed,
        depthQuadK = s * QUAD_K,
        edgeSmoothPx = (s * EDGE_PX).roundToInt(),
        edgePullPx = (s * EDGE_PX).roundToInt(),
    )

    fun generate(seed: Long): RandomScene {
        val rnd = Random(seed)
        fun u(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()
        val room = rnd.nextFloat() < 0.3f
        val w = if (room) u(2.5f, 5.0f) else u(1.0f, 2.5f)
        val endWall = if (rnd.nextFloat() < 0.3f) null else u(3f, 8f)
        val ceiling = if (rnd.nextFloat() < 0.5f) null else u(2.3f, 3.0f)
        val wallH = ceiling ?: 2.6f
        val lenM = endWall ?: (OPEN_WALK_M + 4f)
        val half = w / 2
        val walkX = u(-1f, 1f) * max(0f, half - 0.5f) * 0.5f

        // 구조물: 옆 벽은 시작점 1 m 뒤부터 끝까지
        val sceneItems = ArrayList<SceneItem>()
        sceneItems += SceneItem("floor", HorizontalPlane(0f), obstacle = false)
        fun structure(name: String, mn: Vec3, mx: Vec3) = SceneItem(name, Box(mn, mx), obstacle = false, structure = true)
        sceneItems += structure("wall_left", Vec3(-half - WALL_T, 0f, -lenM - WALL_T), Vec3(-half, wallH, 1f))
        sceneItems += structure("wall_right", Vec3(half, 0f, -lenM - WALL_T), Vec3(half + WALL_T, wallH, 1f))
        if (endWall != null) sceneItems += structure("wall_end", Vec3(-half - WALL_T, 0f, -endWall - WALL_T), Vec3(half + WALL_T, wallH, -endWall))
        if (ceiling != null) sceneItems += structure("ceiling", Vec3(-half - WALL_T, ceiling, -lenM - WALL_T), Vec3(half + WALL_T, ceiling + WALL_T, 1f))

        // 물체: 겹치지 않게(수평 간격 GAP) 최대 50번 다시 뽑는다
        val items = ArrayList<Item>()
        repeat(rnd.nextInt(0, 5)) {
            for (attempt in 0 until 50) {
                val kind = Kind.entries[rnd.nextInt(Kind.entries.size)]
                val place = Place.entries[rnd.nextInt(Place.entries.size)]
                val (sx, sz) = when (kind) {
                    Kind.POLE -> u(0.08f, 0.15f).let { it to it }
                    Kind.HEAD -> u(0.4f, 1.0f) to u(0.2f, 0.5f)
                    else -> u(0.3f, 0.8f) to u(0.3f, 0.6f)
                }
                val (y0, y1) = when (kind) {
                    Kind.LOW -> 0f to u(0.10f, 0.35f)
                    Kind.BODY -> 0f to u(0.5f, 1.2f)
                    Kind.HEAD -> u(1.4f, 1.9f).let { it to it + u(0.1f, 0.3f) }
                    Kind.POLE -> 0f to wallH
                }
                if (sx > w - 0.2f) continue
                val cx = when (place) {
                    Place.ON_LINE -> walkX + u(-0.1f, 0.1f)
                    Place.SIDE -> (if (rnd.nextBoolean()) 1f else -1f).let { side -> walkX + side * (0.4f + sx / 2 + u(0f, max(0f, half - 0.4f - sx))) }
                    Place.AGAINST_WALL -> (if (rnd.nextBoolean()) 1f else -1f) * (half - u(0f, 0.1f) - sx / 2)
                }.coerceIn(-half + sx / 2, half - sx / 2)
                val zNear = u(1.5f, max(1.6f, (endWall ?: lenM) - 0.5f - sz))
                val mn = Vec3(cx - sx / 2, y0, -zNear - sz)
                val mx = Vec3(cx + sx / 2, y1, -zNear)
                val clash = items.any { o ->
                    mn.x < o.max.x + GAP && mx.x > o.min.x - GAP && mn.z < o.max.z + GAP && mx.z > o.min.z - GAP
                }
                if (clash) continue
                items += Item(kind, place, mn, mx)
                break
            }
        }
        for ((i, it) in items.withIndex()) {
            val cls = when (it.kind) {
                Kind.LOW -> HeightClass.FLOOR
                Kind.BODY, Kind.POLE -> HeightClass.BODY
                Kind.HEAD -> HeightClass.HEAD
            }
            sceneItems += SceneItem("${it.kind.name.lowercase()}_$i", Box(it.min, it.max), obstacle = true, expectedClass = cls)
        }

        // 보행: 보행선 위 첫 물체(머리 폭 ±0.3 m 안) 또는 끝 벽 [STOP_BEFORE_M] 앞에서 멈춘다. 머리 높이 판도 막는다.
        val speed = u(0.5f, 1.2f)
        val pitch = u(5f, 25f)
        val headBehindM = 0.3f // 머리는 카메라 0.3 m 뒤(합성 기본 파지): 머리가 앞면 STOP_BEFORE_M 앞에 서려면 카메라 거리 + 0.3 m를 걷는다
        val blockers = items.filter { abs((it.min.x + it.max.x) / 2 - walkX) < 0.3f + (it.max.x - it.min.x) / 2 }.map { -it.max.z }
        val stopAt = (blockers + listOfNotNull(endWall)).minOrNull()
        val walkM = max(0.5f, min(stopAt?.let { it - STOP_BEFORE_M + headBehindM } ?: OPEN_WALK_M, OPEN_WALK_M))
        val walk = Walk(
            startCameraW = Vec3(walkX, 1f, 0f),
            speedMps = speed,
            standS = STAND_S,
            durationS = STAND_S + walkM / speed + 0.5f,
            pitchDownDeg = pitch,
            bobAmpM = 0.02f,
            wristYawAmpDeg = 10f,
        )
        return RandomScene(seed, room, w, endWall, ceiling, walkX, items, Scene(sceneItems), walk)
    }
}
