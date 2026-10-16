package hearspace.core.synth

import hearspace.core.replay.MapEvalWriter
import hearspace.core.replay.OfflineReplay
import hearspace.core.replay.RunLogWriter
import hearspace.core.session.SessionFormat
import hearspace.core.types.ConfigLoader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 무작위 합성 장면 일괄 재생(M30 기준 ②): 장면 시드 × 열화 강도마다 합성 세션을 쓰고 기본 설정으로 재생해 장면 지표
 * `<out>/<시드>/s<강도>/scene_eval.json`과 장면 요약 `<out>/<시드>/scene.json`을 남긴다. 세션 폴더는 재생 뒤 깊이 영상만 지운다.
 * 안내 기록(`guidance.csv` 등)도 남겨 `scene_eval.py synth`가 `metrics.py`로 마주침 검출(STOP)을 센다(M32).
 *
 *     ./gradlew :core:sceneEval [-Pscenes=200] [-Pseed=1] [-Pstrengths=0,0.5,1,1.5] [-Pout=..] [-PoverridesFile=..] [-PrawEveryN=1] [-Pthreads=N]
 *
 * `rawEveryN` 3이면 원시 깊이를 새 깊이 3장에 1장만 쓴다(원시 깊이 약 10 Hz 흉내, `depth.source: RAW` 설정과 함께, M32).
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
    val rawEveryN = opt["rawEveryN"]?.takeIf { it.isNotBlank() }?.toInt() ?: 1
    val threads = opt["threads"]?.takeIf { it.isNotBlank() }?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(), overrides)
    val t0 = System.nanoTime()
    val done = AtomicInteger()
    val total = scenes * strengths.size
    val pool = Executors.newFixedThreadPool(threads)
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
                    SyntheticSessionWriter.write(
                        SyntheticGenerator.generate(rs.scene, rs.walk, RandomScenes.noise(s, seed)), session, "R%04d".format(seed), rawEveryN = rawEveryN,
                    )
                    val log = RunLogWriter(sDir, session, overrides.trim(), "fixed 10 ms")
                    OfflineReplay(config, OfflineReplay.fixed(10f)).run(
                        session, MapEvalWriter(log, sDir, session, config, everyN = 5, align = rs.alignment(), sceneHeightM = 2.5f),
                    )
                    listOf(SessionFormat.DEPTH_DIR, SessionFormat.RAW_DEPTH_DIR, SessionFormat.DEPTH_CONF_DIR).forEach { File(session, it).deleteRecursively() }
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
        "{\"scenes\": $scenes, \"seed\": $seed0, \"strengths\": $strengths, \"rawEveryN\": $rawEveryN, \"overrides\": ${overrides.trim()}, \"elapsedS\": ${"%.1f".format(sec)}}\n",
    )
    println("done $total runs in ${"%.1f".format(sec)} s → $out")
}
