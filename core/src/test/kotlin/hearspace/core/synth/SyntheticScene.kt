package hearspace.core.synth

import hearspace.core.geometry.Conventions
import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Quaternion
import hearspace.core.geometry.Vec3
import hearspace.core.session.PoseGl
import hearspace.core.types.DepthFrame
import hearspace.core.types.GuideImage
import hearspace.core.types.HeightClass
import hearspace.core.types.Intrinsics
import hearspace.core.types.PoseFrame
import hearspace.core.types.TrackingState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// 합성 장면 생성기 (MVP_SPEC §7.8). 월드: +Y 위, 바닥 y = 0. 레이캐스팅으로 깊이를 정확히 만든다.

/** 월드에 놓인 도형. 레이 교차와 표면 거리(테스트용)를 제공한다. */
sealed interface Shape {
    /** 광선 o + t·d (t > 0)와의 가장 가까운 교차 t. 없으면 null. */
    fun intersect(o: Vec3, d: Vec3): Float?

    /** 점에서 표면까지의 거리(부호 없음, m). */
    fun surfaceDistance(p: Vec3): Float

    val aabbMin: Vec3
    val aabbMax: Vec3
}

/** 무한 수평면 y = [heightM] (바닥). */
data class HorizontalPlane(val heightM: Float) : Shape {
    override fun intersect(o: Vec3, d: Vec3): Float? {
        if (abs(d.y) < 1e-9f) return null
        val t = (heightM - o.y) / d.y
        return if (t > EPS) t else null
    }

    override fun surfaceDistance(p: Vec3) = abs(p.y - heightM)
    override val aabbMin get() = Vec3(-BIG, heightM, -BIG)
    override val aabbMax get() = Vec3(BIG, heightM, BIG)
}

/** 축 정렬 상자(벽·상자·판). */
data class Box(val min: Vec3, val max: Vec3) : Shape {
    init {
        require(min.x < max.x && min.y < max.y && min.z < max.z) { "degenerate box $min $max" }
    }

    override fun intersect(o: Vec3, d: Vec3): Float? {
        var t0 = -Float.MAX_VALUE
        var t1 = Float.MAX_VALUE
        for (a in 0..2) {
            val oa = o.c(a)
            val da = d.c(a)
            val lo = min.c(a)
            val hi = max.c(a)
            if (abs(da) < 1e-12f) {
                if (oa < lo || oa > hi) return null
            } else {
                var ta = (lo - oa) / da
                var tb = (hi - oa) / da
                if (ta > tb) ta = tb.also { tb = ta }
                t0 = max(t0, ta)
                t1 = min(t1, tb)
                if (t0 > t1) return null
            }
        }
        return when {
            t0 > EPS -> t0
            t1 > EPS -> t1 // 상자 안에서 출발
            else -> null
        }
    }

    override fun surfaceDistance(p: Vec3): Float {
        val qx = max(min.x - p.x, p.x - max.x)
        val qy = max(min.y - p.y, p.y - max.y)
        val qz = max(min.z - p.z, p.z - max.z)
        val outside = sqrt(max(qx, 0f).sq() + max(qy, 0f).sq() + max(qz, 0f).sq())
        return if (outside > 0f) outside else -max(qx, max(qy, qz))
    }

    override val aabbMin get() = min
    override val aabbMax get() = max
}

/** 세로 원기둥(기둥). 중심 (cx, cz), 반지름, 높이 범위. */
data class VerticalCylinder(val cx: Float, val cz: Float, val radiusM: Float, val yMin: Float, val yMax: Float) : Shape {
    override fun intersect(o: Vec3, d: Vec3): Float? {
        var best: Float? = null
        fun consider(t: Float) {
            if (t > EPS && (best == null || t < best!!)) best = t
        }
        // 옆면
        val ox = o.x - cx
        val oz = o.z - cz
        val a = d.x * d.x + d.z * d.z
        if (a > 1e-12f) {
            val b = 2 * (ox * d.x + oz * d.z)
            val c = ox * ox + oz * oz - radiusM * radiusM
            val disc = b * b - 4 * a * c
            if (disc >= 0) {
                val s = sqrt(disc)
                for (t in floatArrayOf((-b - s) / (2 * a), (-b + s) / (2 * a))) {
                    val y = o.y + t * d.y
                    if (y in yMin..yMax) consider(t)
                }
            }
        }
        // 윗면·아랫면
        if (abs(d.y) > 1e-12f) {
            for (cap in floatArrayOf(yMin, yMax)) {
                val t = (cap - o.y) / d.y
                val x = o.x + t * d.x - cx
                val z = o.z + t * d.z - cz
                if (x * x + z * z <= radiusM * radiusM) consider(t)
            }
        }
        return best
    }

    override fun surfaceDistance(p: Vec3): Float {
        val r = sqrt((p.x - cx).sq() + (p.z - cz).sq())
        val dr = r - radiusM
        val dy = max(yMin - p.y, p.y - yMax)
        return if (dr <= 0f && dy <= 0f) -max(dr, dy) else sqrt(max(dr, 0f).sq() + max(dy, 0f).sq())
    }

    override val aabbMin get() = Vec3(cx - radiusM, yMin, cz - radiusM)
    override val aabbMax get() = Vec3(cx + radiusM, yMax, cz + radiusM)
}

/**
 * 장면 요소. [obstacle]이 참이면 정답 장애물이고, [removeAtS]가 있으면 그 시각부터 사라진다(SC-04).
 * [structure]가 참이면 장애물은 아니지만 정답 파일에 구조물(`kind: structure`, 벽)로 적는다(M12.0 오차 분해의 기준 면).
 */
data class SceneItem(
    val name: String,
    val shape: Shape,
    val obstacle: Boolean,
    /** 정답 높이 분류(장애물일 때). */
    val expectedClass: HeightClass? = null,
    val removeAtS: Float? = null,
    val structure: Boolean = false,
    /** RGB 안내 보정(M13.7) 시험용 밝기 0~255. 기본은 모두 같아 대비가 없다. */
    val luma: Int = 128,
) {
    /** 시각 [tS]에 존재하는지. */
    fun presentAt(tS: Float) = removeAtS == null || tS < removeAtS
}

/** 장면: 도형 목록. */
data class Scene(val items: List<SceneItem>) {
    /** 시각 [tS]에 광선의 가장 가까운 교차(t, 요소). */
    fun raycast(o: Vec3, d: Vec3, tS: Float): Pair<Float, SceneItem>? {
        var best: Pair<Float, SceneItem>? = null
        for (it in items) {
            if (!it.presentAt(tS)) continue
            val t = it.shape.intersect(o, d) ?: continue
            if (best == null || t < best.first) best = t to it
        }
        return best
    }

    /** 시각 [tS]에 존재하는 도형 표면까지의 최소 거리. */
    fun surfaceDistance(p: Vec3, tS: Float): Float =
        items.filter { it.presentAt(tS) }.minOf { it.shape.surfaceDistance(p) }
}

/** 폰 파지 방향. 세로 파지면 센서 +X(GL)가 월드 아래를 향한다(F2 실측). */
enum class Hold { PORTRAIT, LANDSCAPE }

/**
 * 보행 궤적. 카메라는 [startCameraW]에서 [standS]초 정지 후 [headingDeg] 방향으로 [speedMps]로 걷는다.
 * [headingDeg]는 월드 −Z에서 +X 쪽으로 잰 각도(0이면 −Z로 걷는다).
 * 손 흔들림: 걸음 주기 상하 진폭 [bobAmpM], 손목 요 진폭 [wristYawAmpDeg](걸음 주기의 절반 주파수).
 * 왕복(M12.0 E03·E04): [legM]가 있으면 그만큼 걸은 뒤 머리 자리에서 [turnS]초 동안 180° 돌아(카메라는 머리 둘레로 돈다)
 * 같은 선으로 되돌아오기를 반복한다.
 */
data class Walk(
    val startCameraW: Vec3 = Vec3(0f, 1f, 0f),
    val headingDeg: Float = 0f,
    val speedMps: Float = 1f,
    val standS: Float = 2f,
    val durationS: Float = 4f,
    val fps: Int = 30,
    val stepHz: Float = 1.8f,
    val bobAmpM: Float = 0f,
    val wristYawAmpDeg: Float = 0f,
    /** 카메라 아래로 숙인 각(+이면 바닥 쪽). */
    val pitchDownDeg: Float = 10f,
    val hold: Hold = Hold.PORTRAIT,
    /** 카메라 → 머리 오프셋, 진행 방향 기준 (오른쪽, 위, 앞). */
    val gripOffsetM: Vec3 = Vec3(0f, 0.5f, -0.3f),
    /** 한 방향으로 걷는 거리(m). null이면 계속 한 방향. */
    val legM: Float? = null,
    /** 제자리 180° 회전 시간(s). */
    val turnS: Float = 2f,
    /** 이만큼 걸은 구간(leg) 뒤에는 마지막 자리·방향으로 선다. */
    val legCount: Int = Int.MAX_VALUE,
)

/** 시각의 보행 상태: 보행선 위 머리 위치(시작에서 m), 처음 방향에 더한 회전(rad), 걷는 중인지, 걸은 거리(회전 제외, m). */
data class LegState(val alongM: Float, val turnRad: Float, val moving: Boolean, val walkedM: Float)

/** 자세 점프: [atS]부터 ARCore가 보고하는 월드 좌표가 [shiftM] 이동·[yawDeg] 회전한 것처럼 바뀐다(SC-12). */
data class PoseJump(val atS: Float, val shiftM: Vec3, val yawDeg: Float)

/** 잡음·결함 옵션 (§7.8). 기본값은 모두 0. */
data class Noise(
    val seed: Long = 1L,
    /** 깊이 곱셈 잡음 표준편차(비율, 0.01 = 1%). */
    val depthMulStd: Float = 0f,
    /** 깊이 스케일 편향(비율, 0.1 = +10%). */
    val depthScaleBias: Float = 0f,
    /** 무효(0) 픽셀 비율. */
    val invalidRatio: Float = 0f,
    /** 프레임 드롭 비율(자세·깊이 모두 빠짐). */
    val frameDropRatio: Float = 0f,
    /** 보고 자세 위치 잡음 표준편차(m). */
    val posePosStdM: Float = 0f,
    /** 보고 자세 회전 잡음 표준편차(도). */
    val poseRotStdDeg: Float = 0f,
    /** 추적 상실 구간(초, [시작, 끝)). 이 동안 tracking = PAUSED, 깊이 없음. */
    val trackingLossS: List<ClosedFloatingPointRange<Float>> = emptyList(),
    val poseJumps: List<PoseJump> = emptyList(),
    /** 깊이 정지 구간: 이 동안 직전 깊이(같은 시각·같은 값)가 반복된다(SC-13). */
    val depthFreezeS: List<ClosedFloatingPointRange<Float>> = emptyList(),
    /** 쓰레기 깊이 구간: 이 동안 깊이 × 10, 최대 거리 제한 없음(M7 실측: 재생 시작 약 3 s 17~28 m, SC-15). */
    val depthGarbageS: List<ClosedFloatingPointRange<Float>> = emptyList(),
    /**
     * 누적 드리프트(M12.0): 걸은 거리 s(m)에 비례해 보고 월드 좌표가 월드 원점 둘레로 s × [yawDriftDegPerM]° 돌고
     * s × [posDriftPerM]만큼 옮겨진 것처럼 바뀐다(점프와 같은 변환을 연속으로). 제자리 회전 중에는 늘지 않는다.
     */
    val yawDriftDegPerM: Float = 0f,
    val posDriftPerM: Vec3 = Vec3.ZERO,
    /**
     * 가장자리 평활 모드(M13.0): ARCore 일반 깊이의 막을 흉내 낸다. 반경 이 픽셀 정사각형 안에 깊이 불연속(최대 − 최소 >
     * 최소 × [EDGE_JUMP_RATIO])이 있는 픽셀만 그 안 유효 깊이의 평균으로 바꿔, 불연속이 2 × 반경 픽셀의 경사가 된다.
     * 평탄·비스듬한 면은 그대로다. 실측(M12.0, 160 × 90 일반 깊이): 캐리어 윗모서리 경사 p50 2~4, p90 3~5 픽셀.
     * 실측의 뒤 배경 끌림(경계에서 약 25픽셀 안 0.15~0.5 m)은 흉내 내지 않는다(M13.1의 짧은 경사 판정 대상이 아님).
     */
    val edgeSmoothPx: Int = 0,
)

/** 가장자리 평활 모드에서 깊이 불연속으로 보는 창 안 깊이 차 비율. */
const val EDGE_JUMP_RATIO = 0.1f

/** 합성 프레임 1개. [truthWorldFromCam]은 점프·잡음이 없는 참 자세(C_cv). */
data class SynthFrame(
    val index: Int,
    val tS: Float,
    val pose: PoseFrame,
    val poseGl: PoseGl,
    val displayPoseGl: PoseGl,
    val depth: DepthFrame?,
    val truthWorldFromCam: Mat4,
    val truthHead: HeadPose,
)

/** 생성 결과. */
data class SyntheticRecording(val scene: Scene, val walk: Walk, val noise: Noise, val frames: List<SynthFrame>)

/** 합성 장면 → 프레임 스트림 생성기. */
object SyntheticGenerator {
    /** 깊이 내부 파라미터: F4 후보(텍스처 K × 1/12, 160x90). */
    val DEPTH_K = Intrinsics(123.59f, 123.59f, 79.39f, 43.63f, 160, 90)

    /** 시작 시각(ns). 0이 아닌 값으로 두어 시각 계산 실수를 드러낸다. */
    const val T0_NS = 1_000_000_000_000L

    /** 깊이 최대 거리(m). 넘으면 0(무효). */
    const val MAX_DEPTH_M = 8f

    /**
     * [scene]을 [walk] 궤적으로 촬영한다. 깊이는 [depthEveryNFrames] 프레임마다(1 = 30 Hz 일반 깊이, 3 = 10 Hz 원시 깊이 흉내).
     * 같은 입력·같은 시드면 항상 같은 결과.
     */
    fun generate(
        scene: Scene,
        walk: Walk = Walk(),
        noise: Noise = Noise(),
        depthEveryNFrames: Int = 1,
        k: Intrinsics = DEPTH_K,
    ): SyntheticRecording {
        val rnd = Random(noise.seed)
        val n = (walk.durationS * walk.fps).roundToInt()
        val frames = ArrayList<SynthFrame>(n)
        var lastDepth: DepthFrame? = null
        for (i in 0 until n) {
            val tS = i.toFloat() / walk.fps
            val tNs = T0_NS + i * 1_000_000_000L / walk.fps
            val drop = noise.frameDropRatio > 0f && rnd.nextFloat() < noise.frameDropRatio
            val (truth, head) = cameraPose(walk, tS)
            if (drop) continue
            val lost = noise.trackingLossS.any { tS in it }
            val reported = applyPoseNoise(applyJumps(applyDrift(truth, noise, legState(walk, tS).walkedM), noise, tS), noise, rnd)
            val worldFromGl = Conventions.cvToGl(reported)
            val depth = when {
                lost -> null
                i % depthEveryNFrames != 0 -> null
                noise.depthFreezeS.any { tS in it } && lastDepth != null -> lastDepth
                else -> render(scene, truth, reported, k, tNs, tS, noise, rnd)
            }
            if (depth != null) lastDepth = depth
            frames += SynthFrame(
                index = i,
                tS = tS,
                pose = PoseFrame(tNs, if (lost) TrackingState.PAUSED else TrackingState.TRACKING, reported),
                poseGl = toPoseGl(worldFromGl),
                displayPoseGl = toPoseGl(worldFromGl * displayRotation(walk.hold)),
                depth = depth,
                truthWorldFromCam = truth,
                truthHead = head,
            )
        }
        return SyntheticRecording(scene, walk, noise, frames)
    }

    /** 시각 [tS]의 참 카메라 자세(C_cv)와 참 머리 자세. */
    fun cameraPose(w: Walk, tS: Float): Pair<Mat4, HeadPose> {
        val h0 = Math.toRadians(w.headingDeg.toDouble()).toFloat()
        val line = Vec3(sin(h0), 0f, -cos(h0))
        val leg = legState(w, tS)
        val h = h0 + leg.turnRad
        val heading = Vec3(sin(h), 0f, -cos(h))
        val moving = leg.moving
        val phase = 2f * PI.toFloat() * w.stepHz * tS
        val bob = if (moving) w.bobAmpM * sin(phase) else 0f
        val yawOff = if (moving) Math.toRadians(w.wristYawAmpDeg * sin(phase / 2).toDouble()).toFloat() else 0f
        // 머리는 보행선을 따라 곧게 걷고, 카메라는 머리 − 오프셋(지금 진행 방향 기준)에서 흔들린다.
        fun offsetFor(f: Vec3) = f.cross(Vec3.UP) * w.gripOffsetM.x + Vec3.UP * w.gripOffsetM.y + f * w.gripOffsetM.z
        val offset = offsetFor(heading)
        val headPos = w.startCameraW + offsetFor(line) + line * leg.alongM
        val camPos = headPos - offset + Vec3.UP * bob

        val yaw = h + yawOff
        val pitch = Math.toRadians(w.pitchDownDeg.toDouble()).toFloat()
        val fwd = Vec3(sin(yaw) * cos(pitch), -sin(pitch), -cos(yaw) * cos(pitch))
        val upPerp = (Vec3.UP - fwd * (Vec3.UP dot fwd)).normalized()
        val zGl = -fwd
        val xGl = when (w.hold) {
            Hold.PORTRAIT -> -upPerp // 센서 +X가 아래
            Hold.LANDSCAPE -> fwd.cross(Vec3.UP).normalized() // 센서 +X가 오른쪽
        }
        val yGl = zGl.cross(xGl)
        val worldFromGl = Mat4.fromAxes(xGl, yGl, zGl, camPos)
        return Conventions.glToCv(worldFromGl) to HeadPose(headPos, heading)
    }

    /** 시각 [tS]의 보행 상태([Walk.legM]이 없으면 한 방향으로 계속 걷는다). */
    fun legState(w: Walk, tS: Float): LegState {
        val walkingS = max(0f, tS - w.standS)
        val legM = w.legM ?: return LegState(walkingS * w.speedMps, 0f, tS > w.standS, walkingS * w.speedMps)
        val legS = legM / w.speedMps
        if (walkingS >= w.legCount.toFloat() * legS + (w.legCount - 1).toFloat() * w.turnS) {
            val last = w.legCount - 1
            return LegState(if (last % 2 == 0) legM else 0f, PI.toFloat() * last, false, w.legCount * legM)
        }
        val cycleS = legS + w.turnS
        val k = kotlin.math.floor(walkingS / cycleS).toInt()
        val r = walkingS - k * cycleS
        val inLeg = r < legS
        val d = if (inLeg) r * w.speedMps else legM
        val along = if (k % 2 == 0) d else legM - d
        val turn = PI.toFloat() * (k + if (inLeg) 0f else (r - legS) / w.turnS)
        return LegState(along, turn, tS > w.standS && inLeg, k * legM + d)
    }

    private fun applyDrift(t: Mat4, noise: Noise, walkedM: Float): Mat4 {
        if (noise.yawDriftDegPerM == 0f && noise.posDriftPerM == Vec3.ZERO) return t
        val q = Quaternion.fromAxisAngle(Vec3.UP, Math.toRadians((noise.yawDriftDegPerM * walkedM).toDouble()).toFloat())
        return q.toMat4(noise.posDriftPerM * walkedM) * t
    }

    private fun applyJumps(t: Mat4, noise: Noise, tS: Float): Mat4 {
        var r = t
        for (j in noise.poseJumps) {
            if (tS >= j.atS) {
                val q = Quaternion.fromAxisAngle(Vec3.UP, Math.toRadians(j.yawDeg.toDouble()).toFloat())
                r = q.toMat4(j.shiftM) * r
            }
        }
        return r
    }

    private fun applyPoseNoise(t: Mat4, noise: Noise, rnd: Random): Mat4 {
        if (noise.posePosStdM == 0f && noise.poseRotStdDeg == 0f) return t
        val dp = Vec3(gauss(rnd), gauss(rnd), gauss(rnd)) * noise.posePosStdM
        val axis = Vec3(gauss(rnd), gauss(rnd), gauss(rnd))
        val ang = Math.toRadians((gauss(rnd) * noise.poseRotStdDeg).toDouble()).toFloat()
        val rot = if (axis.norm() > 0f) Quaternion.fromAxisAngle(axis, ang).toMat4(Vec3.ZERO) else Mat4.IDENTITY
        val p = t.translation()
        // 카메라 위치를 중심으로 회전 후 위치 잡음
        return Quaternion.IDENTITY.toMat4(p + dp) * rot * Quaternion.IDENTITY.toMat4(-p) * t
    }

    private fun render(
        scene: Scene, truth: Mat4, reported: Mat4, k: Intrinsics, tNs: Long, tS: Float, noise: Noise, rnd: Random,
    ): DepthFrame {
        val o = truth.translation()
        val garbage = noise.depthGarbageS.any { tS in it }
        val mm = ShortArray(k.width * k.height)
        val luma = ByteArray(k.width * k.height)
        for (v in 0 until k.height) for (u in 0 until k.width) {
            // C_cv 광선 (Z = 1) → 월드. 교차 t가 곧 C_cv 깊이 Z다.
            val dCv = Vec3((u - k.cx) / k.fx, (v - k.cy) / k.fy, 1f)
            val hit = scene.raycast(o, truth.transformDir(dCv), tS) ?: continue
            luma[v * k.width + u] = hit.second.luma.toByte() // RGB는 선명하다(평활은 깊이에만)
            var z = hit.first * (1f + noise.depthScaleBias)
            if (noise.depthMulStd > 0f) z *= 1f + noise.depthMulStd * gauss(rnd)
            if (noise.invalidRatio > 0f && rnd.nextFloat() < noise.invalidRatio) continue
            if (garbage) z *= 10f else if (z > MAX_DEPTH_M) continue
            if (z <= 0f) continue
            mm[v * k.width + u] = min((z * 1000f).roundToInt(), 65535).toShort()
        }
        return DepthFrame(
            tNs, if (noise.edgeSmoothPx > 0) edgeSmooth(mm, k, noise.edgeSmoothPx) else mm, null, k, reported, "synthetic", GuideImage(tNs, luma, k),
        )
    }

    /** 반경 [r] 정사각형 안에 깊이 불연속이 있는 유효 픽셀만 그 안 유효 깊이의 평균으로(무효는 무효로 둔다). */
    private fun edgeSmooth(mm: ShortArray, k: Intrinsics, r: Int): ShortArray {
        val out = mm.copyOf()
        for (v in 0 until k.height) for (u in 0 until k.width) {
            if (mm[v * k.width + u].toInt() == 0) continue
            var sum = 0L
            var n = 0
            var lo = Int.MAX_VALUE
            var hi = 0
            for (dv in -r..r) for (du in -r..r) {
                val uu = u + du
                val vv = v + dv
                if (uu !in 0 until k.width || vv !in 0 until k.height) continue
                val d = mm[vv * k.width + uu].toInt() and 0xFFFF
                if (d == 0) continue
                sum += d
                n++
                lo = min(lo, d)
                hi = max(hi, d)
            }
            if (hi - lo > EDGE_JUMP_RATIO * lo) out[v * k.width + u] = (sum / n).toInt().toShort()
        }
        return out
    }

    /** 세로 파지일 때 화면 기준 자세 = 센서 자세를 카메라 Z축으로 90° 회전(F2 실측). */
    private fun displayRotation(hold: Hold): Mat4 = when (hold) {
        Hold.PORTRAIT -> Quaternion.fromAxisAngle(Vec3(0f, 0f, 1f), (PI / 2).toFloat()).toMat4(Vec3.ZERO)
        Hold.LANDSCAPE -> Mat4.IDENTITY
    }

    /** 강체 변환 → PoseGl. */
    fun toPoseGl(t: Mat4): PoseGl {
        val q = Quaternion.fromMat4(t)
        val p = t.translation()
        return PoseGl(p.x, p.y, p.z, q.x, q.y, q.z, q.w)
    }

    private fun gauss(rnd: Random): Float {
        // Box–Muller (시드 고정, 언어 간 재현은 요구하지 않음)
        val u1 = max(rnd.nextDouble(), 1e-12)
        val u2 = rnd.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }
}

private const val EPS = 1e-5f
private const val BIG = 1e6f

private fun Vec3.c(a: Int) = when (a) {
    0 -> x
    1 -> y
    else -> z
}

private fun Float.sq() = this * this
