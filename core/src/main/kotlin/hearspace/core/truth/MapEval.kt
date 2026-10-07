package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.MiniJson

/**
 * 지도(복셀)가 정답 구조물(벽)을 얼마나 얇고 제자리에 그리는지 (M13, `map_eval.json`). 평가 전용이다(정렬이 세션 전체의 바닥
 * 중앙값을 쓰므로 실행 중 안내에는 쓰지 않는다). 좌표는 정답 좌표.
 *
 * 정답 v2의 `kind: structure` 중 한 축이 얇은(< 0.5 m) 수직 면(옆 벽·끝 벽)마다, 사람이 걷는 쪽 면(앞면) 근처의 점유 복셀을
 * 면에 나란한 두 축(벽 방향, 높이)의 복셀 칸으로 묶어 본다.
 * - 두께: 칸마다 면에 수직 방향으로 쌓인 복셀 수 × 복셀 크기. 실제 벽(5~10 cm)이면 1~2칸이다.
 * - 앞 치우침: 칸마다 가장 앞(사람 쪽)에 있는 복셀이 실제 앞면보다 앞으로 나온 거리. 막·배경 끌림이 벽을 사람 쪽으로 당긴 양.
 */
object MapEval {
    /** 면 앞쪽으로 이만큼, 뒤쪽으로 [BEHIND_M]까지를 그 벽의 복셀로 본다(m). */
    private const val FRONT_M = 0.8f
    private const val BEHIND_M = 0.3f

    /** 벽 끝(모서리)과 바닥·천장 근처는 다른 면과 섞이므로 뺀다(m). */
    private const val END_MARGIN_M = 0.3f
    private val HEIGHT_M = 0.3f..1.8f

    data class Wall(val name: String, val columns: Int, val thicknessP50: Float, val thicknessP90: Float, val frontOffsetP50: Float)

    /** 정답 좌표의 점유 복셀 중심 [voxels]로 구조물마다 두께·앞 치우침. 해당 칸이 없는 벽은 뺀다. */
    fun walls(voxels: List<Vec3>, truth: GroundTruth, voxelM: Float): List<Wall> = truth.obstacles.mapNotNull { o ->
        if (o.kind != TruthKind.STRUCTURE) return@mapNotNull null
        val ex = o.maxM.x - o.minM.x
        val ez = o.maxM.z - o.minM.z
        val normalX = ex < 0.5f && ez >= 0.5f
        if (!normalX && !(ez < 0.5f && ex >= 0.5f)) return@mapNotNull null
        // 앞면과 앞쪽 부호: 옆 벽은 복도 가운데(x = 0) 쪽, 끝 벽은 시작 쪽(z 작은 쪽)
        val (face, sign) = when {
            normalX && (o.minM.x + o.maxM.x) > 0f -> o.minM.x to -1f
            normalX -> o.maxM.x to 1f
            else -> o.minM.z to -1f
        }
        val cols = HashMap<Pair<Int, Int>, MutableList<Float>>()
        for (p in voxels) {
            if (p.y !in HEIGHT_M) continue
            val along = if (normalX) p.z else p.x
            val lo = if (normalX) o.minM.z else o.minM.x
            val hi = if (normalX) o.maxM.z else o.maxM.x
            if (along < lo + END_MARGIN_M || along > hi - END_MARGIN_M) continue
            if (normalX && p.z < 0f) continue // 시작 표시 뒤는 보지 않은 곳
            val s = ((if (normalX) p.x else p.z) - face) * sign // + = 앞면보다 사람 쪽
            if (s > FRONT_M || s < -BEHIND_M) continue
            cols.getOrPut(Pair((along / voxelM).toInt(), (p.y / voxelM).toInt())) { ArrayList() } += s
        }
        if (cols.isEmpty()) return@mapNotNull null
        val thick = cols.values.map { it.size * voxelM }.sorted()
        val front = cols.values.map { it.max() }.sorted()
        Wall(o.name, cols.size, pct(thick, 0.5), pct(thick, 0.9), pct(front, 0.5))
    }

    private fun pct(sorted: List<Float>, q: Double) = sorted[((sorted.size - 1) * q).toInt()]

    /** 표본 단계들(시각, 정답 좌표 복셀)의 벽 지표: 마지막 단계와, 표본 단계 값의 중앙값. */
    fun toJson(samples: List<Pair<Long, List<Wall>>>, voxelM: Float): String {
        fun num(x: Float) = JsonNumber(Math.round(x * 1000.0) / 1000.0)
        fun wallJson(w: Wall) = JsonObject(
            linkedMapOf(
                "name" to JsonString(w.name),
                "columns" to JsonNumber(w.columns.toDouble()),
                "thicknessP50M" to num(w.thicknessP50),
                "thicknessP90M" to num(w.thicknessP90),
                "frontOffsetP50M" to num(w.frontOffsetP50),
            ),
        )
        val names = samples.flatMap { s -> s.second.map { it.name } }.distinct()
        val median = names.map { n ->
            val ws = samples.mapNotNull { s -> s.second.firstOrNull { it.name == n } }
            fun med(f: (Wall) -> Float) = ws.map(f).sorted().let { it[(it.size - 1) / 2] }
            Wall(n, med { it.columns.toFloat() }.toInt(), med { it.thicknessP50 }, med { it.thicknessP90 }, med { it.frontOffsetP50 })
        }
        val fields = linkedMapOf<String, JsonValue>(
            "voxelSizeM" to num(voxelM),
            "samples" to JsonNumber(samples.size.toDouble()),
            "final" to JsonArray(samples.lastOrNull()?.second.orEmpty().map(::wallJson)),
            "medianOverSamples" to JsonArray(median.map(::wallJson)),
        )
        return MiniJson.write(JsonObject(fields))
    }

    /** 월드 복셀 중심을 정답 좌표로. */
    fun toTruth(voxelsW: List<Vec3>, al: Alignment): List<Vec3> = voxelsW.map(al::toTruth)
}
