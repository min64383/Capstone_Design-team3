package hearspace.core.replay

import hearspace.core.pipeline.ClusterDebugCsv
import hearspace.core.session.RunLog
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.Closeable
import java.io.File
import java.io.Writer

/**
 * 재생 결과를 실행 로그(MVP §10.1, 앱과 같은 형식)로 [outDir]에 쓴다. 단계별 처리 시간은 `stage_timing.csv`에 따로.
 * `replay_info.json`에는 세션 경로·느린 경로 시간 방식·설정 덮어쓰기를 남긴다(분석 도구가 같은 설정을 쓰도록).
 * 재생이 예외로 끝나도 쌓인 줄을 잃지 않게 호출한 쪽이 [close]한다(`use`, A1 FA9). 끝까지 돈 표시(`replay_done`)는 호출한 쪽이
 * 모든 출력을 쓴 뒤 [OfflineReplay.markDone]으로 남긴다. 같은 폴더에 남은 옛 표시는 여기서 지운다.
 */
class RunLogWriter(
    outDir: File,
    session: File,
    overridesJson: String,
    slowPathNote: String,
    /** 정답 대입 내용(JSON 객체, M12.3). 없으면 null. */
    substitutionJson: String? = null,
    /** RGB 안내 보정의 RGB 원천(M19, [RgbSource] 이름). */
    rgbSource: String = RgbSource.RECORDED.name,
    /** 학습형 깊이 장(M37 `depthFrom`, JSON 객체). 없으면 null. */
    depthFromJson: String? = null,
    /** 움직임 정답의 출처(M48 평가 전용, 예: clicks). 없으면 null(기록하지 않음). */
    movingFrom: String? = null,
) : ReplayListener, Closeable {
    private val slowOut: Writer
    private val guidOut: Writer
    private val obsOut: Writer
    private val clusterOut: Writer
    private val stageOut: Writer
    private var planesOut: Writer? = null
    private var motionOut: Writer? = null
    private var evidenceOut: Writer? = null
    private var geometryOut: Writer? = null
    private var m54Out: Writer? = null
    private var insertsOut: Writer? = null
    private var rolesOut: Writer? = null
    private var planeOut: Writer? = null
    private var decaysOut: Writer? = null
    private var frameClustersOut: Writer? = null
    private val outDir = outDir

    init {
        outDir.mkdirs()
        File(outDir, OfflineReplay.DONE_FILE).delete()
        File(outDir, RunLog.GEOMETRY_FILE).delete() // No stale diagnostics when rerunning SINGLE in the same directory.
        File(outDir, OfflineReplay.INFO_FILE).writeText(
            "{\n  \"session\": \"${session.absolutePath.replace("\\", "/")}\",\n  \"slowPath\": \"$slowPathNote\",\n" +
                "  \"rgbSource\": \"$rgbSource\",\n" +
                // 재생한 코드 판(M41, Gradle이 넘김: git 커밋 짧은 해시, 바뀐 작업 트리면 -dirty). 없으면 unknown
                "  \"gitHash\": \"${System.getProperty("hearspace.gitHash") ?: "unknown"}\",\n" +
                "  \"configOverrides\": $overridesJson" + (substitutionJson?.let { ",\n  \"substitution\": $it" } ?: "") +
                (depthFromJson?.let { ",\n  \"depthFrom\": $it" } ?: "") + (movingFrom?.let { ",\n  \"movingFrom\": \"$it\"" } ?: "") + "\n}\n",
        )
        slowOut = File(outDir, RunLog.SLOW_PATH_FILE).bufferedWriter()
        guidOut = File(outDir, RunLog.GUIDANCE_FILE).bufferedWriter()
        obsOut = File(outDir, RunLog.OBSTACLES_FILE).bufferedWriter()
        clusterOut = File(outDir, RunLog.CLUSTER_DEBUG_FILE).bufferedWriter()
        stageOut = File(outDir, RunLog.STAGE_TIMING_FILE).bufferedWriter()
        clusterOut.line(ClusterDebugCsv.HEADER)
        slowOut.line(RunLog.header(RunLog.SLOW_PATH_HEADER))
        guidOut.line(RunLog.header(RunLog.GUIDANCE_HEADER))
        obsOut.line(RunLog.header(RunLog.OBSTACLES_HEADER))
        stageOut.line(RunLog.header(RunLog.STAGE_TIMING_HEADER))
    }

    override fun onSlowStep(step: SlowStep) {
        slowOut.line(RunLog.slowPathLine(step.mapUpdate, step.startNs, step.doneNs, step.snapshot))
        RunLog.obstacleLines(step.snapshot).forEach { obsOut.line(it) }
        RunLog.clusterDebugLines(step.clusterDebug).forEach { clusterOut.line(it) }
        stageOut.line(RunLog.stageTimingLine(step.depth.tCaptureNs, step.stageNs))
        if (step.motion.isNotEmpty()) { // M48 `track.motion` KALMAN일 때만 생긴다(끈 설정은 파일 목록이 그대로)
            val w = motionOut ?: File(outDir, MOTION_FILE).bufferedWriter().also { it.line(MOTION_HEADER); motionOut = it }
            for (m in step.motion) w.line(motionLine(m))
        }
        if (step.evidence.isNotEmpty()) { // M57 `track.freeSpaceRecord`일 때만 생긴다
            val w = evidenceOut ?: File(outDir, EVIDENCE_FILE).bufferedWriter().also { it.line(EVIDENCE_HEADER); evidenceOut = it }
            for (e in step.evidence) w.line(listOf(
                e.tRawNs, e.tNs, e.id, e.candidate, e.moving, e.ageS, e.nPts, e.nCmp, e.nViol, e.zMedM, e.rotDegS, (e.lo.x + e.hi.x) / 2, (e.lo.z + e.hi.z) / 2, e.lo.x, e.lo.z, e.hi.x, e.hi.z,
            ).joinToString(",") + "," + e.labels.joinToString(",") + "," + (if (e.ownLabels.isEmpty()) IntArray(12) else e.ownLabels).joinToString(","))
        }
        step.mapUpdate.geometry?.let { result ->
            val w = geometryOut ?: File(outDir, RunLog.GEOMETRY_FILE).bufferedWriter().also {
                it.line(RunLog.header(RunLog.GEOMETRY_HEADER)); geometryOut = it
            }
            RunLog.geometryLines(step.depth.tCaptureNs, result).forEach { w.line(it) }
        }
        step.m54Counts?.let { c -> // M54 옵션을 켰을 때만(끈 설정은 파일 목록이 그대로)
            val w = m54Out ?: File(outDir, M54_FILE).bufferedWriter().also { it.line("tCaptureNs,bandRemovedPx,movableExcluded,movableRawInserted"); m54Out = it }
            w.line("${step.depth.tCaptureNs},${c[0]},${c[1]},${c[2]}")
        }
        step.occupiedVoxels?.takeIf { vs -> vs.any { it.role != hearspace.core.mapping.VoxelRole.NONE } }?.let { vs -> // M54 칸 역할(mapInserts + 칸 라벨 표)
            val w = rolesOut ?: File(outDir, ROLES_FILE).bufferedWriter().also { it.line("tCaptureNs,ix,iy,iz,role"); rolesOut = it }
            for (v in vs) w.line("${step.depth.tCaptureNs},${v.ix},${v.iy},${v.iz},${v.role}")
        }
        step.framePlane?.let { p -> // M54 프레임 바닥면(칸 라벨 표를 켰을 때만)
            val w = planeOut ?: File(outDir, FRAME_FLOOR_FILE).bufferedWriter().also { it.line("tCaptureNs,a,b,c,n,fresh"); planeOut = it }
            w.line("${step.depth.tCaptureNs},${p.a},${p.b},${p.c},${p.n},${p.fresh}")
        }
        step.frameClusters?.let { cs -> // M54 프레임 군집 법선(칸 역할 기록 장에서만)
            val w = frameClustersOut ?: File(outDir, FRAME_CLUSTERS_FILE).bufferedWriter().also {
                it.line("tCaptureNs,kind,n,cx,cy,cz,nx,ny,nz,thicknessM,lengthM"); frameClustersOut = it
            }
            for (c in cs) w.line("${step.depth.tCaptureNs},${c.kind},${c.n},${c.centerW.x},${c.centerW.y},${c.centerW.z},${c.normalW.x},${c.normalW.y},${c.normalW.z},${c.thicknessM},${c.lengthM}")
        }
        step.decayedVoxels?.let { v -> // M54 감쇠한 칸과 그 화소 신뢰도 구간(평가 전용)
            val w = decaysOut ?: File(outDir, DECAYS_FILE).bufferedWriter().also { it.line("tCaptureNs,ix,iy,iz,cls"); decaysOut = it }
            for (i in 0 until v.size / 4) w.line("${step.depth.tCaptureNs},${v[4 * i]},${v[4 * i + 1]},${v[4 * i + 2]},${v[4 * i + 3]}")
        }
        step.insertedVoxels?.let { v ->
            val w = insertsOut ?: File(outDir, INSERTS_FILE).bufferedWriter().also { it.line("tCaptureNs,ix,iy,iz"); insertsOut = it }
            for (i in 0 until v.size / 3) w.line("${step.depth.tCaptureNs},${v[3 * i]},${v[3 * i + 1]},${v[3 * i + 2]}")
        }
        step.mapUpdate.planes?.let { p ->
            val w = planesOut ?: File(outDir, RunLog.PLANES_FILE).bufferedWriter().also { it.line(RunLog.header(RunLog.PLANES_HEADER)); planesOut = it }
            w.line(RunLog.planesLine(step.depth.tCaptureNs, p))
        }
    }

    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) {
        RunLog.guidanceLines(g, pose.tCaptureNs, snapshot?.tCaptureNs).forEach { guidOut.line(it) }
    }

    override fun onEnd() = close()

    /** 기록 파일을 모두 닫는다(여러 번 불러도 된다). */
    override fun close() {
        listOfNotNull(slowOut, guidOut, obsOut, clusterOut, stageOut, planesOut, motionOut, evidenceOut, geometryOut, m54Out, insertsOut, rolesOut, planeOut, frameClustersOut, decaysOut).forEach { it.close() }
    }

    private fun motionLine(m: hearspace.core.tracking.MotionDebug): String = listOf(
        m.tCaptureNs, m.id, m.candidate, m.moving, m.measW?.x, m.measW?.z, m.posW?.x, m.posW?.z, m.velW?.x, m.velW?.z,
        m.posSigmaM, m.velSigmaMps, m.chi2, m.nMeasCells, m.votes, m.score, m.measTNs, m.labelVotes, m.labelScore, m.nRawPoints,
    ).joinToString(",") { it?.toString() ?: "" }

    companion object {
        /** 움직임 기록(M48): track마다 칼만·판정·물체 좌표 칸 수. */
        const val MOTION_FILE = "motion.csv"
        /** 빈 곳 위반 기록(M57 2단계). */
        const val EVIDENCE_FILE = "motion_evidence.csv"
        val EVIDENCE_HEADER = "tRawNs,tNs,id,candidate,moving,ageS,nPts,nCmp,nViol,zMedM,rotDegS,cx,cz,loX,loZ,hiX,hiZ," +
            (0 until 12).joinToString(",") { "lab$it" } + "," + (0 until 12).joinToString(",") { "own$it" }
        const val M54_FILE = "m54.csv"
        const val INSERTS_FILE = "map_inserts.csv"
        const val ROLES_FILE = "map_roles.csv"
        const val FRAME_FLOOR_FILE = "frame_floor.csv"
        const val DECAYS_FILE = "map_decays.csv"
        const val FRAME_CLUSTERS_FILE = "frame_clusters.csv"
        const val MOTION_HEADER = "tCaptureNs,id,candidate,moving,measX,measZ,posX,posZ,velX,velZ,posSigmaM,velSigmaMps,chi2,nMeasCells,votes,score," +
            "measTNs,labelVotes,labelScore,nRawPoints"
    }

    private fun Writer.line(s: String) {
        write(s)
        write("\n")
    }
}
