package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.types.CorridorConfig
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class WallAlignTest {
    private val cfg = CorridorConfig(0.8f, 2.0f, 3.5f, 0.2f, 0.25f, 0.8f, 0f, false, true)

    private fun deg(v: Vec3) = Math.toDegrees(atan2(v.x, -v.z).toDouble()).toFloat()

    /** 사용자 오른쪽 0.47 m에 −Z와 [wallDeg]만큼 기운 벽(칸 5 cm, 바닥 위 0.1~1.9 m, 앞뒤 3 m). */
    private fun wall(wallDeg: Float, lateral: Float = 0.47f): List<Vec3> {
        val a = Math.toRadians(wallDeg.toDouble())
        val dir = Vec3(kotlin.math.sin(a).toFloat(), 0f, -kotlin.math.cos(a).toFloat())
        val right = Vec3(-dir.z, 0f, dir.x)
        val out = ArrayList<Vec3>()
        var s = -1.0f
        while (s <= 3.0f) {
            var y = 0.1f
            while (y <= 1.9f) {
                val p = right * lateral + dir * s
                out += Vec3(kotlin.math.floor(p.x / 0.05f) * 0.05f + 0.025f, y, kotlin.math.floor(p.z / 0.05f) * 0.05f + 0.025f)
                y += 0.05f
            }
            s += 0.025f
        }
        return out.distinct()
    }

    @Test
    fun `aligns to a wall within alpha, keeps heading beyond it`() {
        val user = Vec3(0f, 1.5f, 0f)
        for (w in listOf(-8f, -5f, -2f, 0f, 3f, 6f, 9f)) {
            val h = wallAlignedHeading(Vec3(0f, 0f, -1f), wall(w), user, 0f, cfg, 0.05f)
            assertEquals(w, deg(h), 1.0f) { "wall $w" }
        }
        val far = wallAlignedHeading(Vec3(0f, 0f, -1f), wall(20f), user, 0f, cfg, 0.05f)
        assertEquals(0f, deg(far), 0.01f)
    }
}
