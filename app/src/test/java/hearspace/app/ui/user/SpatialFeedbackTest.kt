package hearspace.app.ui.user

import hearspace.core.audio.ObjectSpeechFrame
import hearspace.core.geometry.Vec3
import hearspace.core.types.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SpatialFeedbackTest {
    private val cfg = SpatialUiConfig(true, 1f, 2f, 0.15f, 22.5f, 1400, 50, 200)
    private val nowNs = 2_000_000_000L
    private val maxAgeNs = 500_000_000L
    private fun model() = SpatialFeedback(cfg, maxAgeNs, 0.6f)

    private fun input(
        distanceM: Float = 2f, band: Band = Band.WARN, id: Int = 1,
        height: HeightClass = HeightClass.HEAD, azimuthDeg: Float = -40f,
        timeNs: Long = nowNs, label: SemanticLabel? = SemanticLabel.PERSON,
        labelTimeNs: Long = timeNs, fraction: Float = 1f, epochNs: Long = 0L,
    ): ObjectSpeechFrame {
        val p = Vec3(0f, 1.4f, 2f)
        val obs = Obstacle(id, p, RepStrategy.entries.associateWith { p },
            p - Vec3(0.1f, 0.1f, 0.1f), p + Vec3(0.1f, 0.1f, 0.1f),
            height, true, 1f, timeNs, 8,
            classification = label?.let { ObjectClassification(it, fraction, labelTimeNs) })
        val cmd = AudioCmd(id, azimuthDeg, distanceM, band, SoundKind.HEAD_TONE, 0f)
        return ObjectSpeechFrame(GuidanceOutput(timeNs, GuidanceState.NORMAL, listOf(cmd), 0f, null),
            ObstacleSnapshot(timeNs, listOf(obs), 0f, MapHealth.OK), epochNs)
    }

    @Test fun distanceColorsAndStopOverride() {
        assertEquals(DistanceTint.NEAR, model().update(input(0.9f), nowNs)!!.tint)
        assertEquals(DistanceTint.MIDDLE, model().update(input(1.5f), nowNs)!!.tint)
        assertEquals(DistanceTint.FAR, model().update(input(2.4f), nowNs)!!.tint)
        assertEquals(DistanceTint.NEAR, model().update(input(4f, Band.STOP), nowNs)!!.tint)
    }

    @Test fun approachingChangesImmediatelyAndRetreatUsesHysteresis() {
        val m = model()
        assertEquals(DistanceTint.FAR, m.update(input(2.3f), nowNs)!!.tint)
        assertEquals(DistanceTint.MIDDLE, m.update(input(2f), nowNs)!!.tint)
        assertEquals(DistanceTint.MIDDLE, m.update(input(2.1f), nowNs)!!.tint)
        assertEquals(DistanceTint.FAR, m.update(input(2.16f), nowNs)!!.tint)
        assertEquals(DistanceTint.NEAR, m.update(input(1f), nowNs)!!.tint)
        assertEquals(DistanceTint.NEAR, m.update(input(1.1f), nowNs)!!.tint)
        assertEquals(DistanceTint.MIDDLE, m.update(input(1.16f), nowNs)!!.tint)
    }

    @Test fun newObjectWorldResetAndLostObservationClearColorHistory() {
        val m = model()
        m.update(input(2f), nowNs)
        assertEquals(DistanceTint.FAR, m.update(input(2.1f, id = 2), nowNs)!!.tint)
        m.update(input(2f), nowNs)
        assertEquals(DistanceTint.FAR, m.update(input(2.1f, epochNs = 1L), nowNs)!!.tint)
        m.update(input(2f), nowNs)
        assertNull(m.update(null, nowNs))
        assertEquals(DistanceTint.FAR, m.update(input(2.1f), nowNs)!!.tint)
    }

    @Test fun stopIsPrimaryAndHeightAndNameMatchItsId() {
        val warn = input(1.3f, id = 1, label = SemanticLabel.PERSON, height = HeightClass.HEAD)
        val stop = input(3f, Band.STOP, id = 2, label = SemanticLabel.VEHICLE, height = HeightClass.FLOOR)
        val mixed = warn.copy(output = warn.output.copy(commands = warn.output.commands + stop.output.commands),
            snapshot = warn.snapshot!!.copy(obstacles = stop.snapshot!!.obstacles + warn.snapshot!!.obstacles))
        val cue = model().update(mixed, nowNs)!!
        assertEquals(2, cue.obstacleId)
        assertEquals("차량", cue.name)
        assertEquals("발 근처", cue.heightText)
        assertEquals(DistanceTint.NEAR, cue.tint)
    }

    @Test fun nearestSelectedWhenBandsAreEqual() {
        val far = input(2.4f, id = 1)
        val near = input(1.2f, id = 2)
        val mixed = far.copy(output = far.output.copy(commands = far.output.commands + near.output.commands),
            snapshot = far.snapshot!!.copy(obstacles = far.snapshot!!.obstacles + near.snapshot!!.obstacles))
        assertEquals(2, model().update(mixed, nowNs)!!.obstacleId)
    }

    @Test fun textCoversDirectionHeightAndMetricDistance() {
        assertEquals("왼쪽 2m\n사람 · 머리 높이", model().update(input(), nowNs)!!.detailText)
        assertEquals("몸통 높이", model().update(input(height = HeightClass.BODY), nowNs)!!.heightText)
        assertEquals("정면", model().update(input(azimuthDeg = 0f), nowNs)!!.direction)
        assertEquals("오른쪽", model().update(input(azimuthDeg = 40f), nowNs)!!.direction)
        assertEquals("뒤쪽", model().update(input(azimuthDeg = 180f), nowNs)!!.direction)
        assertEquals("0.1m 이내", model().update(input(0.05f), nowNs)!!.distanceText)
        assertEquals("1.3m", model().update(input(1.3f), nowNs)!!.distanceText)
    }

    @Test fun staleAndFutureFramesAreHidden() {
        assertNull(model().update(input(timeNs = nowNs - maxAgeNs - 1), nowNs))
        assertNull(model().update(input(timeNs = nowNs + 1), nowNs))
        val source = input()
        assertNull(model().update(source.copy(snapshot = source.snapshot!!.copy(tCaptureNs = nowNs + 1)), nowNs))
        assertNull(model().update(source.copy(epochNs = nowNs), nowNs))
    }

    @Test fun invalidOrMissingObjectEvidenceIsHidden() {
        val source = input()
        assertNull(model().update(source.copy(snapshot = null), nowNs))
        assertNull(model().update(source.copy(snapshot = source.snapshot!!.copy(obstacles = emptyList())), nowNs))
        assertNull(model().update(source.copy(snapshot = source.snapshot!!.copy(obstacles = source.snapshot!!.obstacles.map {
            it.copy(lastSeenNs = nowNs - maxAgeNs - 1)
        })), nowNs))
        for (cmd in listOf(source.output.commands[0].copy(infoAgeMs = -1f),
            source.output.commands[0].copy(infoAgeMs = 501f), source.output.commands[0].copy(azimuthDeg = 181f))) {
            assertNull(model().update(source.copy(output = source.output.copy(commands = listOf(cmd))), nowNs))
        }
    }

    @Test fun pausedUnknownSilentAndInvalidDistanceAreHidden() {
        for (state in listOf(GuidanceState.PAUSED, GuidanceState.UNKNOWN)) {
            val source = input()
            assertNull(model().update(source.copy(output = source.output.copy(state = state)), nowNs))
        }
        assertNull(model().update(input(band = Band.SILENT), nowNs))
        for (d in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) assertNull(model().update(input(d), nowNs))
    }

    @Test fun unavailableClassificationFallsBackToObstacle() {
        for (source in listOf(input(label = null), input(fraction = 0.5f),
            input(labelTimeNs = nowNs + 1), input(labelTimeNs = nowNs - maxAgeNs - 1),
            input(label = SemanticLabel.OBJECT))) {
            assertEquals("장애물", model().update(source, nowNs)!!.name)
        }
    }

    @Test fun degradedFloorKeepsFreshDirectionWithExistingCautionStatus() {
        val source = input()
        val cue = model().update(source.copy(snapshot = source.snapshot!!.copy(mapHealth = MapHealth.DEGRADED)), nowNs)!!
        val screen = screen(cue, mapHealth = MapHealth.DEGRADED)
        assertEquals("바닥 확인 중", screen.title)
        assertEquals(cue, (screen.visual as UserScreen.Visual.Arrow).spatial)
    }

    @Test fun screenPreservesStopShapeAndTextAndClearsCueWhenPaused() {
        val cue = model().update(input(3f, Band.STOP), nowNs)!!
        val s = screen(cue)
        assertEquals("멈춤", s.label)
        assertTrue((s.visual as UserScreen.Visual.Arrow).stop)
        assertEquals(UserScreen.Visual.Paused, screen(cue, paused = true).visual)
        assertEquals(UserScreen.Visual.Preparing, screen(cue, state = GuidanceState.UNKNOWN).visual)
    }

    @Test fun speechOnlyDisplaysStartedLineAndIgnoresOldCompletion() {
        val speech = SpeechVisualState()
        assertNull(speech.line)
        speech.started("old", "앱이 시작했습니다")
        speech.started("urgent", "잠시 멈춰 주세요")
        speech.finished("old")
        assertEquals("잠시 멈춰 주세요", speech.line!!.text)
        speech.finished("urgent")
        assertNull(speech.line)
        speech.started("new", "새 안내")
        speech.clear()
        assertNull(speech.line)
    }

    @Test fun assetParsesAndInvalidDisplayThresholdsAreRejected() {
        val file = System.getProperty("hearspace.spatialUiConfig")?.let(::File)
            ?: File("src/main/assets/config/spatial-ui.json").takeIf { it.exists() }
            ?: File("app/src/main/assets/config/spatial-ui.json")
        assertEquals(cfg, SpatialUiConfig.parse(file.readText()))
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(nearM = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(middleM = 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(frameIntervalMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(hysteresisM = -1f) }
    }

    private fun screen(cue: SpatialCue, paused: Boolean = false,
        state: GuidanceState = GuidanceState.NORMAL, mapHealth: MapHealth = MapHealth.OK) =
        UserScreen.of(true, null, paused, state, mapHealth, cue.band, cue.azimuthDeg, cue)
}
