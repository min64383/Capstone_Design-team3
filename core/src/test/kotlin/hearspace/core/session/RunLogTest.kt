package hearspace.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import hearspace.core.pipeline.FastPath
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.Scenes
import hearspace.core.types.ConfigLoader
import java.io.File

class RunLogTest {
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())

    @Test
    fun `headers follow spec section 10_1 order`() {
        assertEquals("tCaptureNs,tStartNs,tDoneNs,nPoints,nVoxels,nObstacles,floorY,mapHealth", RunLog.header(RunLog.SLOW_PATH_HEADER))
        assertEquals(
            "tBlockNs,poseTNs,snapshotTNs,state,obstacleId,azimuthDeg,distanceM,band,sound,infoAgeMs,headingDeg",
            RunLog.header(RunLog.GUIDANCE_HEADER),
        )
        assertEquals(4 + 9 + 6, RunLog.OBSTACLES_HEADER.size)
    }

    /** SC-02를 돌려 세 로그의 줄을 만든다(앱과 같은 순서, 시각은 가상). */
    private fun run(): List<String> {
        val fast = FastPath(config)
        val slow = SlowPath(config)
        var snap: hearspace.core.types.ObstacleSnapshot? = null
        val lines = ArrayList<String>()
        for (f in Scenes.SC02.generate().frames) {
            val g = fast.compute(f.pose, snap, f.pose.tCaptureNs)
            slow.apply(fast.takeMapAction())
            lines += RunLog.guidanceLines(g, f.pose.tCaptureNs, snap?.tCaptureNs)
            val d = f.depth
            val h = fast.headingW
            if (d != null && h != null) {
                val s = slow.process(d, h)
                snap = s
                lines += RunLog.slowPathLine(slow.lastMapUpdate!!, d.tCaptureNs, d.tCaptureNs, s)
                lines += RunLog.obstacleLines(s)
            }
        }
        return lines
    }

    @Test
    fun `lines have header column counts and are deterministic`() {
        val a = run()
        val counts = setOf(RunLog.SLOW_PATH_HEADER.size, RunLog.GUIDANCE_HEADER.size, RunLog.OBSTACLES_HEADER.size)
        assertTrue(a.all { it.split(',').size in counts })
        assertTrue(a.any { it.split(',').size == RunLog.OBSTACLES_HEADER.size }, "SC-02 box appears in obstacles.csv")
        assertTrue(a.any { it.contains(",WARN,FLOOR_PULSE,") }, "SC-02 warns")
        assertEquals(a, run())
    }
}
