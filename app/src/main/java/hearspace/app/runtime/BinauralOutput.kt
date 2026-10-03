package hearspace.app.runtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import hearspace.app.TAG
import hearspace.core.types.AudioConfig

/**
 * 파이프라인용 오디오 출력(§9.4): 48 kHz 스테레오 float `AudioTrack`(저지연 성능 모드).
 * 오디오 스레드가 블록마다 [render]를 불러 채우고 쓴다. 팀원의 [AudioOutput](비프 테스트)과는 별개(DECISIONS M7).
 */
class BinauralOutput(private val audio: AudioConfig, private val render: (FloatArray) -> Unit) : AutoCloseable {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /** 출력 지연 추정(ms): 써 넣었지만 아직 재생되지 않은 프레임. 알 수 없으면 null. */
    @Volatile
    var latencyMs: Float? = null
        private set

    /** 재생 부족(끊김) 횟수. */
    val underruns: Int get() = track?.underrunCount ?: 0

    fun start() {
        if (running) return
        val t = build()
        check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
        // 시스템 최소 버퍼(S10 약 80 ms)보다 줄여 출력 지연을 낮춘다(`audio.bufferBlocks`). 끊김 수는 close에서 기록
        t.setBufferSizeInFrames(audio.bufferBlocks * audio.blockSize)
        track = t
        running = true
        t.play()
        thread = Thread({ loop(t) }, "HEARSPACEBinaural").apply { start() }
        Log.i(TAG, "BinauralOutput started: ${audio.sampleRate} Hz, block ${audio.blockSize}, buffer ${t.bufferSizeInFrames} frames")
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(2 * audio.blockSize)
        val ts = AudioTimestamp()
        var written = 0L
        while (running) {
            render(buf)
            var off = 0
            while (running && off < buf.size) {
                val n = t.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                if (n < 0) {
                    Log.e(TAG, "AudioTrack.write failed: $n")
                    running = false
                    break
                }
                off += n
            }
            written += off / 2
            if (t.getTimestamp(ts)) {
                // 쓴 프레임 − 재생된 프레임(타임스탬프 이후 경과분 보정). AudioTimestamp.nanoTime은 System.nanoTime 기준
                val played = ts.framePosition + (System.nanoTime() - ts.nanoTime) * audio.sampleRate / 1_000_000_000L
                latencyMs = (written - played) * 1000f / audio.sampleRate
            }
        }
    }

    private fun build(): AudioTrack {
        val format = AudioFormat.Builder()
            .setSampleRate(audio.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        check(min > 0) { "getMinBufferSize failed: $min" }
        val blockBytes = audio.blockSize * 2 * 4
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, audio.bufferBlocks * blockBytes))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    override fun close() {
        running = false
        thread?.join(1000)
        track?.let {
            Log.i(TAG, "BinauralOutput stopped: underruns ${it.underrunCount}, latency ${latencyMs}ms")
            runCatching { it.stop() }
            it.release()
        }
        thread = null
        track = null
    }
}
