package hearspace.viewer

import hearspace.core.audio.BinauralRenderer
import hearspace.core.types.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.sound.sampled.AudioSystem

class SonificationExportTest {
    @Test fun `synthetic sources export identical PCM and aligned rows including duck release unknown`(@TempDir root: File) {
        val overrides = """{"sonify":{"mode":"RISK_CONTINUOUS","mapping":"DISTANCE_HEIGHT"}}"""
        val cfg = ConfigLoader.load(Repo.defaultConfig.readText(), overrides)
        val hrtf = ReplayRunner.loadHrtf()
        val dir = File(root, "run")
        val expected = java.io.ByteArrayOutputStream()
        SonificationExport(dir, cfg, File(root, "synthetic"), overrides).use { export ->
            val observed = BinauralRenderer(cfg, hrtf, export::sample)
            val baseline = BinauralRenderer(cfg, hrtf)
            fun cmd(id: Int, d: Float, band: Band) = AudioCmd(id, 0f, d, band, SoundKind.FLOOR_PULSE, 0f, true)
            for (i in 0..120) {
                val state = if (i == 120) GuidanceState.UNKNOWN else GuidanceState.NORMAL
                val commands = when {
                    i < 100 -> listOf(cmd(1, 0.7f, Band.STOP), cmd(2, 2f-i*0.005f, Band.WARN))
                    else -> emptyList()
                }
                val g = GuidanceOutput(1000000000L + i * cfg.audio.blockSize * 1000000000L / cfg.audio.sampleRate,
                    state, commands, 0f, null)
                val a = FloatArray(2*cfg.audio.blockSize)
                val b = FloatArray(a.size)
                export.begin(g); observed.render(g,a); export.end(a)
                baseline.render(g,b)
                assertArrayEquals(b,a,0f,"logging must not change PCM")
                for (v in a) {
                    val x=(v.coerceIn(-1f,1f)*Short.MAX_VALUE).toInt()
                    expected.write(x and 255); expected.write((x shr 8) and 255)
                }
            }
            export.complete()
        }
        AudioSystem.getAudioInputStream(File(dir,"audio.wav")).use {
            assertEquals(121L*cfg.audio.blockSize,it.frameLength)
            assertEquals(cfg.audio.sampleRate.toFloat(),it.format.sampleRate)
            assertEquals(2,it.format.channels)
            assertArrayEquals(expected.toByteArray(),it.readAllBytes())
        }
        val lines=File(dir,"sonification.csv").readLines()
        val header=lines.first().split(',')
        val rows=lines.drop(1).map { line ->
            val cells=line.split(','); assertEquals(header.size,cells.size)
            header.zip(cells).toMap()
        }
        assertTrue(rows.all { it["mapping"]=="DISTANCE_HEIGHT" })
        assertTrue(rows.filter { it["phase"]=="COMMAND" }.all { it["heightDeltaM"]=="0.0" && it["targetPitchHz"]=="500.0" })
        assertEquals(121,rows.map { it["frameStart"] }.distinct().size)
        for (row in rows) {
            assertEquals(row.getValue("frameStart").toDouble()/cfg.audio.sampleRate,row.getValue("elapsedS").toDouble(),1e-12)
        }
        assertTrue(rows.any { it["obstacleId"]=="2" && it["ducked"]=="true" })
        assertTrue(rows.any { it["phase"]=="RELEASE" && it["active"]=="false" && it.getValue("gain").toFloat()>0f })
        assertTrue(rows.any { it["ttcFinite"]=="false" && it["ttcS"]=="" })
        assertTrue(rows.any { it["state"]=="UNKNOWN" && it["phase"]=="NO_SOURCE" })
        assertThrows(IllegalArgumentException::class.java) { SonificationExport(dir,cfg,root,overrides) }
    }
}
