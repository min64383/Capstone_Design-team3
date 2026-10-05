package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.types.CorridorConfig
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonValue
import hearspace.core.types.MiniJson

/**
 * 느린 경로 한 번의 맵(복셀) 정확도 (IMPROVE_SPEC §6.1, M12). 좌표는 정답 좌표.
 * 평가 전용이다: 정렬([Alignment])이 세션 전체의 바닥 높이를 쓰므로 실행 중 안내에는 쓰지 않는다.
 */
data class MapEvalStep(
    val tCaptureNs: Long,
    /** 점유 복셀 수(맵 전체). */
    val nOccupied: Int,
    /** 확실히 빈 공간(`free`) 안의 점유 복셀 = 헛 복셀. */
    val nPhantom: Int,
    /** 정답 물체(`kind: object`) 상자(+ 여유) 안의 점유 복셀. */
    val nObject: Int,
    /** 정답 통로 안에 든 정답 물체 수. */
    val objectsInRange: Int,
    /** 그중 상자 안에 점유 복셀이 하나라도 있는 물체 수. */
    val objectsSeen: Int,
)

/** 세션 전체 요약. 물체 지표는 정답 물체가 통로 안에 있던 단계만으로 낸다. */
data class MapEvalSummary(
    val nSteps: Int,
    val phantomMean: Double,
    val phantomP95: Double,
    val phantomMax: Int,
    /** 물체가 통로 안에 있던 단계의 물체 복셀 평균. */
    val objectVoxelsMean: Double?,
    /** 통로 안에 든 (단계 × 물체) 중 보인 비율. */
    val objectSeenFraction: Double?,
) {
    fun toJson(): String = MiniJson.write(
        JsonObject(
            linkedMapOf<String, JsonValue>(
                "nSteps" to JsonNumber(nSteps.toDouble()),
                "phantomMean" to JsonNumber(phantomMean),
                "phantomP95" to JsonNumber(phantomP95),
                "phantomMax" to JsonNumber(phantomMax.toDouble()),
            ).apply {
                objectVoxelsMean?.let { put("objectVoxelsMean", JsonNumber(it)) }
                objectSeenFraction?.let { put("objectSeenFraction", JsonNumber(it)) }
            },
        ),
    )
}

object MapEval {
    /**
     * 점유 복셀 중심 [centersW](x, y, z 반복, 월드)를 정답과 대조한다. [head]는 이 깊이 때의 머리(정답 좌표).
     * 물체 상자는 [marginM]만큼 넓혀 센다(복셀 중심과 상자 경계의 양자화 차).
     */
    fun step(
        tCaptureNs: Long,
        centersW: FloatArray,
        truth: GroundTruth,
        al: Alignment,
        head: Vec3,
        corridor: CorridorConfig,
        marginM: Float,
    ): MapEvalStep {
        val objects = truth.obstacles.filter { it.kind == TruthKind.OBJECT }
        val perObject = IntArray(objects.size)
        var phantom = 0
        val n = centersW.size / 3
        for (i in 0 until n) {
            val p = al.toTruth(Vec3(centersW[3 * i], centersW[3 * i + 1], centersW[3 * i + 2]))
            if (truth.free.any { it.contains(p) }) phantom++
            for ((k, o) in objects.withIndex()) {
                if (p.x >= o.minM.x - marginM && p.x <= o.maxM.x + marginM &&
                    p.y >= o.minM.y - marginM && p.y <= o.maxM.y + marginM &&
                    p.z >= o.minM.z - marginM && p.z <= o.maxM.z + marginM
                ) perObject[k]++
            }
        }
        val inRange = objects.indices.filter { objects[it].nearestInCorridor(head.x, head.z, corridor) != null }
        return MapEvalStep(tCaptureNs, n, phantom, perObject.sum(), inRange.size, inRange.count { perObject[it] > 0 })
    }

    fun summary(steps: List<MapEvalStep>): MapEvalSummary {
        val ph = steps.map { it.nPhantom }.sorted()
        val withObj = steps.filter { it.objectsInRange > 0 }
        val inRange = withObj.sumOf { it.objectsInRange }
        return MapEvalSummary(
            nSteps = steps.size,
            phantomMean = if (ph.isEmpty()) 0.0 else ph.average(),
            phantomP95 = if (ph.isEmpty()) 0.0 else ph[((ph.size - 1) * 0.95).toInt()].toDouble(),
            phantomMax = ph.lastOrNull() ?: 0,
            objectVoxelsMean = if (withObj.isEmpty()) null else withObj.map { it.nObject }.average(),
            objectSeenFraction = if (inRange == 0) null else withObj.sumOf { it.objectsSeen }.toDouble() / inRange,
        )
    }
}
