package hearspace.viewer

import hearspace.core.relay.RelayBatch
import hearspace.core.relay.RelayEvent
import hearspace.core.relay.RelaySample
import hearspace.core.relay.RelayScreen
import hearspace.core.relay.RelayStart
import hearspace.core.screen.UserScreen
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 데모 중계의 PC 녹화 재생기(M66 설계 3.8절): S10 업로더(M67) 대역. 세션을 앱과 같은 설정 층으로 viewer 재생에 한 번 돌린 뒤,
 * 실제 시간에 맞춰 업로더와 같은 묶음을 올린다. 표본은 20 ms마다 그때까지의 마지막 블록, 알림은 모든 블록, 화면은 core [UserScreen.of],
 * 말은 상황 말 시각에 시작·소리 파일 길이 뒤에 끝(없으면 바로 끝). 세션이 끝나면 새 세션 이름으로 되풀이한다.
 * 앱의 상태 말·일시정지·말 등급 다툼은 없다(M67 실제 업로더로 본다).
 *
 * `./gradlew :viewer:relayReplay -Psession=<ID 일부> [-Pjitter=<ms>] [-Ploop=false] [-Pcheck=true]`
 * - jitter: 묶음마다 0~ms 무작위로 늦춰 보낸다(도착 순서가 바뀔 수 있음).
 * - check: 올리지 않고, 블록마다 값과 50 Hz 표본 값의 차이만 센다(설계 5절 참고 측정).
 */
fun main(args: Array<String>) {
    val opt = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val session = Sessions.resolve(opt["session"] ?: error("use -Psession=<session>"))
    val settings = RelayFiles.settings()
    val layers = RelayFiles.appLayers()
    println("relay replay: ${session.name} (app config layers) ...")
    val r = ReplayRunner.run(session, "{}", ReplayRunner.loadHrtf(), layers = layers)
    val stepNs = 1_000_000_000L / settings.sampleHz

    val clipDir = File(settings.clipDir).let { if (it.isAbsolute) it else File(Repo.root, settings.clipDir) }
    val clips = RelayClips.load(clipDir, r.config.audio.sampleRate)
    val need = r.speech.map { it.text }.distinct().filter { RelayClips.name(it) !in clips }
    if (need.isNotEmpty()) {
        val f = File(clipDir, "texts-needed.txt").apply { parentFile.mkdirs(); writeText(need.joinToString("\n", postfix = "\n")) }
        println("relay replay: ${need.size} speech texts have no clip -> ${Repo.relative(f)} (tools/relay/make-temp-clips.ps1 makes temporary ones)")
    }
    if (opt["check"] == "true") return check(r, stepNs)
    // 말 구간(세션 시각): 다음 말이 앞 말을 끊는다
    val ut = r.speech.sortedBy { it.tNs }
    val sayEnd = LongArray(ut.size) { i ->
        val dur = clips[RelayClips.name(ut[i].text)]?.let { it.size * 1_000_000_000L / r.config.audio.sampleRate } ?: 0L
        minOf(ut[i].tNs + dur, ut.getOrNull(i + 1)?.tNs ?: Long.MAX_VALUE)
    }
    fun speaking(t: Long) = ut.indices.any { t >= ut[it].tNs && t < sayEnd[it] }

    val url = (opt["url"]?.takeIf { it.isNotEmpty() } ?: "http://127.0.0.1:${settings.port}") + "/upload?k=" + URLEncoder.encode(settings.uploadKey, Charsets.UTF_8)
    val jitterMs = opt["jitter"]?.toLongOrNull() ?: 0L
    val loop = opt["loop"] != "false"
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    val sender = Executors.newScheduledThreadPool(4)
    val commit = System.getProperty("hearspace.gitHash") ?: "unknown"
    val t0 = r.blocks.first().tNs
    val tEnd = r.blocks.last().tNs
    val batchNs = settings.batchMs * 1_000_000L
    println("relay replay: ${"%.1f".format((tEnd - t0) / 1e9)} s, ${ut.size} speech lines, upload $url, jitter $jitterMs ms, loop $loop")
    do {
        val id = UUID.randomUUID().toString()
        val base = System.nanoTime() // S10 시계 = 이 PC의 nanoTime(세션 시각 t → t − t0 + base)
        fun live(t: Long) = t - t0 + base
        val needStart = java.util.concurrent.atomic.AtomicBoolean(true)
        var seq = 0L
        var grid = t0
        var lastSampleBlock = -1
        var alertBlock = -1
        var nextSay = 0
        var nextEnd = 0
        var lastScreen: UserScreen? = null
        var k = 1L
        while (true) {
            val wake = base + k * batchNs
            Thread.sleep(maxOf(0L, (wake - System.nanoTime()) / 1_000_000))
            val now = System.nanoTime()
            val cursor = now - base + t0 // 지금의 세션 시각
            val samples = ArrayList<RelaySample>()
            val screens = ArrayList<RelayScreen>()
            val events = ArrayList<RelayEvent>()
            while (grid <= minOf(cursor, tEnd)) {
                val i = r.blockIndexAt(grid)
                if (i >= 0 && i != lastSampleBlock) {
                    lastSampleBlock = i
                    val b = r.blocks[i]
                    samples += RelaySample(live(b.tNs), b.g.state, b.g.headingDeg, speaking(b.tNs), b.g.commands)
                    val c = b.g.commands.firstOrNull()
                    val s = UserScreen.of(true, null, false, b.g.state, r.slowAt(b.tNs)?.snapshot?.mapHealth, c?.band, c?.azimuthDeg)
                    if (s != lastScreen) { lastScreen = s; screens += RelayScreen(live(b.tNs), s, null) }
                }
                grid += stepNs
            }
            val last = r.blockIndexAt(minOf(cursor, tEnd))
            while (alertBlock < last) {
                alertBlock++
                r.blocks[alertBlock].g.alert?.let { events += RelayEvent.Alert(live(r.blocks[alertBlock].tNs), it) }
            }
            while (nextSay < ut.size && ut[nextSay].tNs <= cursor) { events += RelayEvent.SayStart(live(ut[nextSay].tNs), ut[nextSay].text); nextSay++ }
            while (nextEnd < nextSay && sayEnd[nextEnd] <= cursor) {
                // 다음 말에 끊긴 말은 끝 사건 없이 새 말이 대신한다
                if (sayEnd[nextEnd] != ut.getOrNull(nextEnd + 1)?.tNs) events += RelayEvent.SayEnd(live(sayEnd[nextEnd]))
                nextEnd++
            }
            val batch = RelayBatch(id, seq++, now, if (needStart.getAndSet(false)) RelayStart(commit, layers) else null, samples, events.sortedBy { it.tNs }, screens)
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString(batch.toJson())).build()
            sender.schedule({
                // 올리지 못한 묶음은 버리고 계속한다(M67 업로더와 같음)
                http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete { res, err ->
                    if (res?.statusCode() == 409) needStart.set(true)
                    else if (err != null || res.statusCode() != 200) println("relay replay: upload ${res?.statusCode() ?: err}")
                }
            }, if (jitterMs > 0) (Math.random() * jitterMs).toLong() else 0L, TimeUnit.MILLISECONDS)
            if (cursor > tEnd) break
            k++
        }
        println("relay replay: session ${session.name} done${if (loop) ", again" else ""}")
    } while (loop)
    sender.shutdown()
    sender.awaitTermination(5, TimeUnit.SECONDS)
}

/** 블록마다 첫 음원과, 그 시각에 서버가 쓸 50 Hz 표본(시각 이하 마지막 격자 표본)의 첫 음원 차이. */
private fun check(r: ReplayResult, stepNs: Long) {
    val t0 = r.blocks.first().tNs
    val gridBlock = ArrayList<Pair<Long, Int>>() // (격자 시각, 그때 마지막 블록)
    var g = t0
    while (g <= r.blocks.last().tNs) { gridBlock += g to r.blockIndexAt(g); g += stepNs }
    val gridT = LongArray(gridBlock.size) { gridBlock[it].first }
    val dAz = ArrayList<Double>()
    var bandDiff = 0
    for (b in r.blocks) {
        val j = gridBlock[lastAtOrBefore(gridT, b.tNs)].second
        val a = b.g.commands.firstOrNull()
        val s = r.blocks[j].g.commands.firstOrNull()
        if ((a == null) != (s == null) || a?.band != s?.band) bandDiff++
        if (a != null && s != null) dAz += abs(((a.azimuthDeg - s.azimuthDeg + 540f) % 360f) - 180f).toDouble()
    }
    dAz.sort()
    fun q(p: Double) = if (dAz.isEmpty()) Double.NaN else dAz[((dAz.size - 1) * p).toInt()]
    println("relay check ${r.sessionId}: blocks ${r.blocks.size}, both with source ${dAz.size}, |d azimuth| p50 %.2f p95 %.2f max %.2f deg, first-source band differs in %d blocks (%.2f%%)"
        .format(q(0.5), q(0.95), dAz.lastOrNull() ?: Double.NaN, bandDiff, 100.0 * bandDiff / r.blocks.size))
}
