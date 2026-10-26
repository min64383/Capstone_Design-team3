package hearspace.core.synth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 등장음·소실음의 청취용 WAV 생성.
 * 실제 물체 인식이나 앱 재생 로직에는 연결하지 않는다.
 */
class ObjectCueWavTest {

    // 청취 실험용 설정. 음색을 바꾸려면 여기의 값을 수정한다.
    private val sampleRateHz = 48_000

    private data class CueSpec(
        val startHz: Double,
        val endHz: Double,
        val durationMs: Int,
        val amplitude: Double,
        val fadeInMs: Int,
        val fadeOutMs: Int
    )

    // 등장: 짧고 부드럽게 올라가는 소리
    private val appearSpec = CueSpec(
        startHz = 650.0,
        endHz = 1000.0,
        durationMs = 250,
        amplitude = 0.40,
        fadeInMs = 20,
        fadeOutMs = 100
    )

    // 소실: 낮아지면서 길게 감쇠하는 소리
    private val lostSpec = CueSpec(
        startHz = 550.0,
        endHz = 350.0,
        durationMs = 300,
        amplitude = 0.25,
        fadeInMs = 20,
        fadeOutMs = 200
    )

    @Test
    fun exportObjectCueWavs() {
        val outputDir = File(
            System.getProperty("hearspace.testOutput", "build/test-output"),
            "object-cues"
        )
        check(outputDir.isDirectory || outputDir.mkdirs()) {
            "출력 폴더를 만들 수 없습니다: ${outputDir.absolutePath}"
        }

        val appear = synthesize(appearSpec)
        val lost = synthesize(lostSpec)

        verifySignal(appear, appearSpec)
        verifySignal(lost, lostSpec)

        writeWav(File(outputDir, "object_appear.wav"), appear)
        writeWav(File(outputDir, "object_lost.wav"), lost)

        // 0.5초 무음 → 등장 → 1초 무음 → 소실 → 1초 무음.
        // 이 순서를 3번 반복한다.
        val comparison = DoubleArray(
            samplesForMs(500) +
                3 * (appear.size + samplesForMs(1000) +
                    lost.size + samplesForMs(1000))
        )

        var offset = samplesForMs(500)

        repeat(3) {
            appear.copyInto(comparison, destinationOffset = offset)
            offset += appear.size + samplesForMs(1000)

            lost.copyInto(comparison, destinationOffset = offset)
            offset += lost.size + samplesForMs(1000)
        }

        writeWav(File(outputDir, "object_cues_compare.wav"), comparison)

        println("효과음 생성 완료: ${outputDir.absolutePath}")
    }

    private fun samplesForMs(durationMs: Int): Int =
        (durationMs * sampleRateHz / 1000.0).roundToInt()

    private fun synthesize(spec: CueSpec): DoubleArray {
        require(spec.durationMs > 0)
        require(spec.amplitude > 0.0 && spec.amplitude <= 1.0)
        require(spec.startHz > 0.0 && spec.startHz < sampleRateHz / 2.0)
        require(spec.endHz > 0.0 && spec.endHz < sampleRateHz / 2.0)
        require(spec.fadeInMs > 0 && spec.fadeOutMs > 0)
        require(spec.fadeInMs + spec.fadeOutMs <= spec.durationMs)

        val count = samplesForMs(spec.durationMs)
        val fadeInCount = samplesForMs(spec.fadeInMs)
        val fadeOutCount = samplesForMs(spec.fadeOutMs)

        require(count >= 2 && fadeInCount >= 2 && fadeOutCount >= 2)

        val lastTimeSec = (count - 1).toDouble() / sampleRateHz
        val sweepHzPerSec = (spec.endHz - spec.startHz) / lastTimeSec

        return DoubleArray(count) { index ->
            val timeSec = index.toDouble() / sampleRateHz

            // 주파수를 적분한 위상.
            // 단순히 sin(2π × 현재 주파수 × 시간)으로 쓰면
            // 의도한 주파수 변화와 달라지므로 아래 식을 사용한다.
            val phaseRad = 2.0 * PI * (
                spec.startHz * timeSec +
                    0.5 * sweepHzPerSec * timeSec * timeSec
                )

            // 선형 페이드: 첫 샘플과 마지막 샘플은 정확히 0.
            val fadeIn = (
                index.toDouble() / (fadeInCount - 1)
                ).coerceIn(0.0, 1.0)

            val fadeOut = (
                (count - 1 - index).toDouble() / (fadeOutCount - 1)
                ).coerceIn(0.0, 1.0)

            spec.amplitude * fadeIn * fadeOut * sin(phaseRad)
        }
    }

    private fun verifySignal(samples: DoubleArray, spec: CueSpec) {
        assertEquals(samplesForMs(spec.durationMs), samples.size)
        assertEquals(0.0, samples.first(), 1e-12)
        assertEquals(0.0, samples.last(), 1e-12)

        assertTrue(
            samples.all { it.isFinite() && abs(it) < 1.0 },
            "잘못된 샘플 또는 클리핑이 있습니다."
        )
        assertTrue(
            samples.any { abs(it) > 0.01 },
            "생성된 소리가 무음입니다."
        )
    }

    private fun writeWav(file: File, samples: DoubleArray) {
        // 16-bit PCM, little-endian, mono
        val pcmBytes = ByteArray(samples.size * 2)

        for (index in samples.indices) {
            val pcm = (
                samples[index].coerceIn(-1.0, 1.0) * 32767.0
                ).roundToInt()

            pcmBytes[index * 2] = (pcm and 0xff).toByte()
            pcmBytes[index * 2 + 1] = ((pcm shr 8) and 0xff).toByte()
        }

        val format = AudioFormat(
            sampleRateHz.toFloat(),
            16,
            1,
            true,
            false
        )

        AudioInputStream(
            ByteArrayInputStream(pcmBytes),
            format,
            samples.size.toLong()
        ).use { stream ->
            check(
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file) > 0
            ) {
                "WAV 저장에 실패했습니다: ${file.absolutePath}"
            }
        }

        // 저장된 WAV를 다시 열어 형식과 길이 확인.
        AudioSystem.getAudioInputStream(file).use { stream ->
            assertEquals(samples.size.toLong(), stream.frameLength)
            assertEquals(1, stream.format.channels)
            assertEquals(16, stream.format.sampleSizeInBits)
            assertEquals(sampleRateHz.toFloat(), stream.format.sampleRate)
        }
    }
}