package hearspace.core.synth

import hearspace.core.geometry.*
import hearspace.core.pipeline.*
import hearspace.core.types.*
import java.io.File
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

/** Evaluation only: initial chest calibration feeds existing FastPath/SlowPath, never the app. */
object ChestGuidanceExperiment {
    private const val FPS = 30
    private const val T0_NS = 1_000_000_000_000L
    private const val SLOW_NS = 10_000_000L // simulated compute time, not measured device latency
    private val K = SyntheticGenerator.DEPTH_K
    private val headW = Vec3(0f, 1.5f, 0f)
    private data class Case(val suite: String, val mode: String, val turnDeg: Float = 90f, val turnS: Float = 1f,
                            val mountYawDeg: Float = 0f, val frontM: Float = .39f,
                            val slipDeg: Float = 0f, val noiseSeed: Int = 0, val sideM: Float = 0f)
    private data class Frame(val tS: Float, val pose: PoseFrame, val head: HeadPose)
    private fun fwd(deg: Float): Vec3 {
        val a = Math.toRadians(deg.toDouble()); return Vec3(sin(a).toFloat(), 0f, -cos(a).toFloat())
    }
    private fun wrap(a: Float) = ((a + 180f) % 360f + 360f) % 360f - 180f
    private fun angle(a: Vec3, b: Vec3) = Math.toDegrees(atan2((a cross b).norm().toDouble(), (a dot b).toDouble())).toFloat()
    private fun p95(xs: List<Float>): Float = if (xs.isEmpty()) Float.NaN else xs.sorted()[((xs.size - 1) * .95f).roundToInt()]
    private fun pose(c: Case, i: Int): Frame {
        val t = i.toFloat() / FPS
        val progress = ((t - 1f) / c.turnS).coerceIn(0f, 1f)
        val body = fwd(c.turnDeg * progress)
        val cameraForward = fwd(c.turnDeg * progress + c.mountYawDeg + c.slipDeg * progress)
        val pitch = Math.toRadians(10.0).toFloat()
        val f = cameraForward * cos(pitch) - Vec3.UP * sin(pitch)
        val r = cameraForward cross Vec3.UP
        val camera = headW - Vec3.UP * .5f + body * c.frontM
        return Frame(t, PoseFrame(T0_NS + i * 1_000_000_000L / FPS, TrackingState.TRACKING,
            Mat4.fromAxes(r, f cross r, f, camera)), HeadPose(headW, body))
    }
    private fun room(box: Box) = Scene(listOf(
        SceneItem("floor", HorizontalPlane(0f), false), SceneItem("target", box, true, HeightClass.BODY),
        SceneItem("left", Box(Vec3(-3.1f, 0f, -3.1f), Vec3(-3f, 3f, 3.1f)), false, structure = true),
        SceneItem("right", Box(Vec3(3f, 0f, -3.1f), Vec3(3.1f, 3f, 3.1f)), false, structure = true),
        SceneItem("front", Box(Vec3(-3f, 0f, -3.1f), Vec3(3f, 3f, -3f)), false, structure = true),
        SceneItem("back", Box(Vec3(-3f, 0f, 3f), Vec3(3f, 3f, 3.1f)), false, structure = true),
    ))
    private fun render(scene: Scene, fr: Frame, rnd: Random, noisy: Boolean): DepthFrame {
        val m = fr.pose.worldFromCam
        val pixels = ShortArray(K.width * K.height)
        for (v in 0 until K.height) for (u in 0 until K.width) {
            val dir = m.transformDir(Vec3((u - K.cx) / K.fx, (v - K.cy) / K.fy, 1f))
            val hit = scene.raycast(m.translation(), dir, fr.tS) ?: continue
            var z = hit.first
            if (z > SyntheticGenerator.MAX_DEPTH_M) continue
            if (noisy) z *= 1f + (rnd.nextFloat() * 2f - 1f) * .01f
            pixels[v * K.width + u] = (z * 1000).roundToInt().coerceIn(1, 65535).toShort()
        }
        return DepthFrame(fr.pose.tCaptureNs, pixels, null, K, m, "synthetic")
    }
    private class Window {
        var ticks = 0; var eligible = 0; var stopped = 0; var active = 0; var gap = 0; var maxGap = 0
        val azErrors = mutableListOf<Float>(); val headErrors = mutableListOf<Float>()
        fun sample(danger: Boolean, stop: Boolean, isActive: Boolean, az: List<Float>, headError: Float) {
            ticks++; if (isActive) active++; headErrors += headError
            if (danger) { eligible++; if (stop) stopped++ }
            gap = if (danger && !stop) gap + 1 else 0; maxGap = max(maxGap, gap)
            if (danger) azErrors += az
        }
    }
    private val columns = listOf("caseId", "suite", "mode", "turnDeg", "turnS", "mountYawDeg", "actualFrontM", "usedFrontM", "slipDeg", "noiseSeed", "sideM", "phase", "ticks", "eligibleTicks", "stopTicks", "stopCoverage", "maxMissRunMs", "azimuthFrameP95Deg", "headP95M", "activeFraction", "firstEligibleS", "firstStopDelayMs", "resets")
    private fun run(id: Int, c: Case, base: Config, trace: Appendable): List<Map<String, Any>> {
        val usedFront = if (c.mode == "CALIBRATED_MEASURED") c.frontM else .39f
        val cfg = base.copy(heading = if (c.mode == "FUSED") base.heading.copy(cameraSigmaDeg = 2.3f) else base.heading,
            head = base.head.copy(offsetFromCameraM = Vec3(0f, .5f, -usedFront)))
        val first = pose(c, 0).pose.worldFromCam
        val forwardC = first.rigidInverse().transformDir(fwd(0f)) // exact initial alignment, first frame only
        var calibrated = fwd(0f)
        val fast = FastPath(cfg, if (c.mode.startsWith("CALIBRATED")) { _ -> calibrated } else null)
        val slow = SlowPath(cfg)
        val end = fwd(c.turnDeg); val center = end * .95f + (end cross Vec3.UP) * c.sideM
        val box = Box(Vec3(center.x - .15f, .15f, center.z - .15f), Vec3(center.x + .15f, 1.2f, center.z + .15f))
        val scene = room(box); val rnd = Random(c.noiseSeed)
        var snapshot: ObstacleSnapshot? = null; var resets = 0
        var firstEligible = Float.NaN; var firstStop = Float.NaN
        val windows = linkedMapOf("TURN" to Window(), "EARLY_HOLD" to Window(), "LATE_HOLD" to Window())
        for (i in 0..((1f + c.turnS + 3f) * FPS).roundToInt()) {
            val fr = pose(c, i)
            calibrated = fr.pose.worldFromCam.transformDir(forwardC).horizontal().normalized()
            fast.compute(fr.pose, snapshot, fr.pose.tCaptureNs)
            var action = fast.takeMapAction(); if (action == hearspace.core.guidance.MapAction.RESET) resets++; slow.apply(action)
            val depth = render(scene, fr, rnd, c.noiseSeed != 0)
            snapshot = slow.process(depth, fast.headingW!!)
            val g = fast.compute(fr.pose, snapshot, fr.pose.tCaptureNs + SLOW_NS)
            action = fast.takeMapAction(); if (action == hearspace.core.guidance.MapAction.RESET) resets++; slow.apply(action)
            val phase = when { fr.tS < 1f -> "PRE"; fr.tS < 1f + c.turnS -> "TURN"; fr.tS < 2f + c.turnS -> "EARLY_HOLD"; else -> "LATE_HOLD" }
            val relativeCenter = headRelative(center, fr.head)
            val along = center dot fr.head.headingW
            val lateral = center dot fr.head.rightW
            // Explicit reference event: target CENTER in true 0.8m-wide forward corridor and STOP distance.
            // This is an evaluation definition, not a claim that every other box pose is safe.
            val danger = along in 0f..cfg.policy.stopM && abs(lateral) <= cfg.corridor.widthM / 2
            val targetCommands = g.commands.filter { cmd -> snapshot.obstacles.any {
                it.id == cmd.obstacleId && box.surfaceDistance(it.repPointW) <= cfg.map.voxelSizeM * 2
            } }
            val stop = targetCommands.any { it.band == Band.STOP }
            val errors = targetCommands.map { cmd ->
                val point = snapshot.obstacles.first { it.id == cmd.obstacleId }.repPointW
                abs(wrap(cmd.azimuthDeg - headRelative(point, fr.head).azimuthDeg))
            }
            val headError = (fast.head!!.positionW - headW).norm()
            val active = g.state == GuidanceState.NORMAL || g.state == GuidanceState.DEGRADED
            if (danger && firstEligible.isNaN()) firstEligible = fr.tS
            if (danger && stop && firstStop.isNaN()) firstStop = fr.tS
            windows[phase]?.sample(danger, stop, active, errors, headError)
            trace.appendLine(listOf(id, phase, fr.tS, danger, stop, g.state, angle(fast.headingW!!, fr.head.headingW), headError,
                targetCommands.size, if (errors.isEmpty()) "" else errors.max(), snapshot.obstacles.size,
                relativeCenter.azimuthDeg).joinToString(","))
        }
        return windows.map { (phase, w) -> linkedMapOf<String, Any>(
            "caseId" to id, "suite" to c.suite, "mode" to c.mode, "turnDeg" to c.turnDeg, "turnS" to c.turnS,
            "mountYawDeg" to c.mountYawDeg, "actualFrontM" to c.frontM, "usedFrontM" to usedFront, "slipDeg" to c.slipDeg,
            "noiseSeed" to c.noiseSeed, "sideM" to c.sideM, "phase" to phase, "ticks" to w.ticks,
            "eligibleTicks" to w.eligible, "stopTicks" to w.stopped,
            "stopCoverage" to if (w.eligible == 0) Float.NaN else w.stopped.toFloat() / w.eligible,
            "maxMissRunMs" to w.maxGap * 1000f / FPS, "azimuthFrameP95Deg" to p95(w.azErrors), "headP95M" to p95(w.headErrors),
            "activeFraction" to w.active.toFloat() / w.ticks, "firstEligibleS" to firstEligible,
            "firstStopDelayMs" to if (firstStop.isNaN()) Float.NaN else (firstStop - firstEligible) * 1000f, "resets" to resets)
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        val cfg = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(),
            File(System.getProperty("hearspace.runtimeOverride")).readText())
        val out = File(args.firstOrNull() ?: "core/build/chest-guidance-experiment").also { it.mkdirs() }
        val cases = buildList {
            for (a in listOf(-180f, -90f, 90f, 180f)) for (s in listOf(.5f, 1f)) for (mode in listOf("CURRENT", "FUSED", "CALIBRATED"))
                for (seed in listOf(0, 7)) add(Case("TURN_HOLD", mode, a, s, noiseSeed = seed))
            for (yaw in listOf(-15f, 15f)) for (mode in listOf("CURRENT", "FUSED", "CALIBRATED")) for (seed in listOf(0, 7))
                add(Case("MOUNT_YAW", mode, mountYawDeg = yaw, noiseSeed = seed))
            for (front in listOf(.1f, .2f)) for (a in listOf(90f, 180f)) for (mode in listOf("CURRENT", "CALIBRATED", "CALIBRATED_MEASURED"))
                add(Case("HEAD_OFFSET", mode, a, frontM = front))
            for (slip in listOf(-5f, 5f)) for (a in listOf(90f, 180f)) add(Case("MOUNT_SLIP", "CALIBRATED", a, slipDeg = slip))
            for (a in listOf(90f, 180f)) for (mode in listOf("CURRENT", "FUSED", "CALIBRATED")) add(Case("SIDE_CONTROL", mode, a, sideM = 1.2f))
        }
        val rows = File(out, "trace.csv").bufferedWriter().use { trace ->
            trace.appendLine("caseId,phase,tS,eligible,targetStop,state,headingErrorDeg,headErrorM,targetCommands,azimuthFrameMaxDeg,obstacles,targetCenterAzimuthDeg")
            cases.flatMapIndexed { id, c -> run(id, c, cfg, trace).also { println("case ${id + 1}/${cases.size} ${c.suite} ${c.mode}") } }
        }
        File(out, "cases.csv").writeText(buildString {
            appendLine(columns.joinToString(","))
            for (r in rows) appendLine(columns.joinToString(",") { k -> when (val v = r[k]) {
                is Float -> if (v.isNaN()) "" else String.format(Locale.ROOT, "%.6f", v)
                else -> v.toString()
            } })
        })
        check(cases.size == 82 && rows.size == 246)
        check(rows.all { (it["stopTicks"] as Int) <= (it["eligibleTicks"] as Int) })
        check(rows.filter { it["mode"] == "CALIBRATED_MEASURED" }.all { (it["headP95M"] as Float) < 1e-4f })
        check(rows.filter { it["suite"] == "TURN_HOLD" && it["mode"] == "CALIBRATED" }.all { (it["azimuthFrameP95Deg"] as Float).let { e -> e.isNaN() || e < .01f } })
        println("Chest guidance complete: ${cases.size} cases, ${rows.size} phase rows: ${out.absolutePath}")
    }
}
