package hearspace.viewer

import hearspace.core.replay.VideoDemoSettings
import hearspace.core.replay.VideoScene
import hearspace.core.replay.VideoSceneRunner
import hearspace.core.types.ConfigLoader
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.MiniJson
import java.io.File

/**
 * 영상 데모(M73 설계 3.4절): `tools/demo/scenes/` 아래 장면 파일마다 core 재현 실행기를 앱 설정 층으로 돌려 `videodemo.json` outDir에
 * `<장면>.wav`(2채널 16 bit)와 `<장면>.json`(화면·말 시간표, 확인용 정답)을 쓴다. 말 소리는 중계 말 소리 모음(S10 TTS)을 쓴다.
 * 영상과 합치기는 `python tools/demo/video_demo.py`.
 *
 * `./gradlew :viewer:videoDemo [-Pscene=<id>]`
 */
fun main(args: Array<String>) {
    val opt = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val demo = File(Repo.root, "tools/demo")
    val settingsText = File(demo, "videodemo.json").readText()
    val settings = VideoDemoSettings.parse(settingsText)
    val layers = RelayFiles.appLayers()
    val config = ConfigLoader.load(layers.first(), layers.drop(1))
    val hrtf = ReplayRunner.loadHrtf()
    val relay = RelayFiles.settings()
    val clips = RelayClips.load(File(relay.clipDir).let { if (it.isAbsolute) it else File(Repo.root, relay.clipDir) }, config.audio.sampleRate)
    val outDir = ((MiniJson.parse(settingsText) as JsonObject).fields["outDir"] as JsonString).value
    val out = File(outDir).let { if (it.isAbsolute) it else File(Repo.root, outDir) }.apply { mkdirs() }
    val only = opt["scene"]?.takeIf { it.isNotEmpty() }
    val files = File(demo, "scenes").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
    for (f in files) {
        val scene = VideoScene.parse(f.readText())
        if (only != null && scene.id != only) continue
        val runner = VideoSceneRunner(scene, config, settings, hrtf, { clips[RelayClips.name(it)] })
        val r = runner.run()
        writeStereoWav(File(out, "${scene.id}.wav"), r.audio, r.sampleRate)
        File(out, "${scene.id}.json").writeText(VideoSceneRunner.timelineJson(scene, r))
        println("video demo ${scene.id}: %.2f s, %d screen changes -> %s".format(r.audio.size / 2.0 / r.sampleRate, r.screens.size, Repo.relative(out)))
        for (s in r.speech) println("  say %6.2f–%6.2f s  %s".format(s.startNs / 1e9, s.endNs / 1e9, s.text))
        for ((t, sc) in r.screens) println("  screen %6.2f s  %s %s %s".format(t / 1e9, sc.title ?: "", (sc.visual as? hearspace.core.screen.UserScreen.Visual.Arrow)?.let { "arrow %.0f° %s".format(it.azimuthDeg, if (it.stop) "STOP" else "") } ?: sc.visual.toString(), sc.label ?: ""))
        if (runner.missing.isNotEmpty()) println("  WARNING speech clips missing: ${runner.missing}")
    }
}

/** 2채널 교차 ±1 → 16-bit PCM WAV(중계 서버·사운드팩과 같은 변환). */
private fun writeStereoWav(f: File, x: FloatArray, rate: Int) {
    val data = ByteArray(x.size * 2)
    for (i in x.indices) {
        val s = (x[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
        data[2 * i] = s.toByte(); data[2 * i + 1] = (s shr 8).toByte()
    }
    val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
        .putInt(rate).putInt(rate * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(data.size)
    f.outputStream().use { it.write(h.array()); it.write(data) }
}
