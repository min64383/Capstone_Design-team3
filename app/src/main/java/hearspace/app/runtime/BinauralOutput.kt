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

    @Volatile
    private var track: AudioTrack? = null

    /** 출력 지연 추정(ms): 써 넣었지만 아직 재생되지 않은 프레임. 알 수 없으면 null. */
    @Volatile
    var latencyMs: Float? = null
        private set

    /** 재생 부족(끊김) 횟수. */
    val underruns: Int get() = track?.underrunCount ?: 0

    /** 출력이 죽었다(쓰기 실패 뒤 다시 만들기도 실패). 화면이 이것을 보고 안내를 멈추고 알린다(A1 TR2). */
    @Volatile
    var failed = false
        private set

    fun start() {
        if (running) return
        val t = open()
        track = t
        running = true
        thread = Thread({ loop(t) }, "HEARSPACEBinaural").apply { start() }
        Log.i(TAG, "BinauralOutput started: ${audio.sampleRate} Hz, block ${audio.blockSize}, buffer ${t.bufferSizeInFrames} frames")
    }

    /** 트랙을 만들어 재생을 시작한다. 실패하면 만든 트랙을 놓고 예외. */
    private fun open(): AudioTrack {
        val t = build()
        try {
            check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
            // 시스템 최소 버퍼(S10 약 80 ms)보다 줄여 출력 지연을 낮춘다(`audio.bufferBlocks`). 끊김 수는 close에서 기록
            t.setBufferSizeInFrames(audio.bufferBlocks * audio.blockSize)
            t.play()
        } catch (e: Exception) {
            t.release()
            throw e
        }
        return t
    }

    private fun loop(first: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(2 * audio.blockSize)
        val ts = AudioTimestamp()
        var t = first
        var written = 0L
        var reopened = false // 다시 만든 뒤 아직 한 번도 쓰지 못함: 또 실패하면 포기
        while (running) {
            render(buf)
            var off = 0
            while (running && off < buf.size) {
                val n = t.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                if (n < 0) {
                    // 오디오 서버 재시작·경로 상실 등(ERROR_DEAD_OBJECT): 트랙을 다시 만든다. 이 블록의 나머지는 버린다
                    Log.e(TAG, "AudioTrack.write failed: $n (reopened=$reopened)")
                    val next = if (reopened) null else reopen(t)
                    if (next == null) {
                        failed = true
                        running = false
                        break
                    }
                    t = next
                    reopened = true
                    written = 0L
                    off = 0
                    break
                }
                if (n > 0) reopened = false
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

    /** 죽은 트랙을 놓고 새로 연다(오디오 스레드). 실패하면 null. */
    private fun reopen(old: AudioTrack): AudioTrack? {
        track = null
        runCatching { old.release() }
        return runCatching { open() }.onFailure { Log.e(TAG, "AudioTrack reopen failed", it) }.getOrNull()?.also { track = it }
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
