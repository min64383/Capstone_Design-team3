package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.VoxelView
import hearspace.core.pipeline.FixedMap
import hearspace.core.types.DepthFrame
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 정답 대입 재생(M12.3 원인 분해, 평가 전용, IMPROVE_SPEC §9.2): 처리 단계의 출력을 정답으로 바꿔 넣어, 그 단계까지의 오차가
 * 사라진 안내를 얻는다. 앱 설정이 아니라 재생 인자(`substitute=`)로만 켠다.
 * - [depth]: 깊이 영상을 [TruthDepth]로(D). [depthNoisePerM] > 0이면 무작위 잡음만 더한다(Dn).
 * - [map]: 지도(바닥 추정·복셀 점유)를 정답 상자로 채운 고정 지도로(O). 통로·가장자리 규칙·군집·대표점·추적은 그대로 돈다.
 * - [heading]: 앱 진행 방향을 보행선 ±z 중 가까운 쪽으로(H, 지표의 정답 진행 방향과 같은 규칙).
 * 정렬([align])은 기준 재생이 낸 `align.json`을 모든 변형이 같이 쓴다(정렬이 재생의 바닥 추정에 기대므로, 변형마다 맞추면 정답이 달라진다).
 */
class Substitution(
    val truth: GroundTruth,
    val align: Alignment,
    val depth: Boolean = false,
    val depthNoisePerM: Float = 0f,
    val map: Boolean = false,
    val heading: Boolean = false,
) {
    /** 보행선 +z의 월드 방향(수평 단위). */
    private val walkW = align.toWorldDir(Vec3(0f, 0f, 1f))

    /** 깊이 대입. 끄면 그대로. */
    fun depthOf(d: DepthFrame): DepthFrame = if (depth) TruthDepth.render(d, truth.obstacles, align, depthNoisePerM) else d

    /** 진행 방향 대입(H): 앱 진행 방향과 같은 쪽의 보행선 방향. 끄면 null(대입 없음). */
    fun headingSnap(): ((Vec3) -> Vec3)? = if (!heading) null else { h -> if ((h dot walkW) >= 0f) walkW else -walkW }

    /** 지도 대입(O): 정답 상자를 복셀 크기 [voxelSizeM] 격자 점으로 채운 고정 지도. 끄면 null. */
    fun fixedMap(voxelSizeM: Float): FixedMap? = if (!map) null else truthMap(truth.obstacles, align, voxelSizeM)

    /** `replay_info.json`에 남길 내용(분석 도구가 같은 정렬을 읽는다). */
    fun infoJson(alignFrom: String): String {
        val on = listOfNotNull("depth".takeIf { depth }, "map".takeIf { map }, "heading".takeIf { heading })
        return "{ \"substitute\": [${on.joinToString { "\"$it\"" }}], \"depthNoisePerM\": $depthNoisePerM, " +
            "\"alignFrom\": \"${alignFrom.replace("\\", "/")}\" }"
    }

    companion object {
        /** 정답 상자를 격자 점(정답 좌표에서 [voxelSizeM] 간격, 상자 안 칸 중심)으로 채워 월드 복셀로. 바닥은 정렬의 바닥. */
        fun truthMap(obstacles: List<TruthObstacle>, align: Alignment, voxelSizeM: Float): FixedMap {
            val voxels = ArrayList<VoxelView>()
            for (o in obstacles) {
                fun n(a: Float, b: Float) = max(1, ((b - a) / voxelSizeM).roundToInt())
                val nx = n(o.minM.x, o.maxM.x)
                val ny = n(o.minM.y, o.maxM.y)
                val nz = n(o.minM.z, o.maxM.z)
                for (i in 0 until nx) for (j in 0 until ny) for (k in 0 until nz) {
                    val t = Vec3(
                        o.minM.x + (i + 0.5f) * (o.maxM.x - o.minM.x) / nx,
                        o.minM.y + (j + 0.5f) * (o.maxM.y - o.minM.y) / ny,
                        o.minM.z + (k + 0.5f) * (o.maxM.z - o.minM.z) / nz,
                    )
                    val w = align.toWorld(t)
                    voxels += VoxelView(
                        floor(w.x / voxelSizeM).toInt(), floor(w.y / voxelSizeM).toInt(), floor(w.z / voxelSizeM).toInt(),
                        w, hits = Int.MAX_VALUE, score = 1f, lastSeenNs = 0L, logOdds = 0f,
                    )
                }
            }
            return FixedMap(voxels, align.floorY.toFloat())
        }
    }
}

/**
 * 정답 깊이(D): 정답 상자와 바닥(정답 y = 0)을 녹화된 자세·내부 파라미터로 그린 깊이 영상. 합성 장면 렌더러
 * (test `SyntheticGenerator.render`)와 같은 계산이다: C_cv 광선 ((u − cx)/fx, (v − cy)/fy, 1)의 교차 t가 곧 깊이 Z.
 * 광선은 [Alignment]로 정답 좌표에 옮겨 축 정렬 상자와 교차한다(회전은 길이를 보존하므로 t도 같다). [MAX_DEPTH_M] 밖은 0(무효).
 * `noisePerM` > 0이면 화소마다 독립 정규 잡음 σ = noisePerM × Z를 더한다. 시드는 깊이 시각이라 같은 입력이면 같은 결과다.
 */
object TruthDepth {
    /** 합성 렌더러와 같은 최대 거리(m). */
    const val MAX_DEPTH_M = 8f

    private const val EPS = 1e-4f

    fun render(d: DepthFrame, obstacles: List<TruthObstacle>, align: Alignment, noisePerM: Float = 0f): DepthFrame {
        val k = d.K
        val o = align.toTruth(d.worldFromCam.translation())
        val rnd = if (noisePerM > 0f) Random(d.tCaptureNs) else null
        val mm = ShortArray(k.width * k.height)
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val dir = align.toTruthDir(d.worldFromCam.transformDir(Vec3((u - k.cx) / k.fx, (v - k.cy) / k.fy, 1f)))
            var best = if (abs(dir.y) > 1e-9f) (-o.y / dir.y).takeIf { it > EPS } else null // 바닥 y = 0
            for (b in obstacles) {
                val t = box(o, dir, b.minM, b.maxM) ?: continue
                if (best == null || t < best) best = t
            }
            var z = best ?: continue
            if (rnd != null) z += (noisePerM * z * rnd.nextGaussian()).toFloat()
            if (z <= 0f || z > MAX_DEPTH_M) continue
            mm[v * k.width + u] = min((z * 1000f).roundToInt(), 65535).toShort()
        }
        return d.copy(depthMm = mm, confidence = null, source = "truth")
    }

    /** 광선과 축 정렬 상자의 첫 교차 t(앞쪽), 없으면 null(합성 `Box.intersect`와 같은 slab 계산). */
    private fun box(o: Vec3, d: Vec3, lo: Vec3, hi: Vec3): Float? {
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
