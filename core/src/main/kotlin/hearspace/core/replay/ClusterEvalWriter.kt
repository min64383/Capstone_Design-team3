package hearspace.core.replay

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.VoxelMap
import hearspace.core.session.SessionReader
import hearspace.core.truth.Alignment
import hearspace.core.truth.ClusterEval
import hearspace.core.truth.GroundTruth
import hearspace.core.types.Config
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.File

/**
 * 정답이 있는 세션의 군집 칸 단위 평가(`cluster_eval.csv`, M23, [ClusterEval]). [inner]에 그대로 넘기면서 느린 경로마다 군집 칸과
 * 그 장의 칸 상태를 모아 두었다가, 재생이 끝나면 정답 좌표로 옮겨 쓴다. 정렬은 [align](재생 인자 `alignFrom`, 변형끼리 같은 정답)이
 * 있으면 그것, 없으면 [MapEvalWriter]와 같이 이 재생의 바닥 중앙값으로 맞춘다.
 */
class ClusterEvalWriter(
    private val inner: ReplayListener,
    private val outDir: File,
    private val session: File,
    private val config: Config,
    private val align: Alignment?,
) : ReplayListener {
    override val captureVoxels get() = inner.captureVoxels
    override fun captureFree(stepIndex: Int) = inner.captureFree(stepIndex)

    private class Step(val tNs: Long, val headW: Vec3, val clusters: List<Pair<FloatArray, ByteArray>>)

    private val steps = ArrayList<Step>()
    private val floors = ArrayList<Float?>()
    private val rgbEdges = IntArray(3)

    /** 칸 상태 판정만 쓴다(설정의 빈 공간 여유만 보고 지도 내용은 보지 않는다). */
    private val sight = VoxelMap(config.map)

    override fun onSlowStep(step: SlowStep) {
        inner.onSlowStep(step)
        floors += step.snapshot.floorY
        step.mapUpdate.rgbEdgeCounts?.forEachIndexed { i, c -> rgbEdges[i] += c }
        if (!step.applied) return
        val d = step.depth
        val depthMm = step.mapDepthMm
        val camFromWorld = d.worldFromCam.rigidInverse()
        steps += Step(d.tCaptureNs, step.userPosW, step.clusters.map { c ->
            val xyz = FloatArray(c.size * 3)
            val st = ByteArray(c.size)
            for ((i, v) in c.withIndex()) {
                xyz[3 * i] = v.centerW.x
                xyz[3 * i + 1] = v.centerW.y
                xyz[3 * i + 2] = v.centerW.z
                st[i] = if (depthMm == null) -1 else sight.cellView(v.centerW, v.lastSeenNs == d.tCaptureNs, depthMm, d.K, camFromWorld).ordinal.toByte()
            }
            xyz to st
        })
    }

    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) = inner.onBlock(tNs, pose, snapshot, g)

    override fun onEnd() {
        inner.onEnd()
        // RGB 경계 옮기기(M23)를 켠 재생: 경계 중 바닥이라 건너뛴 것·RGB 경계가 있어 옮긴 것(나머지는 가중 중앙값)
        if (rgbEdges[0] > 0) {
            File(outDir, RGB_EDGE_FILE).writeText("{ \"edges\": ${rgbEdges[0]}, \"floorSkipped\": ${rgbEdges[1]}, \"rgbEdge\": ${rgbEdges[2]} }\n")
        }
        val truth = GroundTruth.read(session, config.head.offsetFromCameraM) ?: return
        val al = align ?: Alignment.medianFloorY(floors)?.let { Alignment.fit(SessionReader(session).rows, config.align, config.head.offsetFromCameraM, it) } ?: return
        File(outDir, FILE).bufferedWriter().use { w ->
            w.write(ClusterEval.CSV_HEADER + "\n")
            for (s in steps) {
                val cells = s.clusters.map { (xyz, st) ->
                    ClusterEval.Cells(List(st.size) { i -> al.toTruth(Vec3(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2])) }, st)
                }
                for (r in ClusterEval.evaluate(al.toTruth(s.headW), cells, truth, config.corridor, config.map.voxelSizeM)) {
                    w.write(ClusterEval.csvLine(s.tNs, r) + "\n")
                }
            }
        }
    }

    companion object {
        const val FILE = "cluster_eval.csv"
        const val RGB_EDGE_FILE = "rgb_edge.json"
    }
}
