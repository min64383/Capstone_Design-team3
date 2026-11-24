package hearspace.core.replay

import hearspace.core.session.Png16
import hearspace.core.session.RunLog
import hearspace.core.session.SessionReader
import hearspace.core.situation.ColorImage
import hearspace.core.situation.SignalOutput
import hearspace.core.situation.SignalSpeech
import hearspace.core.situation.Signals
import hearspace.core.situation.SituationInput
import hearspace.core.situation.SituationRecord
import hearspace.core.situation.Situations
import hearspace.core.situation.Utterance
import hearspace.core.types.Config
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.LabelImage
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import hearspace.core.types.SignalConfig
import hearspace.core.types.TrackingState
import java.io.File
import java.io.Writer
import javax.imageio.ImageIO

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
 * 신호 판단(M71)의 PC 재생 입력(**PC 재생 전용**: JDK ImageIO). 녹화 RGB JPEG(`rgb/`, 10 Hz)를 깊이 장 시각까지 차례로 [Signals]에 넣는다(과거만).
 * [fullRes]가 false면 앱 CPU 영상 폭([APP_WIDTH])의 정수배인 영상을 화소 평균으로 줄인다(1920×1080 → 640×360, 초점거리가 정확히 1/3이라
 * 앱 640×480의 가운데와 같은 화소를 본다, M71 설계 3.5절). 자세는 그 RGB를 담은 프레임의 자세다.
 * [log]에는 장·track마다 `signals.csv`, [blobLog]에는 켜진 등 덩어리마다 `signal_blobs.csv`를 쓴다.
 */
class SignalReplay(cfg: SignalConfig, private val dir: File, reader: SessionReader, private val fullRes: Boolean, private val log: Writer?, private val blobLog: Writer?) {
    private class Entry(val tNs: Long, val file: String, val pose: PoseFrame)

    private val signals = Signals(cfg)
    private val entries: List<Entry> = reader.rows.zip(reader.poseFrames()).filter { it.first.rgbFile != null }
        .map { (r, p) -> Entry(r.tNs, r.rgbFile!!, p) }.sortedBy { it.tNs }
    private val baseK = reader.meta.camera.imageIntrinsics
    private var next = 0
    private var latest: SignalOutput? = null

    init {
        log?.write(LOG_HEADER + "\n")
        blobLog?.write(BLOB_HEADER + "\n")
    }

    /** 촬영 시각 ≤ [tNs]인 RGB를 모두 처리하고 최신 말을 준다. */
    fun advanceTo(tNs: Long): List<SignalSpeech> {
        while (next < entries.size && entries[next].tNs <= tNs) {
            val e = entries[next++]
            val img = load(e) ?: continue
            val o = signals.process(img)
            latest = o
            write(o)
        }
        return latest?.speech.orEmpty()
    }

    private fun load(e: Entry): ColorImage? {
        val f = File(dir, e.file)
        if (!f.isFile) return null
        val src = ImageIO.read(f) ?: return null
        val s = if (fullRes) 1 else maxOf(1, src.width / APP_WIDTH)
        val w = src.width / s
        val h = src.height / s
        val px = src.getRGB(0, 0, src.width, src.height, null, 0, src.width)
        val rgb = ByteArray(w * h * 3)
        val n = s * s
        for (i in 0 until w * h) {
            val x0 = (i % w) * s
            val y0 = (i / w) * s
            var r = 0; var g = 0; var b = 0
            for (y in y0 until y0 + s) for (x in x0 until x0 + s) {
                val c = px[y * src.width + x]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            rgb[i * 3] = ((r + n / 2) / n).toByte(); rgb[i * 3 + 1] = ((g + n / 2) / n).toByte(); rgb[i * 3 + 2] = ((b + n / 2) / n).toByte()
        }
        val k0 = SessionReader.scaleTextureK(baseK, src.width, src.height)
        val k = k0.copy(fx = k0.fx / s, fy = k0.fy / s, cx = k0.cx / s, cy = k0.cy / s, width = w, height = h)
        return ColorImage(e.tNs, k, e.pose.worldFromCam, e.pose.tracking == TrackingState.TRACKING, rgb = rgb)
    }

    private fun write(o: SignalOutput) {
        log?.let { w ->
            if (o.tracks.isEmpty()) w.write("${o.tNs},${if (o.still) 1 else 0},-1,,,,,\n")
            for (t in o.tracks) {
                w.write("${o.tNs},${if (o.still) 1 else 0},${t.id},${f(t.azDeg)},${f(t.elDeg)},${t.obs},${if (t.redConfirmed) 1 else 0},${t.transitionNs ?: ""}\n")
            }
        }
        blobLog?.let { w ->
            for (b in o.blobs) {
                w.write("${b.tNs},${if (b.red) "R" else "G"},${b.nPx},${f(b.azDeg)},${f(b.elDeg)},${f(b.heightPx)},${f(b.widthPx)},${f(b.darkLuma)},${if (b.pass) 1 else 0},${b.track}\n")
            }
        }
    }

    private fun f(v: Float) = if (v.isNaN()) "" else "%.3f".format(java.util.Locale.ROOT, v)

    companion object {
        const val APP_WIDTH = 640
        const val LOG_FILE = "signals.csv"
        const val BLOB_FILE = "signal_blobs.csv"
        const val LOG_HEADER = "tNs,still,track,azDeg,elDeg,obs,redConfirmed,transitionNs"
        const val BLOB_HEADER = "tNs,color,nPx,azDeg,elDeg,heightPx,widthPx,darkLuma,pass,track"
    }
}

/**
 * 재생에서 상황 판단을 돌리는 부분(재생 기록 [SituationListener]와 뷰어가 같이 쓴다). 오디오 블록마다 마지막 안내 출력을 기억하고,
 * 반영된 느린 경로 결과마다 그 시각까지 쓸 수 있는 노면 영상으로 [Situations]를 한 차례 돌린다.
 */
class SituationRunner(config: Config, session: File, signalFullRes: Boolean = false, signalLog: Writer? = null, signalBlobLog: Writer? = null) {
    private val situations = Situations(config.situation, config.corridor.widthM, config.corridor.heightM, config.vision.footHeightWindowS, (config.policy.maxInfoAgeMs * 1e6).toLong())
    private val reader = SessionReader(session)
    private val surface = SurfaceFrames(session, reader, config.situation.surfaceDelayMs * 1_000_000L)
    /** 신호 판단(M71). 끄면 null이고 상황 판단 입력은 M65와 같다(③). */
    private val signals = if (config.signal.enabled) SignalReplay(config.signal, session, reader, signalFullRes, signalLog, signalBlobLog) else null
    private var lastOutput: GuidanceOutput? = null

    fun onBlock(g: GuidanceOutput) {
        lastOutput = g
    }

    /** 반영되지 않은 결과(처리 중 RESET)와 판단할 차례가 아닌 결과([Situations.due])는 null. */
    fun onSlowStep(step: SlowStep): Pair<List<Utterance>, SituationRecord>? {
        if (!step.applied) return null
        val g = lastOutput
        val sig = signals?.advanceTo(step.depth.tCaptureNs).orEmpty()
        val surf = surface.at(step.depth.tCaptureNs)
        if (!situations.due(step.depth.tCaptureNs, surf?.tCaptureNs)) return null
        return situations.step(SituationInput(step.doneNs, step.depth, step.snapshot, g?.commands.orEmpty(), Situations.headingOf(g), surf, signals = sig))
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
    signalFullRes: Boolean = false,
) : ReplayListener by inner {
    private val signalOut: Writer? = if (config.signal.enabled) File(outDir, SignalReplay.LOG_FILE).bufferedWriter() else null
    private val blobOut: Writer? = if (config.signal.enabled) File(outDir, SignalReplay.BLOB_FILE).bufferedWriter() else null
    private val runner = SituationRunner(config, session, signalFullRes, signalOut, blobOut)
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
        signalOut?.close()
        blobOut?.close()
    }

    companion object {
        const val SPEECH_FILE = RunLog.SPEECH_FILE
        const val STATE_FILE = RunLog.SITUATION_FILE
    }
}
