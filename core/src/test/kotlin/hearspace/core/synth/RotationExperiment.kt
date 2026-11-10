package hearspace.core.synth

import hearspace.core.geometry.*
import hearspace.core.guidance.*
import hearspace.core.mapping.*
import hearspace.core.pipeline.*
import hearspace.core.types.*
import java.io.File
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

/** Evaluation only. Does not change Heading or app settings. See docs/ROTATION_EXPERIMENT.md. */
object RotationExperiment {
    private const val FPS = 30
    private const val STAND_S = 1f
    private const val HOLD_S = 3f
    private const val T0_NS = 1_000_000_000_000L
    private const val SLOW_NS = 10_000_000L // virtual duration, not device performance
    private val K = SyntheticGenerator.DEPTH_K
    private val columns = listOf("suite", "mode", "angleDeg", "turnS", "offsetM", "after", "noiseSeed", "headingFinalDeg", "headFinalM", "headingAfterP95Deg", "targetStopCoverage", "targetAzimuthP95Deg", "activeFraction", "occupied", "ghostFraction", "resetCount")
    private fun fwd(deg: Float): Vec3 { val a = Math.toRadians(deg.toDouble()); return Vec3(sin(a).toFloat(), 0f, -cos(a).toFloat()) }
    private fun angle(a: Vec3, b: Vec3) = Math.toDegrees(acos((a.normalized() dot b.normalized()).coerceIn(-1f, 1f).toDouble())).toFloat()
    private fun wrap(a: Float) = (a + 540f) % 360f - 180f
    private fun percentile(xs: List<Float>, q: Float = .95f): Float = if (xs.isEmpty()) Float.NaN else xs.sorted()[((xs.size - 1) * q).roundToInt()]
    private fun pose(tNs: Long, deg: Float, pos: Vec3): PoseFrame {
        val p = Math.toRadians(10.0).toFloat()
        val f = fwd(deg) * cos(p) - Vec3.UP * sin(p)
        val right = fwd(deg) cross Vec3.UP
        return PoseFrame(tNs, TrackingState.TRACKING, Mat4.fromAxes(right, f cross right, f, pos))
    }
    private data class Frame(val tS: Float, val pose: PoseFrame, val head: HeadPose)
    private fun trajectory(angleDeg: Float, turnS: Float, offsetM: Float, walk: Boolean = false, wrist: Boolean = false): List<Frame> {
        val count = ((STAND_S + turnS + HOLD_S) * FPS).roundToInt() + 1
        return (0 until count).map { i ->
            val t = i.toFloat() / FPS
            val yaw = angleDeg * ((t - STAND_S) / turnS).coerceIn(0f, 1f)
            val bodyYaw = if (wrist) 0f else yaw
            val heading = fwd(bodyYaw)
            val walked = if (walk) (t - STAND_S - turnS).coerceAtLeast(0f) * .5f else 0f
            val head = HeadPose(Vec3(0f, 1.5f, 0f) + heading * walked, heading)
            val camera = head.positionW - Vec3.UP * .5f + heading * offsetM
            Frame(t, pose(T0_NS + i * 1_000_000_000L / FPS, yaw, camera), head)
        }
    }
    private fun config(base: Config, mode: String, offsetM: Float): Config = base.copy(
        heading = if (mode == "FUSED") base.heading.copy(cameraSigmaDeg = 2.3f) else base.heading,
        head = base.head.copy(offsetFromCameraM = Vec3(0f, .5f, -offsetM)),
    )
    private fun result(suite: String, mode: String, a: Float, turn: Float, offset: Float, after: String, seed: Int = 0) = linkedMapOf<String, Any>(
        "suite" to suite, "mode" to mode, "angleDeg" to a, "turnS" to turn, "offsetM" to offset, "after" to after, "noiseSeed" to seed,
    )
    private fun headingRuns(base: Config): List<Map<String, Any>> {
        val out = mutableListOf<Map<String, Any>>()
        for (a in listOf(-180f, -90f, -45f, 45f, 90f, 180f)) for (turn in listOf(.5f, 1f, 2f)) for (offset in listOf(0f, .15f, .39f)) for (walk in listOf(false, true)) for (mode in listOf("CURRENT", "FUSED", "CHEST_YAW")) {
            val cfg = config(base, mode, offset); val h = Heading(cfg.heading)
            val errors = mutableListOf<Float>(); var last = 0f; var headError = 0f
            for (fr in trajectory(a, turn, offset, walk)) {
                val estimated = h.update(fr.pose)!!
                val chosen = if (mode == "CHEST_YAW") fr.pose.worldFromCam.axis(2).horizontal().normalized() else estimated
                last = angle(chosen, fr.head.headingW)
                headError = (HeadPose.fromCamera(fr.pose.worldFromCam.translation(), chosen, cfg.head.offsetFromCameraM).positionW - fr.head.positionW).norm()
                if (fr.tS >= STAND_S + turn) errors += last
            }
            out += result("HEADING", mode, a, turn, offset, if (walk) "WALK" else "HOLD").apply { put("headingFinalDeg", last); put("headFinalM", headError); put("headingAfterP95Deg", percentile(errors)) }
        }
        for (a in listOf(-90f, 90f)) for (mode in listOf("CURRENT", "FUSED", "CHEST_YAW")) {
            val cfg = config(base, mode, .39f); val h = Heading(cfg.heading); var last = 0f
            for (fr in trajectory(a, 1f, .39f, wrist = true)) {
                val estimated = h.update(fr.pose)!!
                last = angle(if (mode == "CHEST_YAW") fr.pose.worldFromCam.axis(2).horizontal() else estimated, fr.head.headingW)
            }
            out += result("WRIST", mode, a, 1f, .39f, "HOLD").apply { put("headingFinalDeg", last) }
        }
        return out
    }
    private fun room(box: Box) = Scene(listOf(
        SceneItem("floor", HorizontalPlane(0f), false), SceneItem("target", box, true, HeightClass.BODY),
        SceneItem("left", Box(Vec3(-3.1f, 0f, -3.1f), Vec3(-3f, 3f, 3.1f)), false, structure = true),
        SceneItem("right", Box(Vec3(3f, 0f, -3.1f), Vec3(3.1f, 3f, 3.1f)), false, structure = true),
        SceneItem("front", Box(Vec3(-3f, 0f, -3.1f), Vec3(3f, 3f, -3f)), false, structure = true),
        SceneItem("back", Box(Vec3(-3f, 0f, 3f), Vec3(3f, 3f, 3.1f)), false, structure = true),
    ))
    private fun target(deg: Float, distanceM: Float = .95f): Box {
        val c = fwd(deg) * distanceM
        return Box(Vec3(c.x - .15f, .15f, c.z - .15f), Vec3(c.x + .15f, 1.2f, c.z + .15f))
    }
    private fun fixed(box: Box, voxelM: Float): FixedMap {
        val out = mutableListOf<VoxelView>(); val lo = box.min; val hi = box.max
        for (ix in floor(lo.x / voxelM).toInt()..floor(hi.x / voxelM).toInt()) for (iy in floor(lo.y / voxelM).toInt()..floor(hi.y / voxelM).toInt()) for (iz in floor(lo.z / voxelM).toInt()..floor(hi.z / voxelM).toInt()) {
            val c = Vec3((ix + .5f) * voxelM, (iy + .5f) * voxelM, (iz + .5f) * voxelM)
            if (box.surfaceDistance(c) <= voxelM) out += VoxelView(ix, iy, iz, c, 100, 1f, 0L, 0f)
        }
        return FixedMap(out, 0f)
    }
    private fun render(scene: Scene, fr: Frame, rnd: Random, noisy: Boolean): DepthFrame {
        val m = fr.pose.worldFromCam; val camera = m.translation()
        val pixels = ShortArray(K.width * K.height)
        for (v in 0 until K.height) for (u in 0 until K.width) {
            val dir = m.transformDir(Vec3((u - K.cx) / K.fx, (v - K.cy) / K.fy, 1f))
            val hit = scene.raycast(camera, dir, fr.tS) ?: continue
            var z = hit.first
            if (z > SyntheticGenerator.MAX_DEPTH_M) continue
            if (noisy) z *= 1f + (rnd.nextFloat() * 2f - 1f) * .01f
            pixels[v * K.width + u] = (z * 1000).roundToInt().coerceIn(1, 65535).toShort()
        }
        return DepthFrame(fr.pose.tCaptureNs, pixels, null, K, m, "synthetic")
    }
    private fun pipelineRuns(base: Config): List<Map<String, Any>> {
        val rows = mutableListOf<Map<String, Any>>()
        for (suite in listOf("PERFECT_MAP", "DEPTH_PIPELINE")) for (a in listOf(90f, 180f)) for (turn in listOf(1f, 2f)) for (mode in listOf("CURRENT", "FUSED", "CHEST_YAW")) for (seed in if (suite == "DEPTH_PIPELINE") listOf(0, 7) else listOf(0)) {
            val cfg = config(base, mode, .39f); var camForward = fwd(0f)
            val fast = FastPath(cfg, if (mode == "CHEST_YAW") { _ -> camForward } else null)
            val box = target(a); val scene = room(box)
            val slow = SlowPath(cfg, if (suite == "PERFECT_MAP") fixed(box, cfg.map.voxelSizeM) else null)
            var snapshot: ObstacleSnapshot? = null; var count = 0; var stops = 0; var active = 0
            val azErrors = mutableListOf<Float>(); var lastHeadingError = 0f; val rnd = Random(seed)
            for (fr in trajectory(a, turn, .39f)) {
                camForward = fr.pose.worldFromCam.axis(2).horizontal().normalized()
                fast.compute(fr.pose, snapshot, fr.pose.tCaptureNs); slow.apply(fast.takeMapAction())
                val d = if (suite == "PERFECT_MAP") DepthFrame(fr.pose.tCaptureNs, ShortArray(K.width * K.height), null, K, fr.pose.worldFromCam, "synthetic") else render(scene, fr, rnd, seed != 0)
                snapshot = slow.process(d, fast.headingW ?: continue)
                val g = fast.compute(fr.pose, snapshot, fr.pose.tCaptureNs + SLOW_NS); slow.apply(fast.takeMapAction())
                if (fr.tS < STAND_S + turn + 1f) continue // last two seconds, after observation/confirmation warmup
                count++; if (g.state == GuidanceState.NORMAL || g.state == GuidanceState.DEGRADED) active++
                lastHeadingError = angle(fast.headingW!!, fr.head.headingW)
                val targetCommands = g.commands.filter { cmd -> snapshot.obstacles.any { it.id == cmd.obstacleId && box.surfaceDistance(it.repPointW) <= cfg.map.voxelSizeM * 2 } }
                if (targetCommands.any { it.band == Band.STOP }) stops++
                for (cmd in targetCommands) {
                    val point = snapshot.obstacles.first { it.id == cmd.obstacleId }.repPointW
                    azErrors += abs(wrap(cmd.azimuthDeg - headRelative(point, fr.head).azimuthDeg))
                }
            }
            rows += result(suite, mode, a, turn, .39f, "HOLD", seed).apply {
                put("headingFinalDeg", lastHeadingError); put("targetStopCoverage", stops.toFloat() / count); put("targetAzimuthP95Deg", percentile(azErrors)); put("activeFraction", active.toFloat() / count)
            }
        }
        return rows
    }
    private fun coordinateRuns(base: Config): List<Map<String, Any>> {
        val rows = mutableListOf<Map<String, Any>>()
        for ((yaw, shift) in listOf(0f to .1f, 5f to 0f, 5f to .1f, 0f to 1f)) for (mode in listOf("UNCOMPENSATED", "PERFECT_ANCHOR")) {
            val cfg = config(base, "CURRENT", .39f); val box = target(0f, 2f); val scene = room(box)
            val correction = Quaternion.fromAxisAngle(Vec3.UP, Math.toRadians(yaw.toDouble()).toFloat()).toMat4(Vec3(shift, 0f, 0f))
            val slow = SlowPath(cfg); var camForward = fwd(0f); val fast = FastPath(cfg) { _ -> camForward }
            val rnd = Random(0); var snapshot: ObstacleSnapshot? = null; var resets = 0; var ghost = Float.NaN; var occupied = 0
            for (i in 0..120) {
                val t = T0_NS + i * 1_000_000_000L / FPS
                val head = HeadPose(Vec3(0f, 1.5f, 0f), fwd(0f)); val truePose = pose(t, 0f, Vec3(0f, 1f, -.39f))
                val applied = if (i >= 60) correction else Mat4.IDENTITY
                val reported = applied * truePose.worldFromCam
                // Oracle upper bound: exact anchor transform is GIVEN, not inferred from a real ARCore session.
                val used = if (mode == "PERFECT_ANCHOR") applied.rigidInverse() * reported else reported
                camForward = used.axis(2).horizontal().normalized()
                val p = PoseFrame(t, TrackingState.TRACKING, used)
                fast.compute(p, snapshot, t); val action = fast.takeMapAction(); if (action == MapAction.RESET) resets++;slow.apply(action)
                val depth = render(scene, Frame(i.toFloat()/FPS, truePose, head), rnd, false).copy(worldFromCam = used)
                snapshot = slow.process(depth, fast.headingW!!)
                fast.compute(p, snapshot, t + SLOW_NS);slow.apply(fast.takeMapAction())
                if (i == 120) {
                    val pts = slow.map.voxels.occupied().map { if (mode == "PERFECT_ANCHOR") it.centerW else applied.rigidInverse().transformPoint(it.centerW) }
                    occupied = pts.size
                    val tau = cfg.map.voxelSizeM * sqrt(3f) / 2
                    ghost = if (pts.isEmpty()) Float.NaN else pts.count { scene.surfaceDistance(it, i.toFloat()/FPS) > tau }.toFloat() / pts.size
                }
            }
            rows += result("WORLD_CORRECTION", mode, yaw, 0f, shift, "HOLD").apply { put("occupied", occupied);put("ghostFraction", ghost);put("resetCount", resets) }
        }
        return rows
    }
    @JvmStatic fun main(args: Array<String>) {
        val cfg = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())
        val dest = File(args.firstOrNull() ?: "core/build/rotation-experiment");dest.mkdirs()
        val rows = headingRuns(cfg); println("heading and wrist cases ${rows.size}")
        val all = rows + pipelineRuns(cfg).also { println("pipeline cases ${it.size}") } + coordinateRuns(cfg).also { println("world correction cases ${it.size}") }
        val text = buildString { appendLine(columns.joinToString(","));for (r in all) appendLine(columns.joinToString(",") { k -> when (val v = r[k]) { is Float -> if (v.isNaN()) "" else String.format(Locale.ROOT, "%.6f", v);null -> "";else -> v.toString() } }) }
        File(dest, "cases.csv").writeText(text)
        check(all.size == 374) { "unexpected case count ${all.size}" }
        check(rows.filter { it["suite"] == "HEADING" && it["mode"] == "CHEST_YAW" }.all { (it["headingFinalDeg"] as Float) < .1f })
        check(rows.filter { it["suite"] == "WRIST" && it["mode"] == "CHEST_YAW" }.all { (it["headingFinalDeg"] as Float) > 89f })
        println("rotation experiment complete ${all.size} cases: ${dest.absolutePath}/cases.csv")
    }
}
