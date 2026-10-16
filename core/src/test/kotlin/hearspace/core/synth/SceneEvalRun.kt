package hearspace.core.synth

import hearspace.core.replay.MapEvalWriter
import hearspace.core.replay.OfflineReplay
import hearspace.core.replay.ReplayListener
import hearspace.core.replay.SlowStep
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 무작위 합성 장면 일괄 재생(M30 기준 ②): 장면 시드 × 열화 강도마다 합성 세션을 쓰고 기본 설정으로 재생해 장면 지표
 * `<out>/<시드>/s<강도>/scene_eval.json`과 장면 요약 `<out>/<시드>/scene.json`을 남긴다. 세션 폴더는 재생 뒤 지운다.
 *
 *     ./gradlew :core:sceneEval [-Pscenes=200] [-Pseed=1] [-Pstrengths=0,0.5,1,1.5] [-Pout=..] [-PoverridesFile=..]
 *
 * 장면 지표 높이 상한은 2.5 m(합성은 장면 전체가 정답, M30 설계 표 3), 표본은 느린 경로 5단계마다(합성 세션은 짧다).
 */
fun main(args: Array<String>) {
    val opt = args.associate { a -> a.substringBefore('=') to a.substringAfter('=', "") }
    val scenes = opt["scenes"]?.toInt() ?: 200
    val seed0 = opt["seed"]?.toLong() ?: 1L
    val strengths = (opt["strengths"] ?: "0,0.5,1,1.5").split(',').map { it.trim().toFloat() }
    val out = File(opt["out"] ?: "build/scene-eval").absoluteFile
    val overrides = opt["overridesFile"]?.takeIf { it.isNotBlank() }?.let { File(it).readText() } ?: "{}"
    val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(), overrides)
    val noop = object : ReplayListener {
        override fun onSlowStep(step: SlowStep) = Unit
        override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) = Unit
    }
    val t0 = System.nanoTime()
    val done = AtomicInteger()
    val total = scenes * strengths.size
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    for (i in 0 until scenes) {
        val seed = seed0 + i
        pool.submit {
            try {
                val rs = RandomScenes.generate(seed)
                val dir = File(out, "%04d".format(seed)).apply { mkdirs() }
                File(dir, "scene.json").writeText(rs.summaryJson())
                for (s in strengths) {
                    val sDir = File(dir, "s$s").apply { mkdirs() }
                    if (File(sDir, MapEvalWriter.SCENE_FILE).isFile) { done.incrementAndGet(); continue }
                    val session = File(sDir, "session")
                    session.deleteRecursively()
                    SyntheticSessionWriter.write(SyntheticGenerator.generate(rs.scene, rs.walk, RandomScenes.noise(s, seed)), session, "R%04d".format(seed))
                    OfflineReplay(config, OfflineReplay.fixed(10f)).run(
                        session, MapEvalWriter(noop, sDir, session, config, everyN = 5, align = rs.alignment(), sceneHeightM = 2.5f),
                    )
                    session.deleteRecursively()
                    val n = done.incrementAndGet()
                    if (n % 20 == 0) println("$n/$total (${(System.nanoTime() - t0) / 1_000_000_000} s)")
                }
            } catch (e: Throwable) {
                System.err.println("seed $seed: $e")
                e.printStackTrace()
            }
        }
    }
    pool.shutdown()
    pool.awaitTermination(7, TimeUnit.DAYS)
    val sec = (System.nanoTime() - t0) / 1e9
    File(out, "run_info.json").writeText(
        "{\"scenes\": $scenes, \"seed\": $seed0, \"strengths\": $strengths, \"overrides\": ${overrides.trim()}, \"elapsedS\": ${"%.1f".format(sec)}}\n",
    )
    println("done $total runs in ${"%.1f".format(sec)} s → $out")
}
