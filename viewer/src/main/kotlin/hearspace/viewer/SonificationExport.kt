package hearspace.viewer

import hearspace.core.audio.SonificationSample
import hearspace.core.types.Config
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.SonifyMode
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sqrt

/** PC 전용. WAV 표본 위치가 CSV elapsedS의 유일한 기준이다. 실패한 출력은 .partial로 남는다. */
class SonificationExport(private val dir: File, private val config: Config, session: File, overrides: String) : AutoCloseable {
    private val csv: java.io.BufferedWriter
    private val wav: RandomAccessFile
    private var frame = 0L
    private var firstNs: Long? = null
    private lateinit var block: GuidanceOutput
    private val samples = ArrayList<SonificationSample>()
    private var closed = false

    init {
        require(config.sonify.mode == SonifyMode.RISK_CONTINUOUS) { "sonification export requires RISK_CONTINUOUS" }
        require(!dir.exists()) { "Output already exists: $dir. Choose a new directory." }
        check(dir.mkdirs()) { "Cannot create $dir" }
        File(dir, "default-config.json").writeText(Repo.defaultConfig.readText())
        File(dir, "overrides.json").writeText(overrides)
        File(dir, "run.txt").writeText("session=${session.canonicalPath}\nmode=${config.sonify.mode}\nmapping=${config.sonify.mapping}\nsampleRate=${config.audio.sampleRate}\nblockSize=${config.audio.blockSize}\nslowPathMs=${ReplayRunner.SLOW_PATH_MS}\nstatus=partial\n")
        csv = File(dir, "sonification.csv.partial").bufferedWriter()
        wav = RandomAccessFile(File(dir, "audio.wav.partial"), "rw")
        wav.write(ByteArray(44))
        csv.appendLine("elapsedS,frameStart,tBlockNs,state,phase,obstacleId,distanceM,azimuthDeg,band,inCorridor,closingMps,ttcS,ttcFinite,risk,active,targetPitchHz,pitchHz,targetGain,gain,duckGain,ducked,outputRms,outputPeak,heightDeltaM,mapping")
    }

    fun begin(g: GuidanceOutput) {
        block = g
        if (firstNs == null) firstNs = g.tBlockNs
        samples.clear()
    }

    fun sample(s: SonificationSample) { samples += s }

    fun end(out: FloatArray) {
        require(out.size == config.audio.blockSize * 2)
        check((frame + out.size / 2) * 4 <= 0xffffffffL - 36) { "WAV exceeds RIFF size limit" }
        var sum = 0.0
        var peak = 0.0
        // AudioPlayer와 같은 16-bit 변환. RMS/peak도 저장한 PCM에서 계산.
        for (v in out) {
            val pcm = (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            wav.write(pcm and 255); wav.write((pcm shr 8) and 255)
            val value = pcm / 32768.0
            sum += value * value
            peak = maxOf(peak, abs(value))
        }
        val rms = sqrt(sum / out.size)
        val head = listOf(frame.toDouble() / config.audio.sampleRate, frame, block.tBlockNs, block.state)
        fun row(values: List<Any?>) { csv.appendLine(values.joinToString(",") { it?.toString() ?: "" }) }
        if (samples.isEmpty()) {
            // 무음/알림만 있는 블록도 기록. '장애물 없음'으로 해석하지 않는다.
            row(head + listOf("NO_SOURCE") + List(16) { null } + listOf(rms, peak, null, config.sonify.mapping))
        } else for (s in samples) {
            val c = s.command
            row(head + listOf(if (c == null) "RELEASE" else "COMMAND", s.obstacleId,
                c?.distanceM, c?.azimuthDeg, c?.band, c?.inCorridor, s.closingMps,
                s.ttcS.takeIf { it.isFinite() }, s.ttcS.isFinite(), s.risk, s.active,
                s.targetPitchHz, s.pitchHz, s.targetGain, s.gain, s.duckGain,
                s.duckGain < 1f, rms, peak, c?.heightDeltaM, config.sonify.mapping))
        }
        frame += out.size / 2
    }

    fun complete() {
        check(frame > 0) { "No audio blocks" }
        val dataBytes = frame * 4
        fun le(v: Long, n: Int) { repeat(n) { wav.write(((v shr (8 * it)) and 255).toInt()) } }
        wav.seek(0)
        wav.writeBytes("RIFF"); le(36 + dataBytes, 4); wav.writeBytes("WAVEfmt ")
        le(16, 4); le(1, 2); le(2, 2); le(config.audio.sampleRate.toLong(), 4)
        le(config.audio.sampleRate * 4L, 4); le(4, 2); le(16, 2)
        wav.writeBytes("data"); le(dataBytes, 4)
        close()
        check(File(dir, "sonification.csv.partial").renameTo(File(dir, "sonification.csv")))
        check(File(dir, "audio.wav.partial").renameTo(File(dir, "audio.wav")))
        File(dir, "run.txt").appendText("status=complete\nfirstBlockNs=$firstNs\nframes=$frame\ndurationS=${frame.toDouble() / config.audio.sampleRate}\n")
        println("Sonification export: ${dir.absolutePath}")
    }

    override fun close() {
        if (!closed) { closed = true; try { csv.close() } finally { wav.close() } }
    }

    companion object {
        fun newDirectory(session: File): File = File(Repo.root,
            "data/sonification/${session.name}/${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))}-${java.util.UUID.randomUUID().toString().take(8)}")
    }
}
