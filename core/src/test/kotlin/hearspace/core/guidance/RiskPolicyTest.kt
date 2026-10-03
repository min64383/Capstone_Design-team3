package hearspace.core.guidance

import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Vec3
import hearspace.core.types.*
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 통로 밖 가까운 물체가 STOP 대상의 선택 슬롯을 차지하지 않는지 검사한다. */
class RiskPolicyTest {
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())
    private val head = HeadPose(Vec3(0f, 1.5f, 0f), Vec3(0f, 0f, -1f))
    private fun objectAt(id: Int, xM: Float, alongM: Float, inCorridor: Boolean): Obstacle {
        val p = Vec3(xM, 1.5f, -alongM)
        return Obstacle(id, p, RepStrategy.entries.associateWith { p }, p, p,
            HeightClass.FLOOR, inCorridor, 1f, 0L, 10)
    }
    @Test fun stopInsideCorridorWinsLimitedSlotAndGeometryIsForwarded() {
        val objects = listOf(objectAt(1, 2f, 0.2f, false), objectAt(2, 0f, 0.7f, true))
        val snap = ObstacleSnapshot(0L, objects, 0f, MapHealth.OK)
        val baseline = Policy(config.policy.copy(maxSources = 1, prioritizeStop = false), config.corridor.behindM)
        val risk = Policy(config.policy.copy(maxSources = 1, prioritizeStop = true), config.corridor.behindM)
        assertEquals(1, baseline.commands(snap, head, 0f).single().obstacleId)
        val selected = risk.commands(snap, head, 0f).single()
        assertEquals(2, selected.obstacleId)
        assertEquals(Band.STOP, selected.band)
        assertTrue(selected.inCorridor)
        val all = Policy(config.policy.copy(maxSources = 2, prioritizeStop = true), config.corridor.behindM)
            .commands(snap, head, 0f)
        assertFalse(all.first { it.obstacleId == 1 }.inCorridor)
    }
    @Test fun expiredSnapshotIsNotSelected() {
        val policy = Policy(config.policy.copy(prioritizeStop = true), config.corridor.behindM)
        val snapshot = ObstacleSnapshot(0L, listOf(objectAt(2, 0f, 0.7f, true)), 0f, MapHealth.OK)
        assertTrue(policy.commands(snapshot, head, config.policy.maxInfoAgeMs + 1).isEmpty())
    }
}
