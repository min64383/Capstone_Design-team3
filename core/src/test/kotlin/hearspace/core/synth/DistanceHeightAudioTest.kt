package hearspace.core.synth

import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.audio.RiskSoundGenerator
import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Vec3
import hearspace.core.guidance.Policy
import hearspace.core.types.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** 합성 3D 대표점 → Policy → 음향. 실측 데이터에 의존하지 않는 단서 분리 검사. */
class DistanceHeightAudioTest {
    private val baseJson = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val cfg = ConfigLoader.load(baseJson,
        """{"sonify":{"mode":"RISK_CONTINUOUS","mapping":"DISTANCE_HEIGHT"}}""")
    private val head = HeadPose(Vec3(0f,1.5f,0f),Vec3(0f,0f,-1f))
    private val n = cfg.audio.blockSize
    private val dt = n * 1000000000L / cfg.audio.sampleRate
    private val hrtf = Hrtf.parse(File(System.getProperty("hearspace.hrtfAsset")).readBytes())

    private fun command(distanceM: Float, heightDeltaM: Float, azimuthDeg: Float = 0f): AudioCmd {
        val rad = Math.toRadians(azimuthDeg.toDouble())
        val p = Vec3((distanceM*kotlin.math.sin(rad)).toFloat(),head.positionW.y+heightDeltaM,
            (-distanceM*kotlin.math.cos(rad)).toFloat())
        val o = Obstacle(1,p,RepStrategy.entries.associateWith { p },p,p,
            if (heightDeltaM >= 0f) HeightClass.HEAD else HeightClass.FLOOR,true,1f,0L,10)
        return Policy(cfg.policy,cfg.corridor.behindM).commands(
            ObstacleSnapshot(0L,listOf(o),0f,MapHealth.OK),head,0f).single()
    }
    private fun generator(c: AudioCmd): RiskSoundGenerator = RiskSoundGenerator(cfg).also {
        it.render(c,0L,1f,FloatArray(n))
    }

    @Test fun distanceChangesGainButNotPitch() {
        val near = generator(command(0.6f,0f))
        val far = generator(command(2.4f,0f))
        assertEquals(500f,near.targetPitchHz,0.001f)
        assertEquals(near.targetPitchHz,far.targetPitchHz,0f)
        assertTrue(near.targetGain > far.targetGain)
    }
    @Test fun heightChangesPitchButNotDistanceGainOrHeadClassMultiplier() {
        val low=generator(command(1.5f,-1f))
        val middle=generator(command(1.5f,0f))
        val high=generator(command(1.5f,1f))
        assertEquals(250f,low.targetPitchHz,0.001f)
        assertEquals(500f,middle.targetPitchHz,0.001f)
        assertEquals(1000f,high.targetPitchHz,0.001f)
        assertEquals(low.targetGain,high.targetGain,0f)
        assertEquals(-1f,command(1.5f,-1f).heightDeltaM,0f)
        assertEquals(1f,command(1.5f,1f).heightDeltaM,0f)
        assertEquals(250f,generator(command(1.5f,-10f)).targetPitchHz,0.001f)
        assertEquals(1000f,generator(command(1.5f,10f)).targetPitchHz,0.001f)
    }
    @Test fun approachSpeedDoesNotChangePitchOrDistanceGain() {
        fun approach(speed: Float): RiskSoundGenerator {
            val r=RiskSoundGenerator(cfg)
            for (i in 0..80) {
                val d=1.5f + speed * (80-i)*dt/1e9f
                r.render(command(d,0f),i*dt,1f,FloatArray(n))
            }
            return r
        }
        val slow=approach(0.2f);val fast=approach(0.8f)
        assertTrue(fast.risk>slow.risk)
        assertEquals(slow.targetGain,fast.targetGain,0.00001f)
        assertEquals(slow.targetPitchHz,fast.targetPitchHz,0f)
    }
    @Test fun invalidHeightAndExpiredInformationMuteWithoutOldTails() {
        for (bad in listOf(command(0.7f,0f).copy(heightDeltaM=Float.NaN),
            command(0.7f,0f).copy(infoAgeMs=cfg.policy.maxInfoAgeMs+1))) {
            val r=BinauralRenderer(cfg,hrtf);val out=FloatArray(2*n)
            repeat(50) { r.render(GuidanceOutput(it*dt,GuidanceState.NORMAL,listOf(command(0.7f,0f)),0f,null),out) }
            r.render(GuidanceOutput(50*dt,GuidanceState.NORMAL,listOf(bad),0f,null),out)
            assertTrue(out.all { it==0f })
        }
    }
    @Test fun configurationRejectsInvalidHeightSpan() {
        for (json in listOf("""{"sonify":{"heightLowM":1,"heightHighM":1}}""",
            """{"sonify":{"heightLowM":2,"heightHighM":1}}""")) {
            assertThrows(ConfigException::class.java) { ConfigLoader.load(baseJson,json) }
        }
    }
    @Test fun leftAndRightKeepCorrectStereoEnergy() {
        fun energy(az: Float): Pair<Double,Double> {
            val r=BinauralRenderer(cfg,hrtf);val out=FloatArray(n*2)
            var l=0.0;var right=0.0
            repeat(100) { i ->
                r.render(GuidanceOutput(i*dt,GuidanceState.NORMAL,listOf(command(1.5f,0f,az)),0f,null),out)
                for (k in 0 until n) { l+=out[2*k]*out[2*k];right+=out[2*k+1]*out[2*k+1] }
            }
            return l to right
        }
        val left=energy(-60f);val right=energy(60f)
        assertTrue(left.first>left.second,"left source should be louder in left ear")
        assertTrue(right.second>right.first,"right source should be louder in right ear")
    }
    @Test fun listeningWavs() {
        val dir=File(System.getProperty("hearspace.testOutput"),"distance-height").apply { mkdirs() }
        // 청취 비교용 합성 실험에서만 정지 음원을 2초 동안 들을 수 있도록 이벤트 기간 연장.
        val demo=cfg.copy(sonify=cfg.sonify.copy(onceS=3f))
        fun write(name: String, commands: List<AudioCmd>) {
            val bytes=ByteArrayOutputStream()
            val sounding=(2.0*cfg.audio.sampleRate/n).toInt()
            val releasing=(0.5*cfg.audio.sampleRate/n).toInt()
            for (c in commands) {
                val r=BinauralRenderer(demo,hrtf)
                repeat(sounding+releasing) { i ->
                    val out=FloatArray(n*2)
                    r.render(GuidanceOutput(i*dt,GuidanceState.NORMAL,if(i<sounding) listOf(c) else emptyList(),0f,null),out)
                    for(v in out) { val x=(v.coerceIn(-1f,1f)*32767).toInt();bytes.write(x and 255);bytes.write((x shr 8) and 255) }
                }
            }
            val raw=bytes.toByteArray()
            AudioInputStream(ByteArrayInputStream(raw),AudioFormat(cfg.audio.sampleRate.toFloat(),16,2,true,false),raw.size/4L).use {
                AudioSystem.write(it,AudioFileFormat.Type.WAVE,File(dir,name))
            }
        }
        write("01-distance-far-mid-near.wav",listOf(2.4f,1.5f,0.6f).map { command(it,0f) })
        write("02-direction-left-center-right.wav",listOf(-60f,0f,60f).map { command(1.5f,0f,it) })
        write("03-height-low-mid-high.wav",listOf(-1f,0f,1f).map { command(1.5f,it) })
        File(dir,"README.txt").writeText("Synthetic comparison: each section approximately 2 seconds plus 0.5-second release.\n01: far 2.4m / mid 1.5m / near 0.6m; height=0m, azimuth=0deg.\n02: left -60deg / center 0deg / right 60deg; distance=1.5m, height=0m.\n03: relative height -1m / 0m / +1m; distance=1.5m, azimuth=0deg.\nOnly these demos extend onceS to 3s; app activation rules are unchanged.\n")
    }
}
