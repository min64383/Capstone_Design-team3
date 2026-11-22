package hearspace.core.session

import hearspace.core.mapping.MapUpdate
import hearspace.core.pipeline.ClusterDebug
import hearspace.core.pipeline.ClusterDebugCsv
import hearspace.core.pipeline.StageTimes
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.RepStrategy

/**
 * 실행 로그 CSV 형식 (§10.1). 앱(M7)과 PC 오프라인 재생(M8)이 같은 형식을 쓴다. `device.csv`는 [DeviceCsv].
 * 모든 시각은 같은 단조 시계 기준(ns). 값이 없으면 빈 칸.
 */
object RunLog {
    const val SLOW_PATH_FILE = "slow_path.csv"
    const val GUIDANCE_FILE = "guidance.csv"
    const val OBSTACLES_FILE = "obstacles.csv"

    const val GEOMETRY_FILE = "geometry_debug.csv"
    val GEOMETRY_HEADER = listOf("tCaptureNs", "primary", "type", "distanceM", "confidence", "detail", "profile")

    /** Diagnostic geometry only; no inference about safe passage from missing rows. */
    fun geometryLines(tCaptureNs: Long, result: hearspace.core.types.IndoorGeometryResult): List<String> {
        val profile = result.groundProfile.joinToString(";") { "${it.alongStartM}:${it.alongEndM}:${it.heightM ?: ""}:${it.nPoints}" }
        fun quoted(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val findings = result.findings.ifEmpty { listOf(hearspace.core.types.GeometryFinding(result.primary, 0f, 0f, "no ground estimate")) }
        return findings.map { f ->
            listOf(tCaptureNs, result.primary, f.type, f.distanceM, f.confidence, quoted(f.detail), quoted(profile)).joinToString(",")
        }
    }

    /** 군집 통계(오인식 분석용, 명세 §10.1 추가 로그, v0.2.10). 형식은 [ClusterDebugCsv]. */
    const val CLUSTER_DEBUG_FILE = "cluster_debug.csv"

    /**
     * 느린 경로 단계별 처리 시간(실제 시계, IMPROVE_SPEC §3-3, M11). 실행마다 달라지므로 다른 로그와 파일을 나눠
     * "같은 입력이면 같은 출력"인 로그를 지킨다.
     */
    const val STAGE_TIMING_FILE = "stage_timing.csv"

    val STAGE_TIMING_HEADER = listOf("tCaptureNs", "mapNs", "clusterNs", "trackNs")

    /**
     * 앱 입력 기록(M61, 앱 전용). 깊이(DEPTH)는 카메라 프레임마다 한 줄로 결과를 남기고, 원시 깊이(RAW)·의미 영상(LABEL)은 새 영상일 때만 남긴다.
     * frameTNs = 그 영상이 보인 카메라 프레임 시각, appNs = 그때 앱 시계(실시간은 frameTNs와 같은 시계라 차이 = 프레임 지연),
     * imageTNs = 영상 촬영 시각, copyNs = 읽기·복사 시간. result: PUBLISHED(느린 경로로 넘김) | NEW(원시·라벨 새 영상) | SAME(같은 시각 되풀이) |
     * NOT_TRACKING(그 시각 자세가 추적 중 아님) | NO_PIXELS | ARCore 예외 이름(새 영상을 받지 못함). tracking = 프레임의 추적 상태[:실패 이유].
     */
    const val INPUT_TIMING_FILE = "input_timing.csv"

    val INPUT_TIMING_HEADER = listOf("frameTNs", "appNs", "kind", "imageTNs", "copyNs", "result", "tracking")

    fun inputTimingLine(frameTNs: Long, appNs: Long, kind: String, imageTNs: Long?, copyNs: Long, result: String, tracking: String?): String =
        row(frameTNs, appNs, kind, imageTNs, copyNs, result, tracking)

    /** 상황 말(M50). PC 재생(`SituationListener`)과 앱(M65)이 같은 형식으로 쓴다. 시각은 판단 시계, ageMs는 그때의 근거 나이. */
    const val SPEECH_FILE = "speech.csv"
    const val SPEECH_HEADER_LINE = "tNs,priority,key,text,ageMs,interrupted"

    fun speechLine(u: hearspace.core.situation.Utterance): String =
        "${u.tNs},${u.priority},${u.key},\"${u.text.replace("\"", "")}\",${String.format(java.util.Locale.ROOT, "%.1f", u.ageMs)},${if (u.interrupted) 1 else 0}"

    /** 상황 판단 한 차례의 상태(M50, `situation.csv`). 없는 값은 빈 칸. */
    const val SITUATION_FILE = "situation.csv"
    const val SITUATION_HEADER_LINE = "tNs,env,envCandidate,nLines,followAngleDeg,lateralM,blockYawDeg,endAheadM,stairsAheadM,stairsDir,nCells,dropAheadM,ceiling"

    fun situationLine(r: hearspace.core.situation.SituationRecord): String = with(r) {
        fun f(x: Float) = if (x.isNaN()) "" else String.format(java.util.Locale.ROOT, "%.3f", x)
        "$tNs,$env,$envCandidate,$nLines,${f(followAngleDeg)},${f(lateralM)},${f(blockYawDeg)},${f(endAheadM)},${f(stairsAheadM)},$stairsDir,$nCells,${f(dropAheadM)},${if (ceiling) 1 else 0}"
    }

    /**
     * 앱 노면 모델(M65, 앱 전용). 노면 영상마다: imageTNs = 카메라 영상 촬영 시각, offerNs·doneNs = 모델 스레드가 받은·끝낸 앱 시계
     * (doneNs − imageTNs = 촬영부터 쓸 수 있을 때까지), rgbNs·preNs·inferNs·postNs = NV21 → RGB, 앞처리, 추론, 뒤처리 시간.
     */
    const val SURFACE_TIMING_FILE = "surface_timing.csv"

    val SURFACE_TIMING_HEADER = listOf("imageTNs", "offerNs", "doneNs", "rgbNs", "preNs", "inferNs", "postNs")

    fun surfaceTimingLine(imageTNs: Long, offerNs: Long, doneNs: Long, rgbNs: Long, preNs: Long, inferNs: Long, postNs: Long): String =
        row(imageTNs, offerNs, doneNs, rgbNs, preNs, inferNs, postNs)

    /**
     * 앱 말 내보내기(M65, 앱 전용). 줄마다 하나의 결과: requestNs = 말 내보내기가 받은 앱 시계, kind = SITUATION·STATUS_SAFETY·STATUS_ACTION,
     * key = 상황 열쇠·상태 이름·측정 문구(따옴표로 감쌈), rank = 등급, result = REJECTED(등급 표가 받지 않음)·STARTED·AUDIO(첫 소리 조각)·COMPLETED·INTERRUPTED·ERROR,
     * startDelayMs = 받은 때부터 onStart(STARTED 줄) 또는 첫 소리 조각(AUDIO 줄)까지, ageAtStartMs = 소리 시작 때 근거 나이(상태 말은 빈 칸), detail = 엔진이 준 이유.
     */
    const val TTS_FILE = "tts.csv"

    val TTS_HEADER = listOf("requestNs", "kind", "key", "rank", "result", "startDelayMs", "ageAtStartMs", "detail")

    fun ttsLine(requestNs: Long, kind: String, key: String, rank: Int, result: String, startDelayMs: Float?, ageAtStartMs: Float?, detail: String?): String =
        row(requestNs, kind, "\"${key.replace("\"", "")}\"", rank, result, startDelayMs?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
            ageAtStartMs?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }, detail?.replace(",", ";"))

    /** 평면 추출 결과(M13.1c, `frontend.planes`가 RANSAC일 때만). 벽은 `x0:z0:x1:z1:점수`를 `;`로 이은 칸. 바닥이 없으면 floorY가 빈 칸. */
    const val PLANES_FILE = "planes.csv"

    val PLANES_HEADER = listOf("tCaptureNs", "floorY", "floorLiftPerM", "floorInliers", "floorSpreadM", "nWalls", "walls")

    fun planesLine(tCaptureNs: Long, p: hearspace.core.frontend.PlaneResult): String {
        val f = p.floor
        val walls = p.walls.joinToString(";") { "%.3f:%.3f:%.3f:%.3f:%d".format(java.util.Locale.ROOT, it.x0, it.z0, it.x1, it.z1, it.nInliers) }
        return listOf(
            tCaptureNs, f?.heightM?.let { "%.4f".format(java.util.Locale.ROOT, it) } ?: "", f?.liftPerM?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "", f?.nInliers ?: 0,
            f?.spreadM?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "", p.walls.size, walls,
        ).joinToString(",")
    }

    val SLOW_PATH_HEADER = listOf("tCaptureNs", "tStartNs", "tDoneNs", "nPoints", "nVoxels", "nObstacles", "floorY", "mapHealth")

    val GUIDANCE_HEADER = listOf(
        "tBlockNs", "poseTNs", "snapshotTNs", "state", "obstacleId", "azimuthDeg", "distanceM", "band", "sound", "infoAgeMs", "headingDeg",
    )

    val OBSTACLES_HEADER = listOf("tCaptureNs", "id", "heightClass", "inCorridor") +
        RepStrategy.entries.flatMap { s -> listOf("x", "y", "z").map { "rep${s.name}_$it" } } +
        listOf("aabbMinX", "aabbMinY", "aabbMinZ", "aabbMaxX", "aabbMaxY", "aabbMaxZ")

    /** 느린 경로 한 번 → `slow_path.csv` 한 줄. */
    fun slowPathLine(u: MapUpdate, tStartNs: Long, tDoneNs: Long, snapshot: ObstacleSnapshot): String =
        row(u.tCaptureNs, tStartNs, tDoneNs, u.nPoints, u.nVoxels, snapshot.obstacles.size, snapshot.floorY, snapshot.mapHealth)

    /**
     * 오디오 블록 하나 → `guidance.csv` 줄(음원마다 한 줄). 음원이 없으면 음원 칸이 빈 한 줄:
     * 빈 칸은 "이 블록에 낸 음원 없음"일 뿐 "장애물 없음"이 아니다(§2.2-5, 상태 열과 함께 읽는다).
     */
    fun guidanceLines(g: GuidanceOutput, poseTNs: Long, snapshotTNs: Long?): List<String> {
        val head = listOf<Any?>(g.tBlockNs, poseTNs, snapshotTNs, g.state)
        val tail = g.headingDeg.takeUnless { it.isNaN() }
        if (g.commands.isEmpty()) return listOf(row(*(head + List(6) { null } + tail).toTypedArray()))
        return g.commands.map { c ->
            row(*(head + listOf(c.obstacleId, c.azimuthDeg, c.distanceM, c.band, c.sound, c.infoAgeMs, tail)).toTypedArray())
        }
    }

    /** 스냅샷 하나 → `obstacles.csv` 줄(물체마다 한 줄). */
    fun obstacleLines(s: ObstacleSnapshot): List<String> = s.obstacles.map { o ->
        val reps = RepStrategy.entries.flatMap { st -> o.repCandidatesW[st]?.let { listOf(it.x, it.y, it.z) } ?: List(3) { null } }
        row(
            *(listOf<Any?>(s.tCaptureNs, o.id, o.heightClass, o.inCorridor) + reps +
                listOf(o.aabbMinW.x, o.aabbMinW.y, o.aabbMinW.z, o.aabbMaxW.x, o.aabbMaxW.y, o.aabbMaxW.z)).toTypedArray(),
        )
    }

    /** 느린 경로 한 번의 단계별 처리 시간 → `stage_timing.csv` 한 줄. */
    fun stageTimingLine(tCaptureNs: Long, s: StageTimes): String = row(tCaptureNs, s.mapNs, s.clusterNs, s.trackNs)

    /** 느린 경로 한 번의 군집 통계 → `cluster_debug.csv` 줄. */
    fun clusterDebugLines(items: List<ClusterDebug>): List<String> = items.map(ClusterDebugCsv::row)

    fun header(cols: List<String>): String = cols.joinToString(",")

    private fun row(vararg v: Any?): String = v.joinToString(",") { it?.toString() ?: "" }
}
