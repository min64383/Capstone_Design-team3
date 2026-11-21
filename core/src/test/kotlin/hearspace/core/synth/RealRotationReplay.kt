package hearspace.core.synth

import hearspace.core.guidance.Heading
import hearspace.core.replay.OfflineReplay
import hearspace.core.session.SessionReader
import hearspace.core.types.ConfigLoader
import hearspace.core.types.JsonObject
import hearspace.core.types.MiniJson
import java.io.File
import kotlin.math.abs

/** Recorded rotation comparison. Camera yaw is a reference proxy, not measured body heading. */
object RealRotationReplay {
    private data class Variant(val name: String, val profiles: List<String>)

    @JvmStatic fun main(args: Array<String>) {
        val root = File(System.getProperty("hearspace.repo"))
        val base = File(root, "app/src/main/assets/config/default.json").readText()
        val out = File(args.first()).also { require(!it.exists()) { "Use a new output directory: $it" }; it.mkdirs() }
        val profiles = File(root, "tools/measurement/rotation-ab")
        val common = File(profiles, "measured-common.json").readText()
        val chest = File(profiles, "measured-chest.json").readText()
        val fused = File(profiles, "fused.json").readText()
        val near = File(root, "app/src/main/assets/config/near-stop.experimental.override.json").readText()
        val systemAgeMs = ConfigLoader.load(base).policy.maxInfoAgeMs
        val systemAge = """{"policy":{"nearStop":{"maxAgeMs":$systemAgeMs}}}"""
        val variants = listOf(
            Variant("movement", emptyList()), Variant("fused", listOf(fused)),
            Variant("chest", listOf(chest)), Variant("near", listOf(chest, near)),
            Variant("near_system_age", listOf(chest, near, systemAge)),
        )
        File(out, "summary.csv").bufferedWriter().use { summary ->
            summary.appendLine("session,scene,variant,turnStartS,turnEndS,firstStopFromTurnStartMs,stopFromTurnStartS,earlyStopFromTurnStartS,cameraYawErrorOver5Fraction,inputWarnings")
            val sessions = File(profiles, "sessions.csv").readLines().drop(1).filter { it.isNotBlank() }
            for (line in sessions) {
                val cells = line.split(',')
                val id = cells[0]
                val turnStartS = cells[2].toDouble()
                val turnEndS = cells[3].toDouble()
                val session = File(root, "testdata/sessions/$id")
                val reader = SessionReader(session)
                val startNs = reader.rows.first().tNs
                val cameraYaw = reader.poseFrames().associate { p ->
                    p.tCaptureNs to Heading.toDeg(p.worldFromCam.axis(2).horizontal().normalized()).toDouble()
                }
                for (variant in variants) {
                    var merged = MiniJson.parse(common) as JsonObject
                    for (profile in variant.profiles) merged = merge(merged, MiniJson.parse(profile) as JsonObject)
                    val overrides = MiniJson.write(merged)
                    val config = ConfigLoader.load(base, overrides)
                    val run = File(out, "$id/${variant.name}")
                    val replay = OfflineReplay(config, OfflineReplay.fixed(10f))
                    run.mkdirs()
                    val debug = if (config.policy.nearStop != null) File(run, "near_stop_debug.csv").bufferedWriter() else null
                    debug.use { writer ->
                        writer?.appendLine("tCaptureNs,roiPoints,cells,sizeCandidates,maxObservations,published")
                        if (writer != null) replay.nearStopFrameObserver = { timeNs, evidence ->
                            writer.appendLine(listOf(timeNs, evidence.roiPoints, evidence.cells,
                                evidence.sizeCandidates, evidence.maxObservations, evidence.published).joinToString(","))
                        }
                        replay.run(session, run, overrides, "fixed:10.0ms")
                    }
                    File(run, "input_warnings.txt").writeText(replay.warnings.joinToString("\n"))
                    val rows = File(run, "guidance.csv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }
                    val blocks = rows.groupBy { it[0].toLong() }.toSortedMap()
                    val times = blocks.keys.toList()
                    var stopS = 0.0
                    var earlyS = 0.0
                    var firstStop: Double? = null
                    var over5 = 0
                    var headingBlocks = 0
                    for ((index, ns) in times.withIndex()) {
                        val tS = (ns - startNs) / 1e9
                        if (tS < turnStartS) continue
                        val commands = blocks.getValue(ns)
                        val stops = commands.filter { it[7] == "STOP" }
                        if (stops.isNotEmpty() && firstStop == null) firstStop = tS
                        val nextNs = times.getOrNull(index + 1) ?: ns
                        val dtS = (nextNs - ns) / 1e9
                        if (stops.isNotEmpty()) stopS += dtS
                        if (stops.any { it[4].toInt() < 0 }) earlyS += dtS
                        val row = commands.first()
                        val expected = cameraYaw[row[1].toLong()]
                        val actual = row[10].toDoubleOrNull()
                        if (expected != null && actual != null) {
                            val delta = ((actual - expected + 540) % 360) - 180
                            if (abs(delta) > 5) over5++
                            headingBlocks++
                        }
                    }
                    summary.appendLine(listOf(id, cells[1], variant.name, turnStartS, turnEndS,
                        firstStop?.let { (it - turnStartS) * 1000 }, stopS, earlyS,
                        if (headingBlocks > 0) over5.toDouble() / headingBlocks else null,
                        replay.warnings.size).joinToString(",") { it?.toString().orEmpty() })
                    summary.flush()
                    println("$id ${variant.name}: first STOP from turn start ${firstStop?.minus(turnStartS)} s; early STOP $earlyS s; warnings ${replay.warnings.size}")
                }
            }
        }
        println("Rotation comparison: ${out.absolutePath}")
    }

    private fun merge(base: JsonObject, override: JsonObject): JsonObject =
        JsonObject(LinkedHashMap(base.fields).also { out ->
            for ((key, value) in override.fields) {
                val old = out[key]
                out[key] = if (old is JsonObject && value is JsonObject) merge(old, value) else value
            }
        })
}
