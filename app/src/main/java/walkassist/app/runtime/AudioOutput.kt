package walkassist.app.runtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import walkassist.app.TAG
import walkassist.core.audio.SimpleBeepRenderer
import walkassist.core.types.AudioCmd
import walkassist.core.types.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * core에서 만든 stereo PCM을 Android AudioTrack으로 출력한다.
 *
 * 항상 가장 최신 AudioCmd 하나만 사용한다.
 */
class AudioOutput(
    config: Config,
) : AutoCloseable {

    private val audioConfig =
        config.audio

    private val renderer =
        SimpleBeepRenderer(
            audio = config.audio,
            policy = config.policy,
        )

    /*
     * 프로젝트 원칙:
     * queue가 아니라 최신 값 하나만 유지한다.
     */
    private val latestCommand =
        AtomicReference<AudioCmd?>(null)

    @Volatile
    private var running = false

    private var audioThread: Thread? = null

    private var audioTrack: AudioTrack? = null

    /**
     * 오디오 출력을 시작한다.
     */
    fun start() {

        if (running) {
            return
        }

        val track =
            createAudioTrack()

        check(
            track.state ==
                    AudioTrack.STATE_INITIALIZED
        ) {
            "AudioTrack initialization failed"
        }

        audioTrack = track
        running = true

        track.play()

        audioThread =
            Thread(
                {
                    audioLoop(track)
                },
                "WalkAssistAudio",
            ).apply {
                start()
            }

        Log.i(
            TAG,
            "AudioOutput started: " +
                    "${audioConfig.sampleRate} Hz, " +
                    "${audioConfig.blockSize} frames"
        )
    }

    /**
     * 가장 최신 장애물 음향 명령을 넣는다.
     */
    fun submit(
        command: AudioCmd?,
    ) {
        latestCommand.set(command)
    }

    /**
     * 현재 장애물음을 즉시 없앤다.
     */
    fun clear() {
        latestCommand.set(null)
    }

    private fun audioLoop(
        track: AudioTrack,
    ) {

        Process.setThreadPriority(
            Process.THREAD_PRIORITY_AUDIO
        )

        val buffer =
            ShortArray(
                audioConfig.blockSize * 2
            )

        while (running) {

            renderer.render(
                command =
                latestCommand.get(),
                output = buffer,
            )

            var offset = 0

            while (
                running &&
                offset < buffer.size
            ) {

                val written =
                    track.write(
                        buffer,
                        offset,
                        buffer.size - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )

                if (written < 0) {

                    Log.e(
                        TAG,
                        "AudioTrack.write failed: $written"
                    )

                    running = false
                    break
                }

                offset += written
            }
        }
    }

    private fun createAudioTrack(): AudioTrack {

        val channelMask =
            AudioFormat.CHANNEL_OUT_STEREO

        val encoding =
            AudioFormat.ENCODING_PCM_16BIT

        val minimumBuffer =
            AudioTrack.getMinBufferSize(
                audioConfig.sampleRate,
                channelMask,
                encoding,
            )

        check(minimumBuffer > 0) {
            "AudioTrack.getMinBufferSize failed: $minimumBuffer"
        }

        val blockBytes =
            audioConfig.blockSize *
                    2 * // stereo
                    2   // 16-bit = 2 bytes

        /*
         * 너무 작은 버퍼는 끊김을 만들 수 있으므로
         * 최소 버퍼와 4 audio block 중 큰 값을 사용.
         */
        val bufferSizeBytes =
            maxOf(
                minimumBuffer,
                blockBytes * 4,
            )

        val attributes =
            AudioAttributes.Builder()
                .setUsage(
                    AudioAttributes
                        .USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                )
                .setContentType(
                    AudioAttributes.CONTENT_TYPE_SONIFICATION
                )
                .build()

        val format =
            AudioFormat.Builder()
                .setSampleRate(
                    audioConfig.sampleRate
                )
                .setEncoding(
                    encoding
                )
                .setChannelMask(
                    channelMask
                )
                .build()

        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(
                AudioTrack.MODE_STREAM
            )
            .setBufferSizeInBytes(
                bufferSizeBytes
            )
            .setPerformanceMode(
                AudioTrack
                    .PERFORMANCE_MODE_LOW_LATENCY
            )
            .build()
    }

    /**
     * 오디오 스레드와 AudioTrack을 종료한다.
     */
    override fun close() {

        if (!running && audioTrack == null) {
            return
        }

        running = false
        latestCommand.set(null)

        val track =
            audioTrack

        runCatching {
            track?.pause()
        }

        runCatching {
            track?.flush()
        }

        runCatching {
            track?.stop()
        }

        runCatching {
            audioThread?.join(1000)
        }

        runCatching {
            track?.release()
        }

        audioThread = null
        audioTrack = null

        Log.i(
            TAG,
            "AudioOutput stopped"
        )
    }
}