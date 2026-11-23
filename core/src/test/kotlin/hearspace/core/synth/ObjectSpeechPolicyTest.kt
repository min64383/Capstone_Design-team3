package hearspace.core.synth

import hearspace.core.audio.ObjectLabels
import hearspace.core.audio.ObjectSpeechConfig
import hearspace.core.audio.ObjectSpeechFrame
import hearspace.core.audio.ObjectSpeechPolicy
import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.types.*
import org.junit.jupiter.api.Test

class ObjectSpeechPolicyTest {
    private val cfg = ObjectSpeechConfig(true, true, 3, 0.6f, 22.5f, 0, 0, 10000, 1500)
    private val ageNs = 500_000_000L
    private val now = 1_000_000_000L
    private val point = Vec3(0f, 0f, 1f)

    private fun frame(
        label: SemanticLabel? = SemanticLabel.PERSON,
        time: Long = now, band: Band = Band.WARN, angle: Float = -35f,
        state: GuidanceState = GuidanceState.NORMAL, seen: Long = time,
        fraction: Float = 1f, labelTime: Long = time, epoch: Long = 0,
        id: Int = 1, head: Boolean = false,
    ): ObjectSpeechFrame {
        val classification = label?.let { ObjectClassification(it, fraction, labelTime) }
        val obstacle = Obstacle(id, point, RepStrategy.entries.associateWith { point },
            Vec3(-1f, -1f, 0.8f), Vec3(1f, 1f, 1.2f),
            if (head) HeightClass.HEAD else HeightClass.BODY, true, 1f, seen, 10,
            classification = classification)
        val command = AudioCmd(id, angle, if (band == Band.STOP) 1f else 2f, band, SoundKind.FLOOR_PULSE, 0f)
        return ObjectSpeechFrame(GuidanceOutput(time, state, listOf(command), 0f, null),
            ObstacleSnapshot(time, listOf(obstacle), 0f, MapHealth.OK), epoch)
    }

    @Test fun formatsExistingClassesDirectionAndDistance() {
        for ((label, name) in listOf(SemanticLabel.PERSON to "사람", SemanticLabel.VEHICLE to "차량",
            SemanticLabel.TREE to "나무", SemanticLabel.STRUCTURE to "구조물")) {
            val policy = ObjectSpeechPolicy(cfg, ageNs)
            check(policy.update(frame(label), now).request?.text == "왼쪽, $name, 2미터.")
        }
        check(ObjectSpeechPolicy(cfg, ageNs).update(frame(angle = 0f), now).request?.text == "정면, 사람, 2미터.")
        check(ObjectSpeechPolicy(cfg, ageNs).update(frame(angle = 40f), now).request?.text == "오른쪽, 사람, 2미터.")
    }

    @Test fun selectsStopFirstAndMatchesClassificationByObjectId() {
        val person = frame(id = 1)
        val vehicle = frame(id = 2, label = SemanticLabel.VEHICLE, band = Band.STOP, angle = 40f)
        val input = person.copy(
            output = person.output.copy(commands = person.output.commands + vehicle.output.commands),
            snapshot = person.snapshot!!.copy(obstacles = vehicle.snapshot!!.obstacles + person.snapshot.obstacles),
        )
        val request = ObjectSpeechPolicy(cfg, ageNs).update(input, now).request!!
        check(request.signature.obstacleId == 2)
        check(request.text == "오른쪽, 차량, 1미터. 잠시 멈춰 주세요.")
    }

    @Test fun fallsBackForMissingStaleAndLowSupportClasses() {
        for (input in listOf(frame(label = null), frame(fraction = 0.4f),
            frame(labelTime = now - ageNs - 1), frame(labelTime = now + 1),
            frame(label = SemanticLabel.SIDEWALK))) {
            check(ObjectSpeechPolicy(cfg, ageNs).update(input, now).request?.text == "왼쪽, 장애물, 2미터.")
        }
        check(ObjectSpeechPolicy(cfg, ageNs).update(frame(label = null, head = true), now)
            .request?.text == "왼쪽, 머리 높이 장애물, 2미터.")
    }

    @Test fun rejectsStaleFuturePausedAndOutsideInputs() {
        for (input in listOf(frame(time = now - ageNs - 1), frame(time = now + 1),
            frame(seen = now - ageNs - 1), frame(state = GuidanceState.UNKNOWN),
            frame(state = GuidanceState.PAUSED))) {
            check(ObjectSpeechPolicy(cfg, ageNs).update(input, now).request == null)
        }
        val source = frame()
        val outside = source.copy(output = source.output.copy(commands = source.output.commands.map { it.copy(inCorridor = false) }))
        check(ObjectSpeechPolicy(cfg, ageNs).update(outside, now).request == null)
        val bad = source.copy(output = source.output.copy(commands = source.output.commands.map { it.copy(distanceM = Float.NaN) }))
        check(ObjectSpeechPolicy(cfg, ageNs).update(bad, now).request == null)
        val degradedFloor = source.copy(snapshot = source.snapshot!!.copy(mapHealth = MapHealth.DEGRADED))
        check(ObjectSpeechPolicy(cfg, ageNs).update(degradedFloor, now).request == null)
    }

    @Test fun interruptsNormalWithStopAndUsesActualCompletion() {
        val policy = ObjectSpeechPolicy(cfg, ageNs)
        val normal = policy.update(frame(angle = 0f), now).request!!
        policy.onAccepted(normal)
        policy.onStarted(normal.key, now)
        val urgent = policy.update(frame(angle = 0f, band = Band.STOP, time = now + 1), now + 1).request!!
        check(urgent.urgent && urgent.text == "정면, 사람, 1미터. 잠시 멈춰 주세요.")
        policy.onFinished(normal.key, now + 1, completed = false)
        policy.onAccepted(urgent)
        policy.onStarted(urgent.key, now + 1)
        policy.onFinished(normal.key, now + 2, completed = true)
        check(policy.update(frame(angle = 0f, band = Band.STOP, time = now + 3), now + 3).request == null)
        policy.onFinished(urgent.key, now + 4, completed = true)
        check(policy.update(frame(angle = 0f, band = Band.STOP, time = now + 5), now + 5).request == null)
    }

    @Test fun allowsRetryAfterFailedAdmissionAndFailedPlayback() {
        val policy = ObjectSpeechPolicy(cfg, ageNs)
        val first = policy.update(frame(), now).request!!
        val retry = policy.update(frame(time = now + 1), now + 1).request!!
        check(first.key != retry.key)
        policy.onAccepted(retry)
        policy.onStarted(retry.key, now + 1)
        policy.onFinished(retry.key, now + 2, completed = false)
        val later = now + 2_000_000_000L
        check(policy.update(frame(time = later), later).request != null)
    }

    @Test fun throttlesRejectedRequestsAndAnnouncesConfirmedClassChanges() {
        val policy = ObjectSpeechPolicy(cfg, ageNs)
        val first = policy.update(frame(band = Band.STOP), now).request!!
        policy.onRejected(first, now)
        check(policy.update(frame(band = Band.STOP, time = now + 1), now + 1).request == null)
        val later = now + 2_000_000_000L
        val retry = policy.update(frame(time = later), later).request!!
        policy.onAccepted(retry)
        policy.onStarted(retry.key, later)
        policy.onFinished(retry.key, later + 1, true)
        val changed = later + 2_000_000_000L
        check(policy.update(frame(label = SemanticLabel.VEHICLE, time = changed), changed)
            .request?.text == "왼쪽, 차량, 2미터.")
    }

    @Test fun cancelsLostObjectsAndResetsOnWorldChange() {
        val policy = ObjectSpeechPolicy(cfg, ageNs)
        val request = policy.update(frame(), now).request!!
        policy.onAccepted(request)
        policy.onStarted(request.key, now)
        check(policy.update(null, now + 1).cancelKey == request.key)
        val second = policy.update(frame(time = now + 2), now + 2).request!!
        policy.onAccepted(second)
        check(policy.update(frame(time = now + 3, epoch = 1), now + 3).cancelKey == second.key)
        check(policy.update(frame(time = now + 4, epoch = 1), now + 4).request != null)
    }

    @Test fun confirmationRequiresNewEvidenceAndRepeatUsesElapsedTime() {
        val policy = ObjectSpeechPolicy(cfg.copy(normalConfirmMs = 400), ageNs)
        check(policy.update(frame(), now).request == null)
        check(policy.update(frame(), now + 400_000_000L).request == null)
        val later = now + 400_000_000L
        val request = policy.update(frame(time = later), later).request!!
        policy.onAccepted(request)
        policy.onStarted(request.key, later)
        policy.onFinished(request.key, later + 1, completed = true)
        check(policy.update(frame(time = later + 2_000_000_000L), later + 2_000_000_000L).request == null)
        val again = later + 10_000_000_001L
        check(policy.update(frame(time = again), again).request == null)
        check(policy.update(frame(time = again + 400_000_000L), again + 400_000_000L).request != null)
    }

    @Test fun bindsDistinctVisibleSemanticPixelsToAnObstacle() {
        val input = frame()
        val obstacle = input.snapshot!!.obstacles.single().copy(classification = null)
        val k = Intrinsics(10f, 10f, 4f, 4f, 8, 8)
        val pixels = ByteArray(64) { SemanticLabel.UNLABELED.pixel.toByte() }
        val points = listOf(Vec3(-0.1f, 0f, 1f), Vec3(0f, 0f, 1f), Vec3(0.1f, 0f, 1f))
        val image = LabelImage(now, pixels, k, Mat4.IDENTITY)
        for (p in points) pixels[image.indexAt(p)] = SemanticLabel.PERSON.pixel.toByte()
        val depth = DepthFrame(now, ShortArray(64) { 1000 }, null, k, Mat4.IDENTITY, "synthetic", labels = image)
        val labels = ObjectLabels(cfg, 200_000_000L)
        val named = labels.attach(listOf(obstacle), points, depth).single()
        check(named.classification?.label == SemanticLabel.PERSON)
        check(ObjectSpeechPolicy(cfg, ageNs).update(input.copy(snapshot = input.snapshot.copy(obstacles = listOf(named))), now)
            .request?.text == "왼쪽, 사람, 2미터.")
        check(labels.attach(listOf(obstacle), List(5) { points[0] }, depth).single().classification == null)
        check(labels.attach(listOf(obstacle), points, depth.copy(tCaptureNs = now + 201_000_000L))
            .single().classification == null)
        check(labels.attach(listOf(obstacle), points, depth.copy(tCaptureNs = now - 1))
            .single().classification == null)
    }
}
