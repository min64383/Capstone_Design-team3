package hearspace.core.synth

import hearspace.core.geometry.*
import hearspace.core.guidance.Heading
import hearspace.core.types.*
import java.io.File
import java.util.Locale
import kotlin.math.*

/** Synthetic, evaluation-only rigid chest mount. No app behavior or default changes. */
object ChestMountExperiment {
    private const val FPS = 30
    private const val T0_NS = 1_000_000_000_000L
    private val HEAD_W = Vec3(0f, 1.5f, 0f)
    private val FORWARD_W = Vec3(0f, 0f, -1f)
    private val columns = listOf("suite", "mode", "turnDeg", "turnS", "mountYawDeg", "pitchDeg", "rollDeg", "cameraFrontM", "slipDeg", "headingFinalDeg", "headingTurnMaxDeg", "headFinalM", "invalidFrames")
    private data class Frame(val pose: PoseFrame, val bodyForwardW: Vec3, val tS: Float)
    private data class Case(val suite: String, val mode: String, val turnDeg: Float = 90f, val turnS: Float = 1f,
                            val yawDeg: Float = 0f, val pitchDeg: Float = 10f, val rollDeg: Float = 0f,
                            val frontM: Float = .39f, val slipDeg: Float = 0f)

    private fun forward(yawDeg: Float): Vec3 {
        val a = Math.toRadians(yawDeg.toDouble())
        return Vec3(sin(a).toFloat(), 0f, -cos(a).toFloat())
    }
    private fun angle(a: Vec3, b: Vec3): Float {
        // atan2 is better conditioned than acos near zero.
        return Math.toDegrees(atan2((a cross b).norm().toDouble(), (a dot b).toDouble())).toFloat()
    }
    private fun frames(c: Case): List<Frame> = (0..((1f + c.turnS + 3f) * FPS).roundToInt()).map { i ->
        val t = i.toFloat() / FPS
        val yaw = c.turnDeg * ((t - 1f) / c.turnS).coerceIn(0f, 1f)
        val body = forward(yaw)
        // After calibration, the phone slips around world up while its center remains fixed to the chest.
        val slip = c.slipDeg * ((t - 1f) / c.turnS).coerceIn(0f, 1f)
        val lensYaw = forward(yaw + c.yawDeg + slip)
        val pitch = Math.toRadians(c.pitchDeg.toDouble()).toFloat()
        val f = lensYaw * cos(pitch) - Vec3.UP * sin(pitch)
        val r = lensYaw cross Vec3.UP
        val down = f cross r
        val roll = Math.toRadians(c.rollDeg.toDouble()).toFloat()
        val x = r * cos(roll) + down * sin(roll)
        val y = down * cos(roll) - r * sin(roll)
        val camera = HEAD_W - Vec3.UP * .5f + body * c.frontM
        Frame(PoseFrame(T0_NS + i * 1_000_000_000L / FPS, TrackingState.TRACKING,
            Mat4.fromAxes(x, y, f, camera)), body, t)
    }

    private fun run(c: Case, cfg: Config, trace: Appendable): Map<String, Any> {
        val frames = frames(c)
        // Known initial body forward is supplied by a correct, explicit initial alignment.
        // The first frame alone is used. No future frames or true later heading enter this candidate.
        val initial = frames.first().pose.worldFromCam
        val calibratedForwardC = initial.rigidInverse().transformDir(FORWARD_W)
        val measuredHeadOffsetC = initial.rigidInverse().transformDir(HEAD_W - initial.translation())
        val traceKey = "${c.suite},${c.mode},${c.turnDeg},${c.turnS},${c.yawDeg},${c.pitchDeg},${c.rollDeg},${c.frontM},${c.slipDeg}"
        val estimator = Heading(if (c.mode == "FUSED") cfg.heading.copy(cameraSigmaDeg = 2.3f) else cfg.heading)
        var finalDeg = Float.NaN; var headFinalM = Float.NaN; var turnMax = 0f; var invalid = 0
        for (fr in frames) {
            val m = fr.pose.worldFromCam
            val raw = when (c.mode) {
                "CURRENT", "FUSED" -> estimator.update(fr.pose)!!
                "RAW_CAMERA" -> m.axis(2)
                else -> m.transformDir(calibratedForwardC)
            }.horizontal()
            if (raw.norm() < 1e-4f) {
                invalid++
                trace.appendLine("${traceKey},${fr.tS},,,false")
                continue
            }
            val chosen = raw.normalized()
            finalDeg = angle(chosen, fr.bodyForwardW)
            val estimatedHead = when (c.mode) {
                "MEASURED_RIGID" -> m.translation() + m.transformDir(measuredHeadOffsetC)
                else -> HeadPose.fromCamera(m.translation(), chosen, cfg.head.offsetFromCameraM).positionW
            }
            headFinalM = (estimatedHead - HEAD_W).norm()
            if (fr.tS in 1f..(1f + c.turnS)) turnMax = max(turnMax, finalDeg)
            trace.appendLine("${traceKey},${fr.tS},$finalDeg,$headFinalM,true")
        }
        return linkedMapOf("suite" to c.suite, "mode" to c.mode, "turnDeg" to c.turnDeg, "turnS" to c.turnS,
            "mountYawDeg" to c.yawDeg, "pitchDeg" to c.pitchDeg, "rollDeg" to c.rollDeg,
            "cameraFrontM" to c.frontM, "slipDeg" to c.slipDeg, "headingFinalDeg" to finalDeg,
            "headingTurnMaxDeg" to turnMax, "headFinalM" to headFinalM, "invalidFrames" to invalid)
    }

    @JvmStatic fun main(args: Array<String>) {
        val cfg = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())
        val out = File(args.firstOrNull() ?: "core/build/chest-mount-experiment").also { it.mkdirs() }
        val cases = buildList {
            for (a in listOf(-180f, -90f, 90f, 180f)) for (s in listOf(.5f, 1f, 2f))
                for (mode in listOf("CURRENT", "FUSED", "CALIBRATED")) add(Case("TURN_HOLD", mode, a, s))
            for (yaw in listOf(-15f, 0f, 15f)) for (pitch in listOf(0f, 30f, 60f, 90f))
                for (roll in listOf(-20f, 0f, 20f)) for (mode in listOf("RAW_CAMERA", "CALIBRATED"))
                    add(Case("MOUNT_ANGLE", mode, yawDeg = yaw, pitchDeg = pitch, rollDeg = roll))
            for (front in listOf(.10f, .20f, .39f)) for (a in listOf(-180f, -90f, 90f, 180f))
                for (mode in listOf("CALIBRATED", "MEASURED_RIGID")) add(Case("HEAD_OFFSET", mode, turnDeg = a, frontM = front))
            for (slip in listOf(-15f, -5f, 5f, 15f)) for (mode in listOf("RAW_CAMERA", "CALIBRATED"))
                add(Case("MOUNT_SLIP", mode, slipDeg = slip))
        }
        val rows = File(out, "trace.csv").bufferedWriter().use { trace ->
            trace.appendLine("suite,mode,turnDeg,turnS,mountYawDeg,pitchDeg,rollDeg,cameraFrontM,slipDeg,tS,headingErrorDeg,headErrorM,valid")
            cases.map { run(it, cfg, trace) }
        }
        File(out, "cases.csv").writeText(buildString {
            appendLine(columns.joinToString(","))
            for (row in rows) appendLine(columns.joinToString(",") { key ->
                when (val v = row[key]) {
                    is Float -> if (v.isNaN()) "" else String.format(Locale.ROOT, "%.6f", v)
                    else -> v.toString()
                }
            })
        })
        check(rows.size == 140)
        check(rows.filter { it["mode"] == "CALIBRATED" && it["suite"] != "MOUNT_SLIP" }
            .all { (it["headingFinalDeg"] as Float) < .01f && it["invalidFrames"] == 0 })
        check(rows.filter { it["mode"] == "MEASURED_RIGID" }.all { (it["headFinalM"] as Float) < 1e-5f })
        check(rows.filter { it["suite"] == "MOUNT_SLIP" && it["mode"] == "CALIBRATED" }
            .all { abs((it["headingFinalDeg"] as Float) - abs(it["slipDeg"] as Float)) < .01f })
        check(rows.count { it["mode"] == "RAW_CAMERA" && (it["invalidFrames"] as Int) > 0 } == 9)
        println("Chest mount experiment: ${rows.size} cases; calibration, offset, slip, and vertical-camera checks passed")
        println(File(out, "cases.csv").absolutePath)
    }
}
