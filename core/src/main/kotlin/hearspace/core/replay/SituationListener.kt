package hearspace.core.replay

import hearspace.core.geometry.Vec3
import hearspace.core.session.Png16
import hearspace.core.session.SessionReader
import hearspace.core.situation.SituationInput
import hearspace.core.situation.Situations
import hearspace.core.types.Config
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.LabelImage
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.File
import java.io.Writer
import kotlin.math.cos
import kotlin.math.sin

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
 * 상황 가지(M50 설계 그림 3 (b)). 재생 결과를 [inner]에 그대로 넘기고, 느린 경로 결과마다 [Situations]를 돌려 `speech.csv`·`situation.csv`를 쓴다.
 * 소리 경로에는 아무것도 쓰지 않는다(읽기만): 켬·끔의 실행 로그는 바이트 같아야 한다.
 */
class SituationListener(
    private val inner: ReplayListener,
    config: Config,
    session: File,
    outDir: File,
) : ReplayListener by inner {
    private val situations = Situations(config.situation, config.corridor.widthM, config.vision.footHeightWindowS, (config.policy.maxInfoAgeMs * 1e6).toLong())
    private val surface = SurfaceFrames(session, SessionReader(session), config.situation.surfaceDelayMs * 1_000_000L)
    private val speechOut: Writer = File(outDir, SPEECH_FILE).bufferedWriter().apply { write("tNs,priority,key,text,ageMs,interrupted\n") }
    private val stateOut: Writer = File(outDir, STATE_FILE).bufferedWriter().apply {
        write("tNs,env,envCandidate,nLines,followAngleDeg,lateralM,blockYawDeg,endAheadM,stairsAheadM,stairsDir,nCells\n")
    }
    private var lastOutput: GuidanceOutput? = null

    override fun onSlowStep(step: SlowStep) {
        inner.onSlowStep(step)
        if (!step.applied) return
        val g = lastOutput
        val heading = g?.headingDeg?.takeIf { it.isFinite() }?.let { Math.toRadians(it.toDouble()) }?.let { Vec3(sin(it).toFloat(), 0f, -cos(it).toFloat()) }
        val (said, rec) = situations.step(
            SituationInput(step.doneNs, step.depth, step.snapshot, g?.commands.orEmpty(), heading, surface.at(step.depth.tCaptureNs)),
        )
        for (u in said) {
            val text = "\"" + u.text.replace("\"", "") + "\""
            speechOut.write("${u.tNs},${u.priority},${u.key},$text,${String.format(java.util.Locale.ROOT, "%.1f", u.ageMs)},${if (u.interrupted) 1 else 0}\n")
        }
        with(rec) {
            stateOut.write("$tNs,$env,$envCandidate,$nLines,${f(followAngleDeg)},${f(lateralM)},${f(blockYawDeg)},${f(endAheadM)},${f(stairsAheadM)},$stairsDir,$nCells\n")
        }
    }

    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) {
        inner.onBlock(tNs, pose, snapshot, g)
        lastOutput = g
    }

    override fun onEnd() {
        inner.onEnd()
        speechOut.close()
        stateOut.close()
    }

    private fun f(x: Float) = if (x.isNaN()) "" else String.format(java.util.Locale.ROOT, "%.3f", x)

    companion object {
        const val SPEECH_FILE = "speech.csv"
        const val STATE_FILE = "situation.csv"
    }
}
