package hearspace.core.replay

import hearspace.core.geometry.Vec3
import hearspace.core.session.SessionReader
import hearspace.core.truth.Alignment
import hearspace.core.truth.Element
import hearspace.core.truth.GroundTruth
import hearspace.core.truth.MapCheck
import hearspace.core.truth.MapEval
import hearspace.core.truth.SceneEval
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.MiniJson
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import hearspace.core.types.DepthSource
import hearspace.core.types.TrackingState
import hearspace.core.session.DepthEvent
import hearspace.core.session.PoseEvent
import java.io.File

/**
 * 정답이 있는 세션의 지도 평가(`map_eval.json`, M13). [inner](실행 로그)에 그대로 넘기면서, 느린 경로 [everyN]번째마다와
 * 마지막 점유 복셀을 모아 재생이 끝나면 정답 구조물의 두께·앞 치우침을 쓴다([MapEval]). 정렬의 바닥은 이 재생의 바닥 추정 중앙값.
 * 정답에 구조물(벽)이 있어 장면 전체가 정답으로 덮이면 같은 표본으로 장면 지표 `scene_eval.json`도 쓴다(M30, [SceneEval]).
 * [align]을 주면(정답 대입 재생의 기준 정렬, 합성 장면의 참 정렬) 바닥 추정 대신 그 정렬을 쓴다. 세션에 `floor_rgb.json`(M26)이
 * 있으면 그 바닥으로 맞춘 정렬의 장면 지표를 `rgbFloor` 항목으로 함께 낸다. 같은 표본으로 정답 없는 검사 `map_check.json`(M33, [MapCheck])도
 * 쓴다: 이것만 세션의 나중 자세와 나중 깊이 영상(원시·평활, 변형과 무관한 시험 깊이)을 읽는다(평가 전용).
 */
class MapEvalWriter(
    private val inner: ReplayListener,
    private val outDir: File,
    private val session: File,
    private val config: Config,
    private val everyN: Int = 15,
    private val align: Alignment? = null,
    /** 장면 지표 평가 영역의 높이 상한(m). 녹화 세션은 통로 높이(벽 높이·천장은 실측이 아님, M30). */
    private val sceneHeightM: Float = config.corridor.heightM,
    /** 장면 지표 광선 표본 간격(화소). */
    private val sceneStridePx: Int = 4,
    /**
     * [align]을 읽은 정렬 파일(`floorRef`가 있는 독립 정렬, M32). 있으면 장면 지표에 판정 불가 띠(M33 [SceneEval.Band])를 둔다: 바닥 u = 그 파일의
     * 바닥 값(RGB·평활·원시) 범위, 벽 u = 표시 오차 [MARK_ERROR_M] + 거리 × tan(정렬 각 불확실성).
     */
    private val alignFile: File? = null,
) : ReplayListener {
    override val captureVoxels = true

    /** 빈 곳 칸은 장면 지표 표본 단계만(마지막 단계는 표본이 아니면 빈 곳 없이 남아 TRISTATE에서는 빠진다). */
    override fun captureFree(stepIndex: Int) = stepIndex % everyN == 0
    private val samples = ArrayList<Pair<Long, List<Vec3>>>()
    private var last: Pair<Long, List<Vec3>>? = null
    private val floors = ArrayList<Float?>()
    private var n = 0
    private val sceneSamples = ArrayList<SceneInput>()
    private var lastScene: SceneInput? = null

    private class SceneInput(val depth: DepthFrame, val mapDepthMm: ShortArray, val occupiedW: List<Vec3>, val freeW: List<Vec3>?, val lastSeenNs: LongArray)

    override fun onSlowStep(step: SlowStep) {
        inner.onSlowStep(step)
        floors += step.snapshot.floorY
        val v = step.depth.tCaptureNs to step.occupiedVoxels!!.map { it.centerW }
        val s = SceneInput(step.depth, step.mapDepthMm ?: step.depth.depthMm, v.second, step.freeCentersW, step.occupiedVoxels!!.map { it.lastSeenNs }.toLongArray())
        if (n++ % everyN == 0) { samples += v; sceneSamples += s }
        last = v
        lastScene = s
    }

    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) = inner.onBlock(tNs, pose, snapshot, g)

    override fun onEnd() {
        inner.onEnd()
        val truth = GroundTruth.read(session, config.head.offsetFromCameraM) ?: return
        val al = align ?: Alignment.medianFloorY(floors)?.let {
            Alignment.fit(SessionReader(session).rows, config.align, config.head.offsetFromCameraM, it)
        } ?: return
        writeScene(truth, al)
        val steps = (samples + listOfNotNull(last)).distinctBy { it.first }
        val walls = steps.map { (t, v) -> t to MapEval.walls(MapEval.toTruth(v, al), truth, config.map.voxelSizeM) }
        if (walls.all { it.second.isEmpty() }) return
        File(outDir, FILE).writeText(MapEval.toJson(walls, config.map.voxelSizeM))
    }

    private fun writeScene(truth: GroundTruth, al: Alignment) {
        val scene = SceneEval.scene(truth, sceneHeightM) ?: return
        val tristate = config.map.mode == hearspace.core.types.MapMode.TRISTATE
        val inputs = (sceneSamples + listOfNotNull(lastScene?.takeIf { !tristate || it.freeW != null })).distinctBy { it.depth.tCaptureNs }
        val voxelM = config.map.voxelSizeM
        fun pool(a: Alignment) = SceneEval.Pool(
            inputs.map { s ->
                // 지금 지도는 칸에 요소 라벨이 없다: 점유 칸은 모두 장애물(물체) 후보로 쓰인다(M30 설계 3.1절)
                SceneEval.sample(
                    scene, a, voxelM, config.map.radiusM, s.occupiedW, s.occupiedW.map { Element.OBJECT },
                    s.freeW?.let { SceneEval.threeState(s.occupiedW, it, voxelM) } ?: SceneEval.occupancyOnly(s.occupiedW, voxelM),
                    s.depth, s.mapDepthMm, sceneStridePx, band(a),
                )
            },
            voxelM,
        )
        val rgbFloorY = listOf(File(session, FLOOR_RGB_FILE), File(outDir, FLOOR_RGB_FILE)).firstOrNull { it.isFile }?.let { f ->
            ((MiniJson.parse(f.readText()) as? JsonObject)?.fields?.get("floorY") as? JsonNumber)?.value
        }
        val extra = linkedMapOf<String, hearspace.core.types.JsonValue>("floorY" to JsonNumber(al.floorY))
        if (rgbFloorY != null) extra["rgbFloor"] = MiniJson.parse(pool(al.copy(floorY = rgbFloorY)).toJson(mapOf("floorY" to JsonNumber(rgbFloorY))))
        File(outDir, SCENE_FILE).writeText(pool(al).toJson(extra))
        writeFalseCells(scene, al, inputs)
        writeCheck(scene, al, inputs)
    }

    /**
     * 정답 밖 물체 점검(M33 `tools/analysis/unmodeled.py`)용: 표본마다 헛 칸(정답 좌표 칸)을 모아 칸마다 헛 칸으로 센 표본 수와 처음·마지막
     * 시각(깊이 촬영 시각)을 `false_cells.csv`로 쓴다. 평가 전용 출력이다.
     */
    private fun writeFalseCells(scene: SceneEval.Scene, al: Alignment, inputs: List<SceneInput>) {
        val voxelM = config.map.voxelSizeM
        val tau = SceneEval.tau(voxelM)
        val acc = HashMap<Triple<Int, Int, Int>, LongArray>() // 표본 수, 처음, 마지막
        for (s in inputs) {
            val seen = HashSet<Triple<Int, Int, Int>>()
            for (w in s.occupiedW) {
                val p = al.toTruth(w)
                if (!scene.inRegion(p) || SceneEval.classify(scene, p, tau) != hearspace.core.truth.VoxelClass.FALSE) continue
                val k = Triple(kotlin.math.floor(p.x / voxelM).toInt(), kotlin.math.floor(p.y / voxelM).toInt(), kotlin.math.floor(p.z / voxelM).toInt())
                if (!seen.add(k)) continue
                val a = acc.getOrPut(k) { longArrayOf(0, s.depth.tCaptureNs, s.depth.tCaptureNs) }
                a[0]++
                a[2] = s.depth.tCaptureNs
            }
        }
        File(outDir, FALSE_CELLS_FILE).bufferedWriter().use { w ->
            w.write("ix,iy,iz,nSamples,tFirstNs,tLastNs\n")
            for ((k, a) in acc) w.write("${k.first},${k.second},${k.third},${a[0]},${a[1]},${a[2]}\n")
        }
    }

    private fun band(a: Alignment): SceneEval.Band? {
        val f = alignFile?.takeIf { it.isFile } ?: return null
        val ref = (MiniJson.parse(f.readText()) as? JsonObject)?.fields?.get("floorRef") as? JsonObject ?: return null
        val ys = listOf("rgbM", "smoothedM", "rawM").mapNotNull { (ref.fields[it] as? JsonNumber)?.value }
        if (ys.isEmpty()) return null
        return SceneEval.Band((ys.max() - ys.min()).toFloat(), MARK_ERROR_M, a.angleUncertaintyDeg.toFloat())
    }

    private fun writeCheck(scene: SceneEval.Scene, al: Alignment, inputs: List<SceneInput>) {
        val reader = SessionReader(session)
        val poses = ArrayList<PoseFrame>()
        val raw = ArrayList<DepthFrame>()
        for (e in reader.events(DepthSource.RAW)) when (e) {
            is PoseEvent -> if (e.pose.tracking == TrackingState.TRACKING) poses += e.pose
            is DepthEvent -> raw += e.depth
        }
        val smoothed = reader.events(DepthSource.SMOOTHED).filterIsInstance<DepthEvent>().map { it.depth }.toList()
        val tests = linkedMapOf<String, Pair<Boolean, List<DepthFrame>>>()
        if (raw.isNotEmpty()) tests["raw"] = true to raw
        if (smoothed.isNotEmpty()) tests["smoothed"] = false to smoothed
        File(outDir, CHECK_FILE).writeText(
            MapCheck.run(
                inputs.map { MapCheck.Snapshot(it.depth.tCaptureNs, it.occupiedW, it.lastSeenNs) }, poses, tests, config.depth, al, scene,
                config.map.voxelSizeM, config.map.radiusM, config.head.offsetFromCameraM, config.map.tsdfMinCosIncidence,
            ),
        )
    }

    companion object {
        const val FILE = "map_eval.json"
        const val SCENE_FILE = "scene_eval.json"
        const val CHECK_FILE = "map_check.json"
        const val FALSE_CELLS_FILE = "false_cells.csv"

        /** 사람이 시작 표시에 서는 오차(m, M12.0). */
        const val MARK_ERROR_M = 0.03f
        private const val FLOOR_RGB_FILE = "floor_rgb.json"
    }
}
