package hearspace.core.replay

import hearspace.core.session.Png16
import hearspace.core.session.RunLog
import hearspace.core.session.SessionReader
import hearspace.core.situation.SituationInput
import hearspace.core.situation.SituationRecord
import hearspace.core.situation.Situations
import hearspace.core.situation.Utterance
import hearspace.core.types.Config
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.LabelImage
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.File
import java.io.Writer

/**
 * 재생의 노면 영상(M50, PC 재생 전용 읽기. 앱은 M61에서 모델 출력을 직접 넘긴다). 세션 `surface/index.csv`(frameTNs,imageTNs,width,height,file)와
 * 8비트 PNG(화소 = [hearspace.core.situation.SurfaceClass] 번호, 255 = 계산 안 함, CPU 카메라 영상과 같은 센서 방향·비율).
 * 촬영 시각 + 모델 처리 시간 ≤ 깊이 장 시각인 가장 최근 영상만 준다(과거만). 자세는 그 영상 촬영 프레임의 자세다.
 */
class SurfaceFrames(private val dir: File, private val reader: SessionReader, private val delayNs: Long) {
    private class Entry(val imageTNs: Long, val width: Int, val height: Int, val file: String)

    private val entries: List<Entry> = File(dir, INDEX).takeIf { it.isFile }?.readLines()?.drop(1)?.filter { it.isNotBlank() }?.map { line ->
        val c = line.split(',')
        Entry(c[1].toLong(), c[2].toInt(), c[3].toInt(), c[4])
    }?.sortedBy { it.imageTNs }.orEmpty()
    private val poses: List<PoseFrame> by lazy { reader.poseFrames().sortedBy { it.tCaptureNs } }
    private var cacheNs = Long.MIN_VALUE
    private var cache: LabelImage? = null

    val hasSurface: Boolean get() = entries.isNotEmpty()

    /** 깊이 장 시각 [tNs]에 쓸 수 있는 가장 최근 노면 영상. */
    fun at(tNs: Long): LabelImage? {
        val e = entries.lastOrNull { it.imageTNs + delayNs <= tNs } ?: return null
        if (e.imageTNs == cacheNs) return cache
        cacheNs = e.imageTNs
        val f = File(dir, e.file)
        cache = if (!f.isFile) null else {
            val img = Png16.decode(f.readBytes(), e.width.toLong() * e.height)
            require(img.bitDepth == 8 && img.width == e.width && img.height == e.height) { "${e.file}: surface must be 8-bit ${e.width}x${e.height}" }
            val pose = poses.lastOrNull { it.tCaptureNs <= e.imageTNs } ?: return null
            LabelImage(e.imageTNs, img.gray8!!, SessionReader.scaleTextureK(reader.meta.camera.imageIntrinsics, e.width, e.height), pose.worldFromCam)
        }
        return cache
    }

    companion object {
        const val INDEX = "surface/index.csv"
    }
}

/**
 * 재생에서 상황 판단을 돌리는 부분(재생 기록 [SituationListener]와 뷰어가 같이 쓴다). 오디오 블록마다 마지막 안내 출력을 기억하고,
 * 반영된 느린 경로 결과마다 그 시각까지 쓸 수 있는 노면 영상으로 [Situations]를 한 차례 돌린다.
 */
class SituationRunner(config: Config, session: File) {
    private val situations = Situations(config.situation, config.corridor.widthM, config.corridor.heightM, config.vision.footHeightWindowS, (config.policy.maxInfoAgeMs * 1e6).toLong())
    private val surface = SurfaceFrames(session, SessionReader(session), config.situation.surfaceDelayMs * 1_000_000L)
    private var lastOutput: GuidanceOutput? = null

    fun onBlock(g: GuidanceOutput) {
        lastOutput = g
    }

    /** 반영되지 않은 결과(처리 중 RESET)와 판단할 차례가 아닌 결과([Situations.due])는 null. */
    fun onSlowStep(step: SlowStep): Pair<List<Utterance>, SituationRecord>? {
        if (!step.applied) return null
        val g = lastOutput
        val surf = surface.at(step.depth.tCaptureNs)
        if (!situations.due(step.depth.tCaptureNs, surf?.tCaptureNs)) return null
        return situations.step(SituationInput(step.doneNs, step.depth, step.snapshot, g?.commands.orEmpty(), Situations.headingOf(g), surf))
    }
}

/**
 * 상황 가지(M50 설계 그림 3 (b)). 재생 결과를 [inner]에 그대로 넘기고, 느린 경로 결과마다 [SituationRunner]를 돌려 `speech.csv`·`situation.csv`를 쓴다.
 * 소리 경로에는 아무것도 쓰지 않는다(읽기만): 켬·끔의 실행 로그는 바이트 같아야 한다.
 */
class SituationListener(
    private val inner: ReplayListener,
    config: Config,
    session: File,
    outDir: File,
) : ReplayListener by inner {
    private val runner = SituationRunner(config, session)
    private val speechOut: Writer = File(outDir, SPEECH_FILE).bufferedWriter().apply { write(RunLog.SPEECH_HEADER_LINE + "\n") }
    private val stateOut: Writer = File(outDir, STATE_FILE).bufferedWriter().apply { write(RunLog.SITUATION_HEADER_LINE + "\n") }

    override fun onSlowStep(step: SlowStep) {
        inner.onSlowStep(step)
        val (said, rec) = runner.onSlowStep(step) ?: return
        for (u in said) speechOut.write(RunLog.speechLine(u) + "\n")
        stateOut.write(RunLog.situationLine(rec) + "\n")
    }

    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) {
        inner.onBlock(tNs, pose, snapshot, g)
        runner.onBlock(g)
    }

    override fun onEnd() {
        inner.onEnd()
        speechOut.close()
        stateOut.close()
    }

    companion object {
        const val SPEECH_FILE = RunLog.SPEECH_FILE
        const val STATE_FILE = RunLog.SITUATION_FILE
    }
}
