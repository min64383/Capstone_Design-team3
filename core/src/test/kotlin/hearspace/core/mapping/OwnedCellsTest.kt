package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** M57 복셀 지도 하나: 칸 소유 연산(설계 3.2절 규칙 3~7). 라벨 화소 값: 2 건물, 8 물체, 10 사람. */
class OwnedCellsTest {
    private val cfg = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText()).map
    private val person = 10
    private val building = 2

    /** 칸 [p]를 [n]장 맞히고(장마다 한 번) 라벨 [label]을 한 표. */
    private fun fill(m: VoxelMap, p: Vec3, n: Int = cfg.minHits, label: Int? = null) {
        repeat(n) { m.beginFrame(); m.insert(p, it.toLong()) }
        label?.let { m.voteLabel(p, it) }
    }

    private fun cell(m: VoxelMap, p: Vec3) = m.views().single { it.centerW == Vec3((m.index(p.x) + 0.5f) * cfg.voxelSizeM, (m.index(p.y) + 0.5f) * cfg.voxelSizeM, (m.index(p.z) + 0.5f) * cfg.voxelSizeM) }

    @Test
    fun `a moving cell is not occupied and becomes an ordinary cell with the object evidence when released`() {
        val m = VoxelMap(cfg)
        val p = Vec3(0.02f, 1.02f, -2.02f)
        fill(m, p, 2)
        m.own(p, 7, moving = true)
        assertTrue(m.occupied().isEmpty())
        m.releaseOwner(7, 10L, cfg.minHits.toFloat(), 1f)
        val v = m.occupied().single()
        assertEquals(0, v.owner)
        assertFalse(v.moving)
    }

    @Test
    fun `claiming takes owned cells of the majority label or no label and only releases other labels`() {
        val m = VoxelMap(cfg)
        val a = Vec3(0.02f, 1.02f, -2.02f); val b = Vec3(0.07f, 1.02f, -2.02f); val w = Vec3(0.12f, 1.02f, -2.02f); val u = Vec3(0.17f, 1.02f, -2.02f)
        fill(m, a, label = person); fill(m, b, label = person); fill(m, w, label = building); fill(m, u)
        for (p in listOf(a, b, w, u)) m.own(p, 3, moving = false)
        m.claimOwned(3)
        assertTrue(cell(m, a).moving && cell(m, b).moving && cell(m, u).moving)
        assertFalse(cell(m, w).moving)
        assertEquals(0, cell(m, w).owner)
    }

    @Test
    fun `the view shadow takes same-label cells right behind a moving track only`() {
        val m = VoxelMap(cfg)
        val cam = Vec3(0f, 1.2f, 0f)
        val body = listOf(Vec3(-0.07f, 1.02f, -2.02f), Vec3(0.02f, 1.02f, -2.02f), Vec3(0.07f, 1.02f, -2.02f))
        body.forEach { fill(m, it, label = person); m.own(it, 5, moving = true) }
        val echo = Vec3(0.02f, 1.02f, -2.82f) // 0.8 m 뒤, 사람 라벨
        val far = Vec3(0.02f, 1.02f, -5.07f) // 가까운 면(2.0 m)에서 2.93 m 넘게 뒤
        val wall = Vec3(0.02f, 1.02f, -2.52f) // 건물 라벨
        val aside = Vec3(1.02f, 1.02f, -2.82f) // 방위 밖
        fill(m, echo, label = person); fill(m, far, label = person); fill(m, wall, label = building); fill(m, aside, label = person)
        m.claimShadow(5, cam, 2.93f)
        assertTrue(cell(m, echo).moving && cell(m, echo).owner == 5)
        assertFalse(cell(m, far).moving)
        assertFalse(cell(m, wall).moving)
        assertFalse(cell(m, aside).moving)
    }

    @Test
    fun `moving cells not hit this frame are dropped and a dropped track removes its moving cells`() {
        val m = VoxelMap(cfg)
        val hit = Vec3(0.02f, 1.02f, -2.02f); val old = Vec3(0.02f, 1.02f, -2.52f); val keep = Vec3(0.52f, 1.02f, -2.52f)
        fill(m, old); m.own(old, 4, moving = true)
        fill(m, keep); m.own(keep, 4, moving = false)
        m.beginFrame(); m.insert(hit, 99L); m.own(hit, 4, moving = true)
        m.dropUnhitMoving(4)
        assertEquals(listOf(Vec3(0.025f, 1.025f, -2.025f)), m.movingCells(4))
        m.transferOwner(4, 9)
        m.dropOwner(9)
        assertTrue(m.movingCells(9).isEmpty())
        assertEquals(0, cell(m, keep).owner)
    }
}
