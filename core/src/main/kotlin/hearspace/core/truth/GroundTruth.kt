package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.types.CorridorConfig
import hearspace.core.types.HeightClass
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonBool
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.MiniJson
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot

/** 정답 물체 종류(IMPROVE_SPEC §9.1). 구조물(벽·문 같은 큰 평면)은 물체 탐지율 분모에서 뺀다. */
enum class TruthKind { OBJECT, STRUCTURE }

/**
 * 정답 장애물 하나. 좌표는 **정답 좌표**(원점 = 시작 때 머리 아래 바닥, +x 오른쪽, +y 위(바닥 0), +z 보행선 앞, m)이며
 * `distanceFrom: "camera"`로 적힌 파일은 이미 머리 원점으로 옮겨져 있다.
 */
data class TruthObstacle(
    val name: String,
    /** 기대 높이 분류. */
    val type: HeightClass,
    val kind: TruthKind,
    val minM: Vec3,
    val maxM: Vec3,
    /** 이 시각(세션 시작 후 s)부터 없음(합성 SC-04). */
    val removeAtS: Float?,
) {
    /**
     * 정답 통로(머리 [headX], [headZ]에서 보행선 +z 방향) 안 최근접점. 통로 밖이면 null.
     * `tools/analysis/metrics.py`의 `truth_nearest`와 같은 규칙이다(바꾸면 두 곳을 같이 바꾼다).
     */
    fun nearestInCorridor(headX: Float, headZ: Float, c: CorridorConfig): TruthNearest? {
        if (minM.y >= c.heightM) return null
        val lo = maxOf(minM.x, headX - c.widthM / 2)
        val hi = minOf(maxM.x, headX + c.widthM / 2)
        if (lo > hi) return null
        val zn = maxOf(minM.z, headZ - c.behindM)
        if (zn > maxM.z || zn - headZ > c.lengthM) return null
        val xn = headX.coerceIn(lo, hi)
        val along = zn - headZ
        return TruthNearest(along, hypot(xn - headX, along), Math.toDegrees(atan2((xn - headX).toDouble(), along.toDouble())).toFloat())
    }
}

/** 정답 통로 안 최근접점: 보행선 방향 거리, 수평 거리, 방위각(오른쪽 +). */
data class TruthNearest(val alongM: Float, val distanceM: Float, val azimuthDeg: Float)

/** 확실히 비어 있는 공간(정답 좌표, M12). 맵 정확도에서 이 안의 점유 복셀은 헛 복셀이다. */
data class TruthFreeBox(val name: String, val minM: Vec3, val maxM: Vec3) {
    fun contains(p: Vec3) = p.x in minM.x..maxM.x && p.y in minM.y..maxM.y && p.z in minM.z..maxM.z
}

/** 세션의 정답 파일 `annotations/obstacles.json` (docs/FORMAT.md, IMPROVE_SPEC §9.1). */
data class GroundTruth(
    val version: Int,
    /** 장면 ID. 폴더 이름의 장면 ID가 틀린 녹화가 있어(M10) 이 값이 우선이다. */
    val scene: String?,
    /** 줄자 실측이 아닌 추정치인가. */
    val estimated: Boolean,
    val note: String,
    val obstacles: List<TruthObstacle>,
    /** 확실히 빈 공간(`free`, M12). 없으면 빈 목록. */
    val free: List<TruthFreeBox> = emptyList(),
) {
    companion object {
        /** 세션 폴더 기준 정답 파일 경로. */
        const val FILE = "annotations/obstacles.json"

        /** [session]의 정답 파일을 읽는다. 없으면 null. [headOffsetFromCameraM]은 설정 `head.offsetFromCameraM`. */
        fun read(session: File, headOffsetFromCameraM: Vec3): GroundTruth? =
            File(session, FILE).takeIf { it.isFile }?.let { parse(it.readText(), headOffsetFromCameraM) }

        /**
         * v1·v2 형식을 읽는다. `distanceFrom`이 `"camera"`면 시작 때 카메라에서 잰 좌표라서 머리 원점으로 옮긴다:
         * 머리 = 카메라 + 오프셋(진행 방향 기준)이므로 정답 좌표에서 카메라는 (−ox, ·, −oz)에 있다.
         */
        fun parse(text: String, headOffsetFromCameraM: Vec3): GroundTruth {
            val root = MiniJson.parse(text) as? JsonObject ?: throw IllegalArgumentException("obstacles.json: top level must be an object")
            val f = root.fields
            val from = (f["distanceFrom"] as? JsonString)?.value ?: "start"
            val shift = when (from) {
                "start" -> Vec3(0f, 0f, 0f)
                "camera" -> Vec3(-headOffsetFromCameraM.x, 0f, -headOffsetFromCameraM.z)
                else -> throw IllegalArgumentException("obstacles.json: distanceFrom must be \"start\" or \"camera\", got \"$from\"")
            }
            val items = (f["obstacles"] as? JsonArray)?.items ?: throw IllegalArgumentException("obstacles.json: obstacles must be an array")
            return GroundTruth(
                version = (f["version"] as? JsonNumber)?.value?.toInt() ?: 1,
                scene = (f["scene"] as? JsonString)?.value,
                estimated = (f["estimated"] as? JsonBool)?.value ?: false,
                note = (f["note"] as? JsonString)?.value ?: "",
                obstacles = items.map { obstacle(it as? JsonObject ?: throw IllegalArgumentException("obstacles.json: obstacle must be an object"), shift) },
                free = ((f["free"] as? JsonArray)?.items ?: emptyList()).mapIndexed { i, it ->
                    val o = (it as? JsonObject ?: throw IllegalArgumentException("obstacles.json: free must be an array of objects")).fields
                    TruthFreeBox((o["name"] as? JsonString)?.value ?: "free$i", vec(o, "min", shift), vec(o, "max", shift))
                },
            )
        }

        private fun vec(f: Map<String, hearspace.core.types.JsonValue>, key: String, shift: Vec3): Vec3 {
            val a = (f[key] as? JsonArray)?.items?.map { (it as? JsonNumber)?.value?.toFloat() }
            require(a != null && a.size == 3 && a.all { it != null }) { "obstacles.json: $key must be an array of 3 numbers" }
            return Vec3(a[0]!!, a[1]!!, a[2]!!) + shift
        }

        private fun obstacle(o: JsonObject, shift: Vec3): TruthObstacle {
            val f = o.fields
            fun vec(key: String) = vec(f, key, shift)
            fun str(key: String): String? = (f[key] as? JsonString)?.value
            return TruthObstacle(
                name = str("name") ?: throw IllegalArgumentException("obstacles.json: name is required"),
                type = HeightClass.valueOf(str("type") ?: throw IllegalArgumentException("obstacles.json: type is required")),
                kind = when (val k = str("kind") ?: "object") {
                    "object" -> TruthKind.OBJECT
                    "structure" -> TruthKind.STRUCTURE
                    else -> throw IllegalArgumentException("obstacles.json: kind must be object or structure, got $k")
                },
                minM = vec("min"),
                maxM = vec("max"),
                removeAtS = (f["removeAtS"] as? JsonNumber)?.value?.toFloat(),
            )
        }
    }
}
