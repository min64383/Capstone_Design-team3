package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.types.CorridorConfig
import kotlin.math.abs

/**
 * 진행 통로 (§7.5): 사용자 위치 [originW]에서 진행 방향 [headingW] 기준 폭 `widthM`, 앞 `lengthM`, 뒤 `behindM`,
 * 바닥 위 0 ~ `heightM`의 상자. 진행 방향 추정(Heading, M5)은 호출하는 쪽 책임이다.
 */
class Corridor(val originW: Vec3, headingW: Vec3, val floorY: Float, private val cfg: CorridorConfig) {

    /** 수평 단위 진행 방향. */
    val headingW: Vec3 = headingW.horizontal().normalized()

    private val rightW = this.headingW cross Vec3.UP

    /** 진행 방향 거리(앞 +). */
    fun alongM(pW: Vec3): Float = (pW - originW).horizontal() dot headingW

    /** 좌우 거리(오른쪽 +). */
    fun lateralM(pW: Vec3): Float = (pW - originW).horizontal() dot rightW

    /** 진행 방향 거리 [alongM]·좌우 [lateralM] 자리의 높이 [yW] 월드 점([alongM]·[lateralM]의 역). */
    fun pointW(alongM: Float, lateralM: Float, yW: Float): Vec3 {
        val h = originW + headingW * alongM + rightW * lateralM
        return Vec3(h.x, yW, h.z)
    }

    /** 통로 안인지. [halfWidthM]은 좌우 반폭(가장자리 구조물을 통로 밖까지 잴 때 넓힘, M22). */
    fun contains(pW: Vec3, halfWidthM: Float = cfg.widthM / 2): Boolean {
        val along = alongM(pW)
        val h = pW.y - floorY
        return along >= -cfg.behindM && along <= cfg.lengthM &&
            abs(lateralM(pW)) <= halfWidthM &&
            h >= 0f && h <= cfg.heightM
    }
}

/**
 * 벽 방향 맞춤(M42 C, `corridor.wallAlign`): [headingW]를 ±α 안에서 돌려 보며, 사용자 둘레(수평 `lengthM` 안, 바닥 위 0~`heightM`)의 점유 칸이
 * 그 방향과 나란한 한 줄(좌우 칸 하나 폭)에 진행 방향으로 가장 많이(서로 다른 칸 수) 들어가는 방향을 고른다. 그 줄이 `edgeMinLengthM` 이상 이어져야
 * 하고(가장자리 구조물과 같은 "길게 이어짐"), 없으면 [headingW] 그대로. 같은 길이면 덜 돌린 쪽.
 * α = asin((widthM/2 − edgeInnerM)/edgeMinLengthM): 이 각보다 덜 기운 벽은 가장자리 구역(폭 widthM/2 − edgeInnerM) 안에 edgeMinLengthM 넘게
 * 머물 수 있어 가장자리 구조물 규칙이 이미 나란한 벽으로 본다. 각 간격 atan(칸/lengthM)은 통로 끝에서 좌우 한 칸이 바뀌는 각이다.
 * 정답 진행 방향(H)이 복도 축으로 맞추는 것을 지도에서 보이는 벽으로 대신한다(C1 상한 D + H).
 */
fun wallAlignedHeading(headingW: Vec3, cellsW: List<Vec3>, userPosW: Vec3, floorY: Float, cfg: CorridorConfig, voxelM: Float): Vec3 {
    val h = headingW.horizontal().normalized()
    val minBins = kotlin.math.round(cfg.edgeMinLengthM / voxelM).toInt()
    val pts = cellsW.filter {
        val dy = it.y - floorY
        dy >= 0f && dy <= cfg.heightM && (it - userPosW).horizontal().norm() <= cfg.lengthM
    }
    if (pts.size < minBins) return h
    val maxRad = kotlin.math.asin(((cfg.widthM / 2 - cfg.edgeInnerM) / cfg.edgeMinLengthM).coerceIn(0f, 1f))
    val step = kotlin.math.atan(voxelM / cfg.lengthM)
    val n = (maxRad / step).toInt()
    var best = h
    var bestBins = minBins - 1
    for (i in 0..2 * n) {
        val k = if (i == 0) 0 else if (i % 2 == 1) (i + 1) / 2 else -(i / 2)
        val a = k * step
        val c = kotlin.math.cos(a)
        val s = kotlin.math.sin(a)
        val d = Vec3(h.x * c - h.z * s, 0f, h.x * s + h.z * c)
        val r = d cross Vec3.UP
        val seen = HashSet<Long>()
        val perLine = HashMap<Int, Int>()
        var longest = 0
        for (p in pts) {
            val q = (p - userPosW).horizontal()
            val lat = kotlin.math.floor((q dot r) / voxelM).toInt()
            val along = kotlin.math.floor((q dot d) / voxelM).toInt()
            if (seen.add((lat.toLong() shl 32) or (along.toLong() and 0xffffffffL))) {
                val m = (perLine[lat] ?: 0) + 1
                perLine[lat] = m
                if (m > longest) longest = m
            }
        }
        if (longest > bestBins) {
            bestBins = longest
            best = d
        }
    }
    return best
}
