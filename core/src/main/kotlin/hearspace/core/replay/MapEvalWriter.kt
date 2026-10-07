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
 * 정답이 있는 세션의 지도 평가(`map_eval.json`, M13). [inner](실행 로그)에 그대로 넘기면서, 느린 경로 [everyN]번째마다와
 * 마지막 점유 복셀을 모아 재생이 끝나면 정답 구조물의 두께·앞 치우침을 쓴다([MapEval]). 정렬의 바닥은 이 재생의 바닥 추정 중앙값.
 */
class MapEvalWriter(
    private val inner: ReplayListener,
    private val outDir: File,
    private val session: File,
    private val config: Config,
    private val everyN: Int = 15,
) : ReplayListener {
    override val captureVoxels = true
    private val samples = ArrayList<Pair<Long, List<Vec3>>>()
    private var last: Pair<Long, List<Vec3>>? = null
    private val floors = ArrayList<Float?>()
    private var n = 0

    override fun onSlowStep(step: SlowStep) {
        inner.onSlowStep(step)
        floors += step.snapshot.floorY
        val v = step.depth.tCaptureNs to step.occupiedVoxels!!.map { it.centerW }
        if (n++ % everyN == 0) samples += v
        last = v
    }

    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) = inner.onBlock(tNs, pose, snapshot, g)

    override fun onEnd() {
        inner.onEnd()
        val truth = GroundTruth.read(session, config.head.offsetFromCameraM) ?: return
        val floorY = Alignment.medianFloorY(floors) ?: return
        val al = Alignment.fit(SessionReader(session).rows, config.align, config.head.offsetFromCameraM, floorY)
        val steps = (samples + listOfNotNull(last)).distinctBy { it.first }
        val walls = steps.map { (t, v) -> t to MapEval.walls(MapEval.toTruth(v, al), truth, config.map.voxelSizeM) }
        if (walls.all { it.second.isEmpty() }) return
        File(outDir, FILE).writeText(MapEval.toJson(walls, config.map.voxelSizeM))
    }

    companion object {
        const val FILE = "map_eval.json"
    }
}
