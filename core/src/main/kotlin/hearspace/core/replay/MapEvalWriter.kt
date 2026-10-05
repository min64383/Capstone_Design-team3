package hearspace.core.replay

import hearspace.core.geometry.Vec3
import hearspace.core.session.SessionReader
import hearspace.core.truth.Alignment
import hearspace.core.truth.GroundTruth
import hearspace.core.truth.MapEval
import hearspace.core.types.Config
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.File

/**
 * 정답이 있는 세션에서 느린 경로마다 맵 정확도를 재서 [outDir]에 `map_eval.csv`(단계마다)와 `map_eval.json`(요약)을 쓴다
 * (IMPROVE_SPEC §6.1, M12). 정렬에 세션 전체의 바닥 높이가 필요해 끝([onEnd])에 계산한다(평가 전용).
 */
class MapEvalWriter(private val outDir: File, private val session: File, private val config: Config) : ReplayListener {
    override val captureVoxels = true

    private class Rec(val tCaptureNs: Long, val camW: Vec3, val centersW: FloatArray, val floorY: Float?)

    private val recs = ArrayList<Rec>()

    override fun onSlowStep(step: SlowStep) {
        val v = step.occupiedVoxels!!
        val c = FloatArray(v.size * 3)
        v.forEachIndexed { i, x -> c[3 * i] = x.centerW.x; c[3 * i + 1] = x.centerW.y; c[3 * i + 2] = x.centerW.z }
        recs += Rec(step.depth.tCaptureNs, step.depth.worldFromCam.translation(), c, step.snapshot.floorY)
    }

    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) = Unit

    override fun onEnd() {
        val off = config.head.offsetFromCameraM
        val truth = GroundTruth.read(session, off) ?: return
        val floorY = Alignment.medianFloorY(recs.map { it.floorY }) ?: return
        val al = Alignment.fit(SessionReader(session).rows, config.align.fitLengthM, off, floorY)
        val steps = recs.map { r ->
            val c = al.toTruth(r.camW)
            MapEval.step(r.tCaptureNs, r.centersW, truth, al, Vec3(c.x + off.x, c.y, c.z + off.z), config.corridor, config.map.voxelSizeM)
        }
        outDir.mkdirs()
        File(outDir, CSV).bufferedWriter().use { w ->
            w.write("tCaptureNs,nOccupied,nPhantom,nObject,objectsInRange,objectsSeen\n")
            for (s in steps) w.write("${s.tCaptureNs},${s.nOccupied},${s.nPhantom},${s.nObject},${s.objectsInRange},${s.objectsSeen}\n")
        }
        File(outDir, JSON).writeText(MapEval.summary(steps).toJson() + "\n")
    }

    companion object {
        const val CSV = "map_eval.csv"
        const val JSON = "map_eval.json"
    }
}

/** 여러 받는 쪽에 같은 재생 결과를 넘긴다. 하나라도 복셀을 원하면 복셀을 모은다. */
class TeeListener(private vararg val listeners: ReplayListener) : ReplayListener {
    override val captureVoxels: Boolean get() = listeners.any { it.captureVoxels }
    override fun onSlowStep(step: SlowStep) = listeners.forEach { it.onSlowStep(step) }
    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) = listeners.forEach { it.onBlock(tNs, pose, snapshot, g) }
    override fun onEnd() = listeners.forEach { it.onEnd() }
}
