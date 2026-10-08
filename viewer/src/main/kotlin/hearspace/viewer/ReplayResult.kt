package hearspace.viewer

import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.frontend.PlaneResult
import hearspace.core.frontend.Segmenter
import hearspace.core.geometry.Vec3
import hearspace.core.pipeline.StageTimes
import hearspace.core.replay.OfflineReplay
import hearspace.core.replay.ReplayListener
import hearspace.core.replay.SlowStep
import hearspace.core.session.FrameRow
import hearspace.core.session.SessionReader
import hearspace.core.truth.Alignment
import hearspace.core.truth.GroundTruth
import hearspace.core.types.Band
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.File

/** 저장소 안 파일 위치. 모두 git에 있는 파일이라 기기·Android SDK 없이 동작한다. */
object Repo {
    val root: File = File(System.getProperty("hearspace.repoRoot") ?: ".").absoluteFile
    val defaultConfig: File get() = File(root, "app/src/main/assets/config/default.json")
    val hrtf: File get() = File(root, "app/src/main/assets/hrtf/sadie2_d1_48k.hrir")
    val sessions: File get() = File(root, "testdata/sessions")
    val feedback: File get() = File(root, "data/feedback")
    val variants: File get() = File(root, "data/viewer/variants")
}

/** 오디오 블록 하나: 그 블록이 쓴 자세·스냅샷 시각과 빠른 경로 출력. */
class BlockRec(val tNs: Long, val pose: PoseFrame, val snapshotTNs: Long?, val g: GuidanceOutput)

/** 느린 경로 결과 하나. [voxelsW]는 처리 직후 점유 복셀 중심(x, y, z 반복). */
class SlowRec(
    val doneNs: Long,
    val snapshot: ObstacleSnapshot,
    val voxelsW: FloatArray,
    val applied: Boolean,
    val stageNs: StageTimes,
    /** `frontend.planes`가 RANSAC일 때 이 깊이의 바닥·벽 평면(M13.1c). */
    val planes: PlaneResult? = null,
    /** `frontend.segment`가 REGION일 때 이 깊이의 영역 분할(C3a). */
    val segments: Segmenter.Result? = null,
)

/** [times](오름차순)에서 [t] 이하인 마지막 위치. 없으면 −1. 화면은 이것으로만 찾아 미래 값을 쓰지 않는다. */
fun lastAtOrBefore(times: LongArray, t: Long): Int {
    var lo = 0
    var hi = times.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (times[mid] <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/**
 * 재실행 한 번의 결과(IMPROVE_SPEC §10). 시각은 재생 가상 시계(앱 수신 시각, ns).
 * 정답·정렬은 화면 표시용이며 계산 규칙은 core(`GroundTruth`, `Alignment`)를 그대로 쓴다.
 */
class ReplayResult(
    val session: File,
    val reader: SessionReader,
    val config: Config,
    val overridesJson: String,
    val blocks: List<BlockRec>,
    val slow: List<SlowRec>,
    /** 스테레오 PCM(L R 교차), [sampleRate]. 블록 i는 표본 [i × blockSize, (i + 1) × blockSize). */
    val audio: FloatArray,
    val elapsedMs: Long,
) {
    val sampleRate = config.audio.sampleRate
    val blockSize = config.audio.blockSize
    val t0Ns: Long = blocks.first().tNs
    val durationS: Double = blocks.size.toDouble() * blockSize / sampleRate

    private val blockT = LongArray(blocks.size) { blocks[it].tNs }
    private val appliedSlow = slow.filter { it.applied }
    private val slowT = LongArray(appliedSlow.size) { appliedSlow[it].doneNs }
    private val rgbRows = reader.rows.filter { it.rgbFile != null }
    private val rgbT = LongArray(rgbRows.size) { rgbRows[it].sysElapsedNs }
    private val depthRows = reader.rows.filter { it.depthFile != null }
    private val depthT = LongArray(depthRows.size) { depthRows[it].sysElapsedNs }

    /** 세션 ID(폴더 이름). */
    val sessionId: String = session.name

    val truth: GroundTruth? = GroundTruth.read(session, config.head.offsetFromCameraM)

    /** 보행선 좌표(정답 좌표). 바닥을 한 번도 못 잡았으면 null. */
    val alignment: Alignment? = Alignment.medianFloorY(slow.map { it.snapshot.floorY })?.let {
        runCatching { Alignment.fit(reader.rows, config.align, config.head.offsetFromCameraM, it) }.getOrNull()
    }

    /** 장면 ID: 정답 파일의 `scene`, 없으면 폴더 이름 끝(M10 녹화는 폴더 이름이 틀려 정답 파일이 우선). */
    val scene: String = truth?.scene ?: session.name.substringAfterLast('_')

    /** 시각 [tNs] 이하의 마지막 블록 위치(없으면 −1). */
    fun blockIndexAt(tNs: Long) = lastAtOrBefore(blockT, tNs)

    /** 시각 [tNs]까지 빠른 경로에 반영된 마지막 느린 경로 결과. */
    fun slowAt(tNs: Long): SlowRec? = lastAtOrBefore(slowT, tNs).let { if (it < 0) null else appliedSlow[it] }

    /** 시각 [tNs]까지 도착한 마지막 RGB 프레임(저장 간격 `record.rgbEveryN`). */
    fun rgbRowAt(tNs: Long): FrameRow? = lastAtOrBefore(rgbT, tNs).let { if (it < 0) null else rgbRows[it] }

    /** 시각 [tNs]까지 도착한 마지막 깊이 프레임. */
    fun depthRowAt(tNs: Long): FrameRow? = lastAtOrBefore(depthT, tNs).let { if (it < 0) null else depthRows[it] }

    fun timeOfFrame(audioFrame: Long): Long = t0Ns + audioFrame * 1_000_000_000L / sampleRate
    fun frameOfTime(tNs: Long): Long = ((tNs - t0Ns).coerceAtLeast(0) * sampleRate / 1_000_000_000L)
    fun secondsOf(tNs: Long): Double = (tNs - t0Ns) / 1e9

    /**
     * 블록마다 머리 위치(정답 좌표): 카메라 + 오프셋을 **그 시각 진행 방향 기준**으로 더한다(core `HeadPose.fromCamera`와 같음).
     * 되돌아오는 구간(E03·E04)에서 오프셋이 보행선 +z 쪽으로 가면 머리가 0.78 m 어긋난다. 방향이 없으면(NaN) 보행선 축 기준.
     */
    fun headTruthAt(i: Int): Vec3? {
        val al = alignment ?: return null
        val b = blocks[i]
        val o = config.head.offsetFromCameraM
        val cam = b.pose.worldFromCam.translation()
        if (b.g.headingDeg.isNaN()) {
            val c = al.toTruth(cam)
            return Vec3(c.x + o.x, c.y, c.z + o.z)
        }
        val hd = Math.toRadians(b.g.headingDeg.toDouble()) // headingDeg = atan2(x, −z)
        val f = Vec3(kotlin.math.sin(hd).toFloat(), 0f, -kotlin.math.cos(hd).toFloat())
        val right = Vec3(-f.z, 0f, f.x)
        return al.toTruth(cam + right * o.x + f * o.z).let { Vec3(it.x, it.y, it.z) }
    }

    // 타임라인용 시계열: 블록마다 첫 음원의 거리·방위·구간과, 정답 통로 안 가장 가까운 정답 물체의 거리·방위
    val state: Array<GuidanceState> = Array(blocks.size) { blocks[it].g.state }
    val estDistM = FloatArray(blocks.size) { blocks[it].g.commands.firstOrNull()?.distanceM ?: Float.NaN }
    val estAzDeg = FloatArray(blocks.size) { blocks[it].g.commands.firstOrNull()?.azimuthDeg ?: Float.NaN }
    val estBand: Array<Band?> = Array(blocks.size) { blocks[it].g.commands.firstOrNull()?.band }
    val truthDistM = FloatArray(blocks.size) { Float.NaN }
    val truthAzDeg = FloatArray(blocks.size) { Float.NaN }

    init {
        val t = truth
        if (t != null && alignment != null) {
            for (i in blocks.indices) {
                val h = headTruthAt(i) ?: continue
                val near = t.obstacles.mapNotNull { it.nearestInCorridor(h.x, h.z, config.corridor) }.minByOrNull { it.alongM } ?: continue
                truthDistM[i] = near.distanceM
                truthAzDeg[i] = near.azimuthDeg
            }
        }
    }
}

/** 세션을 오프라인 재생해 [ReplayResult]를 만든다. 블록마다 앱과 같은 바이노럴 렌더러로 소리를 만든다. */
object ReplayRunner {
    /** 느린 경로 처리 시간(ms). `:core:replay` 기본값(OfflineReplayMain)과 같다. */
    const val SLOW_PATH_MS = 10f

    fun run(session: File, overridesJson: String, hrtf: Hrtf): ReplayResult {
        val start = System.nanoTime()
        val config = ConfigLoader.load(Repo.defaultConfig.readText(), overridesJson)
        val reader = SessionReader(session)
        val renderer = BinauralRenderer(config, hrtf)
        val n = config.audio.blockSize
        val blocks = ArrayList<BlockRec>()
        val slow = ArrayList<SlowRec>()
        val chunks = ArrayList<FloatArray>()
        val listener = object : ReplayListener {
            override val captureVoxels = true

            override fun onSlowStep(step: SlowStep) {
                val v = step.occupiedVoxels!!
                val xyz = FloatArray(v.size * 3)
                v.forEachIndexed { i, vox -> xyz[3 * i] = vox.centerW.x; xyz[3 * i + 1] = vox.centerW.y; xyz[3 * i + 2] = vox.centerW.z }
                slow += SlowRec(step.doneNs, step.snapshot, xyz, step.applied, step.stageNs, step.mapUpdate.planes, step.mapUpdate.segments)
            }

            override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) {
                blocks += BlockRec(tNs, pose, snapshot?.tCaptureNs, g)
                val out = FloatArray(2 * n)
                renderer.render(g, out)
                chunks += out
            }
        }
        OfflineReplay(config, OfflineReplay.fixed(SLOW_PATH_MS)).run(session, listener)
        require(blocks.isNotEmpty()) { "no audio blocks: session has no tracked pose" }
        val audio = FloatArray(chunks.size * 2 * n)
        chunks.forEachIndexed { i, c -> c.copyInto(audio, i * 2 * n) }
        return ReplayResult(session, reader, config, overridesJson, blocks, slow, audio, (System.nanoTime() - start) / 1_000_000)
    }

    fun loadHrtf(): Hrtf = Hrtf.parse(Repo.hrtf.readBytes())
}
