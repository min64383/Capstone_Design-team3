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

/** 바닥면에 투영한 노면 화소 하나(계단 내리막·낙차 증거에 쓴다). */
class SurfacePoint(val pW: Vec3, val cls: Int)

/**
 * 월드 좌표의 노면 격자(M50 설계 3.4절). 노면 영상의 화소마다 카메라 광선을 바닥면 y = floorY와 교차시켜 0.1 m 칸에 쌓는다.
 * 한 영상 안에서 칸마다 가장 많은 클래스 하나만 남겨, 칸의 표는 "그 클래스로 본 영상 수"다(같은 영상의 화소 수에 끌리지 않게).
 * 지난 [SituationConfig.gridMemoryS] 안의 영상만 쓴다. 지금 시각 이후의 영상은 받지 않는다(부르는 쪽이 과거 영상만 넘긴다).
 */
class SurfaceGrid(private val cfg: SituationConfig) {
    /** 영상 하나가 본 칸들: 칸 열쇠, 섞은 열쇠, 그 영상에서 가장 많은 클래스. */
    private class FrameObs(val tNs: Long, val keys: LongArray, val mixed: LongArray, val cls: IntArray)

    private val frames = ArrayDeque<FrameObs>()

    /** 칸의 최근 관측 고리(M69): 최근 [SituationConfig.gridRecentK]개 영상의 시각·클래스. 영상을 넣을 때 갱신해 판단마다 다시 모으지 않는다. */
    private class Ring(k: Int) {
        val t = LongArray(k)
        val c = IntArray(k)
        var n = 0
        var head = 0
        var newest = Long.MIN_VALUE
    }

    /** 섞은 열쇠 → 최근 관측 고리. [SituationConfig.gridRecentK] = 0이면 비어 있다. */
    private val rings = HashMap<Long, Ring>()

    /** [add]의 칸 집계 작업 버퍼(카메라 둘레 조밀 배열, 클래스별 수)와 이번 영상에서 처음 닿은 칸 목록. 한 번 잡고 다시 쓴다. */
    private var dense = IntArray(0)
    private var touched = IntArray(0)

    /** 마지막으로 넣은 영상의 투영 점(배경 뺀 노면, 계단 내리막·낙차 증거에 쓴다). */
    var lastPoints: List<SurfacePoint> = emptyList()
        private set

    /**
     * 영상 [img]를 바닥면 높이 [floorY]에 투영해 넣는다. [stride] 화소마다 하나. [lastPoints]는 그중 [pointStride] 화소마다(0이면 [stride]와 같음, M69:
     * 원래 크기 노면 영상에서 점 객체가 4배라 기기 상황 판단과 메모리 정리가 무거워짐, 깊이 높이에는 너머 띠에 점 10개면 된다).
     */
    fun add(img: LabelImage, floorY: Float, stride: Int = 2, pointStride: Int = 0) {
        val ps = if (pointStride <= 0) stride else pointStride
        val k = img.K
        // 화소마다 객체를 만들지 않는다(M69: 원래 크기 노면 영상은 화소가 4배라 장마다 객체 약 30만 개 → 기기 상황 판단·메모리 정리가 무거움).
        // 계산 순서는 transformDir·Vec3과 같다(같은 결과). 칸 집계는 카메라 둘레 조밀 배열(한 번 잡고 다시 씀)
        val m = img.worldFromCam.toRowMajorArray()
        val ox = m[3]
        val oy = m[7]
        val oz = m[11]
        val r = (cfg.gridMaxDistM / cfg.gridCellM).toInt() + 2
        val side = 2 * r + 1
        val ix0 = index(ox) - r
        val iz0 = index(oz) - r
        val nc = SurfaceClass.COUNT
        if (dense.size < side * side * nc) dense = IntArray(side * side * nc)
        var nTouched = 0
        if (touched.size < side * side) touched = IntArray(side * side)
        val points = ArrayList<SurfacePoint>()
        val maxD2 = cfg.gridMaxDistM * cfg.gridMaxDistM
        var v = stride / 2
        while (v < k.height) {
            var u = stride / 2
            val ry = (v + 0.5f - k.cy) / k.fy
            while (u < k.width) {
                val c = img.labels[v * k.width + u].toInt() and 0xFF
                if (c < nc) {
                    val rx = (u + 0.5f - k.cx) / k.fx
                    val dx = m[0] * rx + m[1] * ry + m[2] * 1f
                    val dy = m[4] * rx + m[5] * ry + m[6] * 1f
                    val dz = m[8] * rx + m[9] * ry + m[10] * 1f
                    if (dy < -1e-4f) {
                        val t = (floorY - oy) / dy
                        if (t > 0f) {
                            val px = ox + dx * t
                            val pz = oz + dz * t
                            val ex = px - ox
                            val ez = pz - oz
                            if (ex * ex + ez * ez <= maxD2) {
                                val cell = (index(px) - ix0) * side + (index(pz) - iz0)
                                val base = cell * nc
                                var first = true
                                for (j in 0 until nc) if (dense[base + j] != 0) { first = false; break }
                                if (first) touched[nTouched++] = cell
                                dense[base + c]++
                                if (c != SurfaceClass.BACKGROUND.pixel && (u - stride / 2) % ps == 0 && (v - stride / 2) % ps == 0) points.add(SurfacePoint(Vec3(px, floorY, pz), c))
                            }
                        }
                    }
                }
                u += stride
            }
            v += stride
        }
        val keys = LongArray(nTouched)
        val mixed = LongArray(nTouched)
        val cls = IntArray(nTouched)
        for (i in 0 until nTouched) {
            val cell = touched[i]
            val base = cell * nc
            var best = 0
            for (j in 1 until nc) if (dense[base + j] > dense[base + best]) best = j
            cls[i] = best
            keys[i] = key(ix0 + cell / side, iz0 + cell % side)
            mixed[i] = mix(keys[i])
            for (j in 0 until nc) dense[base + j] = 0
        }
        frames.addLast(FrameObs(img.tCaptureNs, keys, mixed, cls))
        lastPoints = points
        val rk = cfg.gridRecentK
        if (rk > 0) for (j in keys.indices) {
            val r = rings.getOrPut(mixed[j]) { Ring(rk) }
            r.t[r.head] = img.tCaptureNs
            r.c[r.head] = cls[j]
            r.head = (r.head + 1) % rk
            if (r.n < rk) r.n++
            r.newest = img.tCaptureNs
        }
        val keepNs = (cfg.gridMemoryS * 1e9).toLong()
        while (frames.isNotEmpty() && frames.first().tNs < img.tCaptureNs - keepNs) {
            val f = frames.removeFirst()
            // 기억 밖으로 나간 영상의 칸 중 그 뒤로 다시 보지 않은 칸의 고리를 지운다
            if (rk > 0) for (m in f.mixed) rings[m]?.let { if (it.newest < img.tCaptureNs - keepNs) rings.remove(m) }
        }
    }

    /**
     * [nowNs]에서 지난 [windowS] 안의 영상을 모은 칸들. 창 길이마다 집계를 이어서 쌓는다(M65): 새로 창에 든 영상은 더하고, 창 밖으로 나간 영상은 뺀다.
     * 판단마다 창 안 영상 전부를 다시 모으면 기기에서 상황 판단 한 차례가 p95 275~460 ms였다(T01). [nowNs]는 줄지 않는다고 본다(재생·앱 모두).
     * 찾기는 섞은 열쇠로 한다: 칸 열쇠 `(ix << 32) | iz`의 `Long` 해시는 `ix xor iz`로 접혀 이웃 칸끼리 겹친다(M65 PC 프로파일 76%).
     * 칸을 도는 순서는 정해져 있지만 M50과 다르므로, 순서가 결과를 바꾸는 곳(블록 선 RANSAC)은 부르는 쪽이 칸을 좌표순으로 정렬한다.
     * [SurfaceCell.lastNs]는 창 안에서 그 칸에 마지막으로 더한 영상 시각이다(뺄 때 되돌리지 않음, 격자 밖에서 쓰지 않음).
     */
    fun cells(nowNs: Long, windowS: Float): Map<Long, SurfaceCell> =
        windows.getOrPut(windowS) { Window((windowS * 1e9).toLong()) }.advance(nowNs)

    private val windows = HashMap<Float, Window>()

    private inner class Window(private val lengthNs: Long) {
        private val inside = ArrayDeque<FrameObs>()
        private val byMixed = HashMap<Long, SurfaceCell>()
        private var lastAddedNs = Long.MIN_VALUE
        private val view = object : AbstractMap<Long, SurfaceCell>() {
            override val entries: Set<Map.Entry<Long, SurfaceCell>>
                get() = byMixed.values.mapTo(LinkedHashSet()) { java.util.AbstractMap.SimpleImmutableEntry(key(it.ix, it.iz), it) }
            override val values: Collection<SurfaceCell> get() = byMixed.values
            override val size: Int get() = byMixed.size
            override fun get(key: Long): SurfaceCell? = byMixed[mix(key)]
            override fun containsKey(key: Long): Boolean = byMixed.containsKey(mix(key))
        }

        fun advance(nowNs: Long): Map<Long, SurfaceCell> {
            val fromNs = nowNs - lengthNs
            while (inside.isNotEmpty() && inside.first().tNs < fromNs) {
                val f = inside.removeFirst()
                for (i in f.keys.indices) {
                    val m = f.mixed[i]
                    val cell = byMixed[m] ?: continue
                    cell.frames[f.cls[i]]--
                    if (cell.frames.all { it == 0 }) byMixed.remove(m)
                }
            }
            for (f in frames) {
                if (f.tNs <= lastAddedNs || f.tNs < fromNs || f.tNs > nowNs) continue
                for (i in f.keys.indices) {
                    val cell = byMixed.getOrPut(f.mixed[i]) { cellOf(f.keys[i]) }
                    cell.frames[f.cls[i]]++
                    if (f.tNs > cell.lastNs) cell.lastNs = f.tNs
                }
                inside.addLast(f)
                lastAddedNs = f.tNs
            }
            return view
        }
    }

    /**
     * 칸 [cellKey]([keyAt])의 최근 [SituationConfig.gridRecentK]번 관측 중 [nowNs]에서 지난 [windowS] 안의 클래스별 수(M69, 없으면 null).
     * 걸어가며 같은 칸을 점점 가까이(더 많은 화소로) 보므로, 멀리서 놓친 가는 블록 선(가로 방향)을 가까이서 본 관측이 이기게 하려는 것이다.
     */
    fun recentCounts(cellKey: Long, nowNs: Long, windowS: Float): IntArray? {
        val r = rings[mix(cellKey)] ?: return null
        val fromNs = nowNs - (windowS * 1e9).toLong()
        var out: IntArray? = null
        for (i in 0 until r.n) {
            if (r.t[i] < fromNs || r.t[i] > nowNs) continue
            if (out == null) out = IntArray(SurfaceClass.COUNT)
            out[r.c[i]]++
        }
        return out
    }

    /** 월드 점 [pW]가 든 칸의 열쇠. */
    fun keyAt(pW: Vec3): Long = key(index(pW.x), index(pW.z))

    fun reset() {
        frames.clear()
        windows.clear()
        rings.clear()
        lastPoints = emptyList()
    }

    private fun index(m: Float): Int = floor(m / cfg.gridCellM).toInt()

    private fun cellOf(key: Long): SurfaceCell {
        val ix = (key shr 32).toInt()
        val iz = key.toInt()
        return SurfaceCell(ix, iz, Vec3((ix + 0.5f) * cfg.gridCellM, 0f, (iz + 0.5f) * cfg.gridCellM))
    }

    private fun key(ix: Int, iz: Int): Long = (ix.toLong() shl 32) or (iz.toLong() and 0xFFFFFFFFL)

    private companion object {
        /** splitmix64 끝 섞기(일대일): 섞은 값의 `Long` 해시가 고르게 퍼진다. */
        fun mix(k: Long): Long {
            var z = k
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
