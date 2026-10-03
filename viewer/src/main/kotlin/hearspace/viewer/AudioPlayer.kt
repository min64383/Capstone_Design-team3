package hearspace.viewer

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * 미리 렌더한 스테레오 PCM을 재생한다(`javax.sound.sampled`, JDK 내장). 재생 위치(표본 프레임) = 세션 시각이다.
 * 느린 재생은 지원하지 않는다(음높이·시간 단서가 바뀜, IMPROVE_SPEC §10.3).
 */
class AudioPlayer {
    private var pcm = ByteArray(0)
    private var sampleRate = 48_000
    private var line: SourceDataLine? = null
    private var writer: Thread? = null
    @Volatile private var playing = false
    @Volatile private var startFrame = 0L
    @Volatile private var pausedFrame = 0L

    val isPlaying get() = playing
    val totalFrames get() = pcm.size / BYTES_PER_FRAME.toLong()

    /** 새 소리를 싣는다(재생 중이면 멈춤). [stereo]는 L R 교차 float(−1~1). */
    fun load(stereo: FloatArray, rate: Int) {
        stop()
        sampleRate = rate
        pcm = ByteArray(stereo.size * 2)
        for (i in stereo.indices) {
            val s = (stereo[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            pcm[2 * i] = s.toByte()
            pcm[2 * i + 1] = (s shr 8).toByte()
        }
        pausedFrame = 0
    }

    /** 현재 재생 위치(프레임). */
    fun positionFrame(): Long {
        val l = line
        return if (playing && l != null) (startFrame + l.longFramePosition).coerceAtMost(totalFrames) else pausedFrame
    }

    fun play() {
        if (playing || pcm.isEmpty()) return
        stop() // 끝까지 재생돼 남은 줄을 닫는다
        if (pausedFrame >= totalFrames) pausedFrame = 0
        val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
        val l = AudioSystem.getSourceDataLine(format).apply { open(format, sampleRate / 10 * BYTES_PER_FRAME); start() }
        line = l
        startFrame = pausedFrame
        playing = true
        writer = Thread({
            var off = (startFrame * BYTES_PER_FRAME).toInt()
            val chunk = 2048 * BYTES_PER_FRAME
            while (playing && off < pcm.size) {
                val n = minOf(chunk, pcm.size - off)
                off += l.write(pcm, off, n)
            }
            if (playing) {
                l.drain()
                pausedFrame = totalFrames // 끝까지 재생됨
            }
            playing = false
        }, "viewer-audio").apply { isDaemon = true; start() }
    }

    /** 멈추고 그 위치를 기억한다. */
    fun pause() {
        if (!playing) return
        pausedFrame = positionFrame()
        stop()
    }

    /** [frame]으로 옮긴다. 재생 중이었으면 그 위치부터 계속 재생한다. */
    fun seek(frame: Long) {
        val wasPlaying = playing
        stop()
        pausedFrame = frame.coerceIn(0, totalFrames)
        if (wasPlaying) play()
    }

    private fun stop() {
        playing = false
        writer?.join(500)
        writer = null
        line?.let { it.stop(); it.flush(); it.close() }
        line = null
    }

    private companion object {
        const val BYTES_PER_FRAME = 4 // 16비트 스테레오
    }
}
