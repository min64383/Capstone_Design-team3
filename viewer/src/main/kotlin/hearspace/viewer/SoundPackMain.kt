package hearspace.viewer

import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.types.AlertKind
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.SoundKind
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * M70 사운드팩: 앱이 낼 수 있는 소리를 앱과 같은 설정 층·렌더러(core [BinauralRenderer])로 WAV에 담는다. 사용자가 듣고 고칠 소리를 정한다.
 * 장면은 합성 명령(거리·방위·높이)이고 구간은 정책 거리 문턱으로 정한다. 말은 S10 말 소리 모음(data/relay/clips)에 재생 음량 ttsVolume을 곱해 렌더 뒤에 섞는다(S10은 TTS가 따로 섞음).
 * 결과: 트랙 WAV, 말 문장별 WAV, 전체 이어 듣기 all.wav, 목록 index.html. 앱 동작을 바꾸지 않는 듣기 도구다.
 * ponytail: 구간 문턱에 히스테리시스 없음, 거리는 블록마다 매끄럽게 바뀜(앱은 느린 경로 주기로 계단). 소리 비교용이라 충분.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrNull(0)?.takeIf { it.isNotEmpty() }
        ?: "data/soundpack/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")))
    require(!out.exists()) { "Output already exists: $out" }
    val layers = RelayFiles.appLayers() + listOfNotNull(args.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { File(it).readText() })
    val config = ConfigLoader.load(layers.first(), layers.drop(1))
    val pack = SoundPack(config, Hrtf.parse(Repo.hrtf.readBytes()), File(Repo.root, "data/relay/clips"))
    pack.write(out)
    println("sound pack: ${out.absolutePath}${File.separator}index.html")
}

class SoundPack(private val config: Config, private val hrtf: Hrtf, clipDir: File) {
    private val sr = config.audio.sampleRate
    private val n = config.audio.blockSize
    private val policy = config.policy
    /** texts.txt 순서(문장 목록 순서)의 (문장, 소리). */
    val speech: List<Pair<String, FloatArray>> = run {
        val clips = RelayClips.load(clipDir, sr)
        File(clipDir, "texts.txt").takeIf { it.isFile }?.readLines(Charsets.UTF_8).orEmpty()
            .mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { (h, t) -> clips[h]?.let { t to it } } }
    }

    private fun clip(text: String) = speech.firstOrNull { it.first == text }?.second ?: error("no clip for '$text' in data/relay/clips")

    /** 장면 하나. [cmds]는 시각(s) → 명령, [alerts]는 (시각, 종류), [talk]는 (시작 시각, 소리). 스테레오 교차 배열. */
    fun render(c: Config, durS: Double, cmds: (Double) -> List<AudioCmd>, alerts: List<Pair<Double, AlertKind>> = emptyList(),
               talk: List<Pair<Double, FloatArray>> = emptyList()): FloatArray {
        val r = BinauralRenderer(c, hrtf)
        val blocks = (durS * sr / n).toInt()
        val out = FloatArray(2 * n * blocks)
        val buf = FloatArray(2 * n)
        val pending = alerts.sortedBy { it.first }.toMutableList()
        for (b in 0 until blocks) {
            val t = b * n.toDouble() / sr
            while (pending.isNotEmpty() && pending[0].first <= t) r.alert(pending.removeAt(0).second)
            val speaking = talk.any { (t0, x) -> t >= t0 && t < t0 + x.size.toDouble() / sr }
            r.renderWithSpeech(GuidanceOutput((t * 1e9).toLong(), GuidanceState.NORMAL, cmds(t), 0f, null), buf, speaking)
            buf.copyInto(out, 2 * n * b)
        }
        for ((t0, x) in talk) {
            val s0 = (t0 * sr).toInt()
            for (i in x.indices) {
                val f = s0 + i
                if (2 * f + 1 >= out.size) break
                val v = x[i] * c.audio.ttsVolume
                out[2 * f] += v; out[2 * f + 1] += v
            }
        }
        return out
    }

    /** 정책 거리 문턱으로 구간(멈춤 < stopM ≤ 주의 ≤ warnMaxM < 조용 ≤ silentMaxM). 그 너머는 명령 없음. */
    fun band(d: Float): Band? = when {
        d < policy.stopM -> Band.STOP
        d <= policy.warnMaxM -> Band.WARN
        d <= policy.silentMaxM -> Band.SILENT
        else -> null
    }

    private fun cmd(id: Int, d: Float, azDeg: Float, heightM: Float) = band(d)?.let {
        AudioCmd(id, azDeg, d, it, if (heightM > -0.3f) SoundKind.HEAD_TONE else SoundKind.FLOOR_PULSE, 0f,
            inCorridor = abs(azDeg) <= 20f, heightDeltaM = heightM)
    }

    /** [fromM]에서 [toM]까지 [mps]로 움직이고(시작 [t0] s) 끝 거리에 머문다. */
    private fun move(id: Int, fromM: Float, toM: Float, mps: Float, azDeg: Float = 0f, heightM: Float = BODY_M, t0: Double = LEAD_S) =
        { t: Double ->
            val dir = if (toM < fromM) -1f else 1f
            val d = (fromM + dir * mps * (t - t0).coerceAtLeast(0.0).toFloat()).let { if (dir < 0) it.coerceAtLeast(toM) else it.coerceAtMost(toM) }
            listOfNotNull(cmd(id, d, azDeg, heightM))
        }

    private fun stay(id: Int, d: Float, azDeg: Float = 0f, heightM: Float = BODY_M) = { _: Double -> listOfNotNull(cmd(id, d, azDeg, heightM)) }

    data class Track(val file: String, val group: String, val title: String, val note: String, val pcm: FloatArray, val expectSound: Boolean = true)

    /** 주 음량을 [db]로, 말하는 동안 배율은 그만큼 낮춰 말하는 동안 크기를 지금과 같게(M70 설계 3.3절 후보). */
    fun candidate(db: Float): Config {
        val a = config.audio
        return config.copy(audio = a.copy(masterGainDb = db, ttsObstacleGain = a.ttsObstacleGain * 10f.pow((a.masterGainDb - db) / 20f)))
    }

    fun tracks(): List<Track> {
        val c = config
        val walk = 1.0f // 보행 속도 m/s(보통 걸음, 우리 녹화 어림)
        val approachS = LEAD_S + (3.2 - 0.6) / walk + 1.5
        val t = ArrayList<Track>()
        var k = 0
        fun add(group: String, title: String, note: String, pcm: FloatArray, expect: Boolean = true) {
            k++; t += Track("%02d.wav".format(k), group, title, note, pcm, expect)
        }
        // 알림음
        val alertNames = mapOf(AlertKind.START to "시작", AlertKind.READY to "준비됨", AlertKind.PAUSE to "일시정지",
            AlertKind.UNKNOWN to "확인 불가", AlertKind.WAITING to "준비 대기(대기 중 되풀이)")
        for ((kind, name) in alertNames) {
            val times = if (kind == AlertKind.WAITING) listOf(LEAD_S, LEAD_S + 1.0, LEAD_S + 2.0) else listOf(LEAD_S)
            add("알림음", name, "상태가 바뀔 때 한 번 나는 비공간음(양쪽 같음)", render(c, times.last() + 1.5, { emptyList() }, times.map { it to kind }))
        }
        // 장애물: 정면 몸통 높이
        add("장애물 다가옴", "걸어서 다가감 3.2 → 0.6 m", "정면·몸통 높이, 초속 ${walk} m. 2.5 m 안(주의)에서 소리가 나고 1 m 안(멈춤)에서 이어진다",
            render(c, approachS, move(1, 3.2f, 0.6f, walk)))
        add("장애물 다가옴", "천천히 다가감 3.2 → 0.6 m", "초속 0.5 m",
            render(c, LEAD_S + 2.6 / 0.5 + 1.5, move(1, 3.2f, 0.6f, 0.5f)))
        add("장애물 다가옴", "서 있음, 주의 거리 1.8 m", "다가오지 않으면 처음 0.7 s만 나고 조용해진다(사용자 결정 규칙)",
            render(c, LEAD_S + 3.0, stay(1, 1.8f)))
        add("장애물 다가옴", "서 있음, 멈춤 거리 0.8 m", "멈춤 구간은 서 있어도 이어진다", render(c, LEAD_S + 3.0, stay(1, 0.8f)))
        add("장애물 다가옴", "멀어짐 0.6 → 3.2 m", "멀어질 때. 멈춤 구간을 벗어나면 구간이 바뀔 때만 잠깐 난다",
            render(c, approachS, move(1, 0.6f, 3.2f, walk)))
        // 방향
        for ((az, name) in listOf(-90f to "왼쪽 90°", -45f to "왼쪽 45°", 0f to "정면", 45f to "오른쪽 45°", 90f to "오른쪽 90°", 180f to "뒤")) {
            add("방향", name, "2.4 → 1.1 m 다가감, 몸통 높이", render(c, LEAD_S + 1.3 / walk + 1.0, move(1, 2.4f, 1.1f, walk, az)))
        }
        // 높이
        for ((h, name) in listOf(-1.4f to "발 근처(머리보다 1.4 m 아래)", BODY_M to "몸통(0.6 m 아래)", 0f to "머리 높이")) {
            add("높이", name, "정면 2.4 → 1.1 m 다가감. 높을수록 음이 높다", render(c, LEAD_S + 1.3 / walk + 1.0, move(1, 2.4f, 1.1f, walk, 0f, h)))
        }
        // 두 물체
        add("두 물체", "정면 멈춤 0.8 m + 오른쪽 45° 다가옴", "멈춤 소리가 있으면 다른 소리는 ${c.sonify.duckDb.toInt()} dB 줄인다",
            render(c, LEAD_S + 2.0 / walk + 1.0, { s -> stay(1, 0.8f)(s) + move(2, 2.4f, 0.4f, walk, 45f)(s) }))
        // 말과 장애물
        val car = clip("차 다가옴")
        add("말과 장애물", "다가가는 중 ‘차 다가옴’", "말하는 동안 장애물 소리 × ${c.audio.ttsObstacleGain}",
            render(c, approachS, move(1, 3.2f, 0.6f, walk), talk = listOf(LEAD_S + 1.2 to car)))
        add("말과 장애물", "멈춤 중 ‘내려가는 계단, 1미터’", "멈춤 소리 위에 말",
            render(c, LEAD_S + 3.5, stay(1, 0.8f), talk = listOf(LEAD_S + 0.8 to clip("내려가는 계단, 1미터"))))
        // 크기 후보
        for ((db, name) in listOf(c.audio.masterGainDb to "A 지금", -6f to "B", 0f to "C")) {
            val cc = candidate(db)
            add("크기 후보", "$name: 주 음량 ${fmt(db)} dB", "말하는 동안 배율 ${"%.3f".format(cc.audio.ttsObstacleGain)}. 걸어서 다가감 + ‘차 다가옴’",
                render(cc, approachS, move(1, 3.2f, 0.6f, walk), talk = listOf(LEAD_S + 1.2 to car)))
        }
        // 말 크기 후보(사용자 "tts가 조금 큰거 같아" → 0.5를 고름, 2026-11-23): 주 음량·말하는 동안 배율은 지금 그대로
        val stairs = clip("내려가는 계단, 1미터")
        for (v in listOf(0.7f, 0.5f, 0.35f)) {
            val cc = c.copy(audio = c.audio.copy(ttsVolume = v))
            val name = if (v == c.audio.ttsVolume) "지금" else "지금 대비 ${fmt(20 * log10(v / c.audio.ttsVolume))} dB"
            add("말 크기 후보", "말 음량 $v ($name)", "걸어서 다가감 + ‘차 다가옴’, 이어서 멈춤 중 ‘내려가는 계단, 1미터’",
                render(cc, approachS, move(1, 3.2f, 0.6f, walk), talk = listOf(LEAD_S + 1.2 to car)) +
                    render(cc, LEAD_S + 3.0, stay(1, 0.8f), talk = listOf(LEAD_S + 0.8 to stairs)))
        }
        return t
    }

    fun write(dir: File) {
        val tracks = tracks()
        val gap = FloatArray(2 * (sr * 1.5).toInt())
        dir.mkdirs()
        File(dir, "speech").mkdirs()
        val starts = ArrayList<Double>()
        var all = FloatArray(0)
        val rows = StringBuilder()
        var group = ""
        for (tr in tracks) {
            val (rms, peak) = level(tr.pcm)
            check(!tr.expectSound || peak > 0f) { "silent track ${tr.file} ${tr.title}" }
            check(peak <= config.audio.limiterCeiling + 0.2f) { "track ${tr.file} peak $peak" } // 말은 리미터 뒤에 섞이므로 여유
            wav(File(dir, tr.file), tr.pcm, 2)
            starts += all.size / 2.0 / sr
            all += tr.pcm + gap
            if (tr.group != group) { group = tr.group; rows.append("<tr class=g><td colspan=4>${esc(group)}</td></tr>\n") }
            rows.append("<tr><td>${tr.file.removeSuffix(".wav")}</td><td><b>${esc(tr.title)}</b><br><small>${esc(tr.note)} · " +
                "전체 ${mmss(starts.last())} · RMS ${fmt(db(rms))} · 최고점 ${fmt(db(peak))} dBFS</small></td>" +
                "<td><audio controls preload=none src=\"${tr.file}\"></audio></td></tr>\n")
        }
        rows.append("<tr class=g><td colspan=4>말 (S10 TTS, 재생 음량 ${config.audio.ttsVolume})</td></tr>\n")
        speech.forEachIndexed { i, (text, x) ->
            val name = "speech/%02d.wav".format(i + 1)
            val st = FloatArray(2 * x.size) { x[it / 2] * config.audio.ttsVolume }
            wav(File(dir, name), st, 2)
            rows.append("<tr><td>말 ${i + 1}</td><td>${esc(text)}</td><td><audio controls preload=none src=\"$name\"></audio></td></tr>\n")
        }
        wav(File(dir, "all.wav"), all, 2)
        val a = config.audio
        File(dir, "index.html").writeText("""<!doctype html><html lang=ko><meta charset=utf-8><title>HEARSPACE 사운드팩</title>
<style>body{font-family:sans-serif;max-width:900px;margin:24px auto;padding:0 16px}table{border-collapse:collapse;width:100%}
td{border-bottom:1px solid #ddd;padding:6px;vertical-align:middle}tr.g td{font-weight:700;background:#eee}small{color:#555}audio{height:32px}</style>
<h1>HEARSPACE 사운드팩</h1>
<p>앱과 같은 설정 층(기본 + 앱 소리 층 + 하네스 + 상황 + 조합 C)과 렌더러로 만든 소리다. 크기는 S10에서 나는 크기 그대로이고(정규화 안 함), 이어폰으로 들어야 좌우·앞뒤가 들린다.
주 음량 ${fmt(a.masterGainDb)} dB, 말 음량 ${a.ttsVolume}, 말하는 동안 장애물 × ${a.ttsObstacleGain}. 걷는 장면은 합성(거리 구간은 정책 문턱 ${policy.stopM} / ${policy.warnMaxM} / ${policy.silentMaxM} m).</p>
<p><b>전체 이어 듣기</b> <audio controls preload=none src="all.wav"></audio> (트랙 사이 1.5 s)</p>
<table>
$rows</table></html>
""", Charsets.UTF_8)
    }

    private fun level(x: FloatArray): Pair<Float, Float> {
        var peak = 0f; var sum = 0.0; var cnt = 0
        for (v in x) { peak = maxOf(peak, abs(v)); if (abs(v) > 1e-5f) { sum += v.toDouble() * v; cnt++ } }
        return (if (cnt == 0) 0f else kotlin.math.sqrt(sum / cnt).toFloat()) to peak
    }

    private fun wav(f: File, x: FloatArray, ch: Int) {
        val data = ByteArray(x.size * 2)
        for (i in x.indices) {
            val s = (x[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt() // 중계 서버·SonificationExport와 같은 변환
            data[2 * i] = s.toByte(); data[2 * i + 1] = (s shr 8).toByte()
        }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort())
            .putInt(sr).putInt(sr * ch * 2).putShort((ch * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(data.size)
        f.outputStream().use { it.write(h.array()); it.write(data) }
    }

    private fun db(v: Float) = if (v <= 0f) Float.NEGATIVE_INFINITY else 20f * log10(v)
    private fun fmt(v: Float) = if (v.isInfinite()) "−∞" else "%.1f".format(v).replace('-', '−')
    private fun mmss(s: Double) = "%d:%04.1f".format((s / 60).toInt(), s % 60)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

    companion object {
        /** 장면 앞 무음(s). */
        const val LEAD_S = 0.3
        /** 몸통 높이 장애물의 머리 기준 높이(m, 어림). */
        const val BODY_M = -0.6f
    }
}
