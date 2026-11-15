package hearspace.core.situation

import hearspace.core.geometry.Vec3
import hearspace.core.types.LabelImage
import hearspace.core.types.SituationConfig
import kotlin.math.floor

/** 노면 분할 모델의 클래스(M50 설계 표 4). [pixel]은 노면 영상의 화소 값이다. 255 = 계산 안 함. */
enum class SurfaceClass(val pixel: Int) {
    BACKGROUND(0), BLOCK(1), SIDEWALK(2), ALLEY(3), ROADWAY(4), CROSSWALK(5), STAIRS(6), BUMP(7);

    companion object {
        const val UNKNOWN_PIXEL = 255
        val COUNT = values().size

        /** 걸을 수 있는 노면(블록 끝 너머 관측에 쓴다). */
        val WALKABLE = setOf(BLOCK.pixel, SIDEWALK.pixel, ALLEY.pixel, ROADWAY.pixel, CROSSWALK.pixel)
    }
}

/** 노면 격자 칸 하나의 모음: 클래스별로 이 칸을 그 클래스로 본 노면 영상 수, 마지막으로 본 시각. */
class SurfaceCell(val ix: Int, val iz: Int, val centerW: Vec3) {
    val frames = IntArray(SurfaceClass.COUNT)
    var lastNs = Long.MIN_VALUE

    /** 가장 많이 본 클래스(화소 값). */
    val major: Int get() = frames.indices.maxBy { frames[it] }
}

/** 바닥면에 투영한 노면 화소 하나(계단 내리막 증거에 쓴다). */
class SurfacePoint(val pW: Vec3, val cls: Int)

/**
 * 월드 좌표의 노면 격자(M50 설계 3.4절). 노면 영상의 화소마다 카메라 광선을 바닥면 y = floorY와 교차시켜 0.1 m 칸에 쌓는다.
 * 한 영상 안에서 칸마다 가장 많은 클래스 하나만 남겨, 칸의 표는 "그 클래스로 본 영상 수"다(같은 영상의 화소 수에 끌리지 않게).
 * 지난 [SituationConfig.gridMemoryS] 안의 영상만 쓴다. 지금 시각 이후의 영상은 받지 않는다(부르는 쪽이 과거 영상만 넘긴다).
 */
class SurfaceGrid(private val cfg: SituationConfig) {
    private class FrameObs(val tNs: Long, val cells: Map<Long, Int>)

    private val frames = ArrayDeque<FrameObs>()

    /** 마지막으로 넣은 영상의 투영 점(계단·방지턱만, 다른 클래스는 칸으로만 남긴다). */
    var lastPoints: List<SurfacePoint> = emptyList()
        private set

    /** 영상 [img]를 바닥면 높이 [floorY]에 투영해 넣는다. [stride] 화소마다 하나. */
    fun add(img: LabelImage, floorY: Float, stride: Int = 2) {
        val k = img.K
        val o = img.worldFromCam.translation()
        val counts = HashMap<Long, IntArray>()
        val points = ArrayList<SurfacePoint>()
        val maxD2 = cfg.gridMaxDistM * cfg.gridMaxDistM
        var v = stride / 2
        while (v < k.height) {
            var u = stride / 2
            while (u < k.width) {
                val c = img.labels[v * k.width + u].toInt() and 0xFF
                if (c < SurfaceClass.COUNT) {
                    val d = img.worldFromCam.transformDir(Vec3((u + 0.5f - k.cx) / k.fx, (v + 0.5f - k.cy) / k.fy, 1f))
                    if (d.y < -1e-4f) {
                        val t = (floorY - o.y) / d.y
                        if (t > 0f) {
                            val p = Vec3(o.x + d.x * t, floorY, o.z + d.z * t)
                            val dx = p.x - o.x
                            val dz = p.z - o.z
                            if (dx * dx + dz * dz <= maxD2) {
                                counts.getOrPut(key(index(p.x), index(p.z))) { IntArray(SurfaceClass.COUNT) }[c]++
                                if (c == SurfaceClass.STAIRS.pixel || c == SurfaceClass.BUMP.pixel) points.add(SurfacePoint(p, c))
                            }
                        }
                    }
                }
                u += stride
            }
            v += stride
        }
        frames.addLast(FrameObs(img.tCaptureNs, counts.mapValues { (_, n) -> n.indices.maxBy { n[it] } }))
        lastPoints = points
        val keepNs = (cfg.gridMemoryS * 1e9).toLong()
        while (frames.isNotEmpty() && frames.first().tNs < img.tCaptureNs - keepNs) frames.removeFirst()
    }

    /** [nowNs]에서 지난 [windowS] 안의 영상을 모은 칸들. */
    fun cells(nowNs: Long, windowS: Float): Map<Long, SurfaceCell> {
        val fromNs = nowNs - (windowS * 1e9).toLong()
        val out = HashMap<Long, SurfaceCell>()
        for (f in frames) {
            if (f.tNs < fromNs || f.tNs > nowNs) continue
            for ((key, c) in f.cells) {
                val cell = out.getOrPut(key) { cellOf(key) }
                cell.frames[c]++
                if (f.tNs > cell.lastNs) cell.lastNs = f.tNs
            }
        }
        return out
    }

    /** 월드 점 [pW]가 든 칸의 열쇠. */
    fun keyAt(pW: Vec3): Long = key(index(pW.x), index(pW.z))

    fun reset() {
        frames.clear()
        lastPoints = emptyList()
    }

    private fun index(m: Float): Int = floor(m / cfg.gridCellM).toInt()

    private fun cellOf(key: Long): SurfaceCell {
        val ix = (key shr 32).toInt()
        val iz = key.toInt()
        return SurfaceCell(ix, iz, Vec3((ix + 0.5f) * cfg.gridCellM, 0f, (iz + 0.5f) * cfg.gridCellM))
    }

    private fun key(ix: Int, iz: Int): Long = (ix.toLong() shl 32) or (iz.toLong() and 0xFFFFFFFFL)
}
