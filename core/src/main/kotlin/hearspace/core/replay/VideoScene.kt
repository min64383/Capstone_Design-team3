package hearspace.core.replay

import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.guidance.Corridor
import hearspace.core.pipeline.FastPath
import hearspace.core.screen.UserScreen
import hearspace.core.situation.SignalLight
import hearspace.core.situation.SignalSpeech
import hearspace.core.situation.Signals
import hearspace.core.situation.SituationInput
import hearspace.core.situation.Situations
import hearspace.core.types.Band
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.HeightClass
import hearspace.core.types.Intrinsics
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.LabelImage
import hearspace.core.types.MapHealth
import hearspace.core.types.MiniJson
import hearspace.core.types.Obstacle
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import hearspace.core.types.SemanticLabel
import hearspace.core.types.TrackingState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 영상 속 물체 하나의 열쇠 장: 원본 영상 시각(s), 화면 상자(화소, 왼·위·오른·아래), 거리를 잰 단서 [cue]:
 * 'H' 상자 세로 = 실제 높이, 'W' 상자 가로 = 실제 폭(정면을 볼 때), 'G' 상자 아래 = 가장 가까운 바닥 접점(카메라 높이·지평선으로).
 */
data class BoxKey(val tS: Double, val u0: Float, val v0: Float, val u1: Float, val v1: Float, val cue: Char = 'H')

/** 사람이 적은 물체(M73 설계 3.1절). [widthM]·[heightM]은 실제 폭·높이(거리 단서), [depthM]은 상자 앞면부터의 깊이. */
data class SceneObject(val id: Int, val label: SemanticLabel, val widthM: Float, val heightM: Float, val depthM: Float, val keys: List<BoxKey>)

/** 신호 상태가 바뀌는 시각(원본 영상 s)과 그 뒤의 상태: RED·GREEN 켜짐, OFF 꺼짐. */
data class SignalKey(val tS: Double, val state: String)

/**
 * 장면 파일 하나(`tools/demo/scenes/<id>.json`, M73 설계 3.1절). 원본 영상의 [startS]~[endS]를 쓰고, 결과 시각은 [startS]부터 잰다.
 * 카메라는 긴 변 화각 [longSideFovDeg]의 핀홀(가운데 = 영상 가운데)이고, 사용자는 원점에 서서 카메라 정면(−Z)을 본다.
 */
data class VideoScene(
    val id: String,
    val title: String,
    val startS: Double,
    val endS: Double,
    val widthPx: Int,
    val heightPx: Int,
    val longSideFovDeg: Float,
    val cameraHeightM: Float,
    val objects: List<SceneObject>,
    val signal: List<SignalKey>,
    /** 바닥 접점 단서('G')용: 영상을 찍은 카메라의 바닥 위 높이와 지평선 화소 행(앞면 폭 단서 장들로 맞춘 값). */
    val groundCameraHeightM: Float = Float.NaN,
    val horizonV: Float = Float.NaN,
) {
    /** 초점 거리(화소). */
    val focalPx: Float get() = (maxOf(widthPx, heightPx) / 2f) / tan(Math.toRadians(longSideFovDeg / 2.0)).toFloat()

    companion object {
        fun parse(text: String): VideoScene {
            val o = MiniJson.parse(text) as JsonObject
            val clip = o.obj("clip")
            val cam = o.obj("camera")
            return VideoScene(
                id = o.str("id"), title = o.str("title"), startS = clip.num("startS"), endS = clip.num("endS"),
                widthPx = cam.num("widthPx").toInt(), heightPx = cam.num("heightPx").toInt(),
                longSideFovDeg = cam.num("longSideFovDeg").toFloat(), cameraHeightM = cam.num("heightM").toFloat(),
                groundCameraHeightM = (cam.fields["groundHeightM"] as? JsonNumber)?.value?.toFloat() ?: Float.NaN,
                horizonV = (cam.fields["horizonV"] as? JsonNumber)?.value?.toFloat() ?: Float.NaN,
                objects = (o.fields["objects"] as? JsonArray)?.items.orEmpty().map { v ->
                    val m = v as JsonObject
                    SceneObject(m.num("id").toInt(), SemanticLabel.valueOf(m.str("label")), m.num("widthM").toFloat(), m.num("heightM").toFloat(), m.num("depthM").toFloat(),
                        (m.fields["keys"] as JsonArray).items.map { k ->
                            val it = (k as JsonArray).items
                            val a = it.take(5).map { v -> (v as JsonNumber).value }
                            val cue = (it.getOrNull(5) as? JsonString)?.value?.single() ?: 'H'
                            require(cue in "HWG") { "cue $cue" }
                            BoxKey(a[0], a[1].toFloat(), a[2].toFloat(), a[3].toFloat(), a[4].toFloat(), cue)
                        }.sortedBy { it.tS })
                },
                signal = (o.fields["signal"] as? JsonArray)?.items.orEmpty().map { k ->
                    val a = (k as JsonArray).items
                    SignalKey((a[0] as JsonNumber).value, (a[1] as JsonString).value.also { require(it in setOf("RED", "GREEN", "OFF")) { "signal state $it" } })
                }.sortedBy { it.tS },
            )
        }

        private fun JsonObject.obj(k: String) = fields[k] as? JsonObject ?: throw IllegalArgumentException("missing $k")
        private fun JsonObject.num(k: String) = (fields[k] as? JsonNumber)?.value ?: throw IllegalArgumentException("missing $k")
        private fun JsonObject.str(k: String) = (fields[k] as? JsonString)?.value ?: throw IllegalArgumentException("missing $k")
    }
}

/** 재현 실행 값(M73 설계 표 4). `tools/demo/scenes/videodemo.json`에서 읽는다. */
data class VideoDemoSettings(
    /** 영상 앞에 서 있던 시간(상태 기계 준비·신호 멈춤 확인). 이 동안의 소리는 버린다. */
    val preRollS: Double,
    val poseHz: Double,
    val snapshotIntervalMs: Long,
    val snapshotLatencyMs: Long,
    val velocityWindowS: Double,
    val signalElevationDeg: Float,
    val labelWidth: Int,
    val labelHeight: Int,
    /** 화면을 다시 그리는 간격(앱 사용자 화면 UserModeActivity `UI_REFRESH_MS` 200 ms). */
    val screenRefreshMs: Long,
) {
    companion object {
        fun parse(text: String): VideoDemoSettings {
            val o = MiniJson.parse(text) as JsonObject
            fun n(k: String) = (o.fields[k] as? JsonNumber)?.value ?: throw IllegalArgumentException("missing $k")
            return VideoDemoSettings(n("preRollS"), n("poseHz"), n("snapshotIntervalMs").toLong(), n("snapshotLatencyMs").toLong(),
                n("velocityWindowS"), n("signalElevationDeg").toFloat(), n("labelWidth").toInt(), n("labelHeight").toInt(), n("screenRefreshMs").toLong())
        }
    }
}

/** 한 물체의 정답 위치(월드). [nearCenterW]는 앞면 가운데(속도를 잼), [aabbMinW]~[aabbMaxW]는 상자. */
class TruthBox(val nearCenterW: Vec3, val aabbMinW: Vec3, val aabbMaxW: Vec3, val distanceM: Float)

/** 말 한 줄: 시작·끝(결과 시각 ns, 영상 첫 장 = 0), 문장, 등급. */
data class DemoSpeech(val startNs: Long, val endNs: Long, val text: String, val priority: Int)

/** 확인용 한 줄(100 ms마다): 물체별 진행 방향 거리·좌우·움직임·통로 안, 첫 음원 구간. */
data class DemoTruth(val tNs: Long, val objectId: Int, val alongM: Float, val lateralM: Float, val speedMps: Float, val inCorridor: Boolean, val band: Band?)

class VideoSceneResult(
    /** 2채널 교차(L R), 결과 시각 0부터. */
    val audio: FloatArray,
    val sampleRate: Int,
    /** 화면 요소가 바뀐 시각(ns)과 그 요소. */
    val screens: List<Pair<Long, UserScreen>>,
    val speech: List<DemoSpeech>,
    val truth: List<DemoTruth>,
)

/**
 * 영상 데모 재현 실행기(M73 설계 3.2절). 깊이 → 물체 단계만 사람이 적은 상자에서 물체를 만드는 것으로 바꾸고, 그 뒤는 앱과 같은 core를 쓴다:
 * 빠른 경로(상태 기계·거리 구간·TTC STOP) → 렌더러, 상황 판단(1g 사람·차 다가옴)·신호 판단(검출 뒤 [Signals.observe]) → 말하기 규칙 → 말 소리.
 * 정답은 시각 t에 t의 것만 묻는다([truthAt]에 미래를 묻지 않음, 속도는 지난 [VideoDemoSettings.velocityWindowS]의 이동).
 * 열쇠 장 사이를 직선으로 잇는 것은 영상 속 세계를 정하는 일이다(합성 장면 생성기가 경로를 정하는 것과 같음).
 */
class VideoSceneRunner(
    private val scene: VideoScene,
    private val config: Config,
    private val settings: VideoDemoSettings,
    private val hrtf: Hrtf,
    /** 문장 → 말 소리(모노, 설정 표본율, ±1). 없으면 null(빠진 문장은 [missing]에 남고 소리 없이 진행). */
    private val clip: (String) -> FloatArray?,
    /** 시험용: 정답을 물을 때마다 (물은 시각, 지금 시각)을 받는다. */
    private val onTruthQuery: ((askedNs: Long, nowNs: Long) -> Unit)? = null,
) {
    val missing = LinkedHashSet<String>()
    private val rate = config.audio.sampleRate
    private val n = config.audio.blockSize
    private val camW = Vec3(0f, scene.cameraHeightM, 0f)
    private val worldFromCam = Mat4.fromAxes(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f), camW)
    private val corridor = Corridor(camW, Vec3(0f, 0f, -1f), 0f, config.corridor)
    private val labelK = Intrinsics(scene.focalPx * settings.labelWidth / scene.widthPx, scene.focalPx * settings.labelWidth / scene.widthPx,
        settings.labelWidth / 2f, settings.labelHeight / 2f, settings.labelWidth, settings.labelHeight)

    /** 결과 시각 [tNs](원본 영상 [VideoScene.startS] = 0)의 정답 상자. 첫 열쇠 전·마지막 열쇠 뒤면 null. */
    fun truthAt(o: SceneObject, tNs: Long, nowNs: Long): TruthBox? {
        onTruthQuery?.invoke(tNs, nowNs)
        val tS = scene.startS + tNs / 1e9
        val ks = o.keys
        if (ks.isEmpty() || tS < ks.first().tS || tS > ks.last().tS) return null
        val j = ks.indexOfLast { it.tS <= tS }
        val a = ks[j]
        val b = ks.getOrElse(j + 1) { a }
        val w = if (b.tS > a.tS) ((tS - a.tS) / (b.tS - a.tS)).toFloat() else 0f
        fun lerp(x: Float, y: Float) = x + (y - x) * w
        val u0 = lerp(a.u0, b.u0); val v0 = lerp(a.v0, b.v0); val u1 = lerp(a.u1, b.u1); val v1 = lerp(a.v1, b.v1)
        val f = scene.focalPx
        val cx = scene.widthPx / 2f
        // 핀홀: 화면 크기 = 초점 × 실제 크기 ÷ 깊이. 두 열쇠의 단서가 다르면 각 열쇠의 거리를 잇는다
        fun depth(k: BoxKey, u0: Float, v0: Float, u1: Float, v1: Float) = when (k.cue) {
            'W' -> f * o.widthM / (u1 - u0)
            'G' -> f * scene.groundCameraHeightM / (v1 - scene.horizonV)
            else -> f * o.heightM / (v1 - v0)
        }
        val d = if (a.cue == b.cue) depth(a, u0, v0, u1, v1) else lerp(depth(a, a.u0, a.v0, a.u1, a.v1), depth(b, b.u0, b.v0, b.u1, b.v1))
        val x0 = (u0 - cx) / f * d
        val x1 = (u1 - cx) / f * d
        return TruthBox(Vec3((x0 + x1) / 2f, o.heightM / 2f, -d), Vec3(x0, 0f, -(d + o.depthM)), Vec3(x1, o.heightM, -d), d)
    }

    fun run(): VideoSceneResult {
        val preNs = (settings.preRollS * 1e9).toLong()
        val endNs = ((scene.endS - scene.startS) * 1e9).toLong()
        val fast = FastPath(config)
        val renderer = BinauralRenderer(config, hrtf)
        val situations = Situations(config.situation, config.corridor.widthM, config.corridor.heightM, config.vision.footHeightWindowS, (config.policy.maxInfoAgeMs * 1e6).toLong())
        val signals = if (scene.signal.isNotEmpty()) Signals(config.signal) else null
        val signalDir = Vec3(0f, sin(Math.toRadians(settings.signalElevationDeg.toDouble())).toFloat(), -cos(Math.toRadians(settings.signalElevationDeg.toDouble())).toFloat())
        val poseStepNs = (1e9 / settings.poseHz).toLong()
        val snapStepNs = settings.snapshotIntervalMs * 1_000_000L
        val latencyNs = settings.snapshotLatencyMs * 1_000_000L
        val signalStepNs = config.signal.intervalMs * 1_000_000L

        val audio = ArrayList<FloatArray>()
        val screens = ArrayList<Pair<Long, UserScreen>>()
        val said = ArrayList<DemoSpeech>()
        val truth = ArrayList<DemoTruth>()
        val out = FloatArray(2 * n)
        var pose: PoseFrame? = null
        var nextPoseNs = -preNs
        var snapshot: ObstacleSnapshot? = null
        var nextCaptureNs = -preNs
        var lastSignalNs = Long.MIN_VALUE
        var signalSpeech: List<SignalSpeech> = emptyList()
        var lastG: GuidanceOutput? = null
        var speechClip: FloatArray? = null
        var speechPos = 0
        var speechPriority = 0
        var lastScreen: UserScreen? = null
        var nextScreenNs = 0L
        var nextTruthNs = 0L
        var k = 0L
        while (true) {
            val now = -preNs + k * n * 1_000_000_000L / rate
            if (now >= endNs) break
            while (nextPoseNs <= now) {
                pose = PoseFrame(nextPoseNs, TrackingState.TRACKING, worldFromCam)
                nextPoseNs += poseStepNs
            }
            // 신호: 앱 신호 스레드처럼 intervalMs가 지난 첫 영상 장(원본 영상의 장 = 상태 열쇠 사이 아무 시각)
            if (signals != null && (lastSignalNs == Long.MIN_VALUE || now - lastSignalNs >= signalStepNs)) {
                lastSignalNs = now
                val st = signalState(now)
                val lights = if (st == "RED" || st == "GREEN") listOf(SignalLight(signalDir, st == "RED")) else emptyList()
                signalSpeech = signals.observe(now, true, camW, lights).speech
            }
            // 스냅샷: 촬영 시각 tc의 정답, tc + 지연에 도착
            while (nextCaptureNs + latencyNs <= now) {
                val tc = nextCaptureNs
                snapshot = ObstacleSnapshot(tc, obstaclesAt(tc, now), 0f, MapHealth.OK)
                nextCaptureNs += snapStepNs
                // 상황 판단: 앱처럼 느린 경로 결과가 반영될 때, 판단할 차례면
                val snap = snapshot
                if (situations.due(tc, null)) {
                    val depth = DepthFrame(tc, ShortArray(labelK.width * labelK.height), null, labelK, worldFromCam, "synthetic", labels = labelsAt(snap))
                    val (us, _) = situations.step(SituationInput(tc + latencyNs, depth, snap, lastG?.commands.orEmpty(), Situations.headingOf(lastG), null,
                        outputPriority = speechPriority, signals = signalSpeech))
                    for (u in us) {
                        val c = clip(u.text)
                        if (c == null) missing += u.text
                        // 새 말이 앞 말을 끊는다(앱 TTS QUEUE_FLUSH, 중계와 같음)
                        said.lastOrNull()?.let { p -> if (p.endNs > u.tNs) said[said.size - 1] = p.copy(endNs = u.tNs) }
                        val dur = c?.let { it.size * 1_000_000_000L / rate } ?: 0L
                        said += DemoSpeech(u.tNs, u.tNs + dur, u.text, u.priority)
                        speechClip = c
                        speechPos = 0
                        speechPriority = if (c == null) 0 else u.priority
                    }
                }
            }
            val g = fast.compute(pose!!, snapshot, now)
            lastG = g
            g.alert?.let { renderer.alert(it) }
            renderer.renderWithSpeech(g, out, speechPriority > 0)
            speechClip?.let { c ->
                var i = 0
                while (i < n && speechPos < c.size) {
                    val v = c[speechPos++] * config.audio.ttsVolume
                    out[2 * i] += v
                    out[2 * i + 1] += v
                    i++
                }
                if (speechPos >= c.size) { speechClip = null; speechPriority = 0 }
            }
            if (now >= 0) {
                audio += out.copyOf()
                // 화면은 앱처럼 screenRefreshMs마다 다시 그린다(블록마다 바뀌는 방위로 시간표가 부풀지 않게)
                if (now >= nextScreenNs) {
                    nextScreenNs += settings.screenRefreshMs * 1_000_000L
                    val c0 = g.commands.firstOrNull()
                    val s = UserScreen.of(true, null, false, g.state, MapHealth.OK, c0?.band, c0?.azimuthDeg)
                    if (s != lastScreen) { lastScreen = s; screens += now to s }
                }
                if (now >= nextTruthNs) {
                    nextTruthNs += 100_000_000L
                    for (o in scene.objects) {
                        val tb = truthAt(o, now, now) ?: continue
                        val rep = repPoint(tb)
                        val v = velocity(o, now, now)
                        truth += DemoTruth(now, o.id, corridor.alongM(rep), corridor.lateralM(rep), v?.horizontal()?.norm() ?: Float.NaN,
                            corridor.contains(rep), g.commands.firstOrNull { it.obstacleId == o.id }?.band)
                    }
                }
            }
            k++
        }
        val pcm = FloatArray(audio.size * 2 * n)
        for ((i, b) in audio.withIndex()) System.arraycopy(b, 0, pcm, i * 2 * n, 2 * n)
        return VideoSceneResult(pcm, rate, screens, said.filter { it.startNs >= 0 }, truth)
    }

    /** 촬영 시각 [tc]의 물체들(앱이 보는 거리 `map.radiusM` 안만). */
    private fun obstaclesAt(tc: Long, now: Long): List<Obstacle> = scene.objects.mapNotNull { o ->
        val tb = truthAt(o, tc, now) ?: return@mapNotNull null
        val rep = repPoint(tb)
        if ((rep - camW).horizontal().norm() > config.map.radiusM) return@mapNotNull null
        val v = velocity(o, tc, now)
        val d = tb.distanceM
        val sp = config.track.measSigma0M + config.track.measSigmaPerM2 * d * d
        val sv = (sqrt(2.0) * sp / settings.velocityWindowS).toFloat()
        val moving = v != null && v.horizontal().norm() >= config.track.minMovingMps
        Obstacle(o.id, rep, emptyMap(), tb.aabbMinW, tb.aabbMaxW, HeightClass.BODY, corridor.contains(rep), 1f, tc, 10,
            velocityW = v, motionRepW = rep, moving = moving, positionSigmaM = sp, velocitySigmaMps = sv)
    }

    /** 지난 [VideoDemoSettings.velocityWindowS] 동안 앞면 가운데의 평균 속도(수평). 창이 다 차지 않았으면 null. */
    private fun velocity(o: SceneObject, tNs: Long, now: Long): Vec3? {
        val wNs = (settings.velocityWindowS * 1e9).toLong()
        val a = truthAt(o, tNs - wNs, now) ?: return null
        val b = truthAt(o, tNs, now) ?: return null
        return (b.nearCenterW - a.nearCenterW).horizontal() * (1e9f / wNs)
    }

    /** 대표점: 상자 앞면에서 사용자와 수평으로 가장 가까운 점, 높이는 상자 가운데. */
    private fun repPoint(tb: TruthBox): Vec3 = Vec3(camW.x.coerceIn(tb.aabbMinW.x, tb.aabbMaxW.x), tb.nearCenterW.y, tb.aabbMaxW.z)

    /** 의미 영상 대신: 보이는 물체가 있으면 그 종류로 채운 작은 라벨 영상(장면마다 물체 하나, 설계 표 3). */
    private fun labelsAt(snap: ObstacleSnapshot): LabelImage {
        val label = snap.obstacles.firstOrNull()?.let { o -> scene.objects.first { it.id == o.id }.label } ?: SemanticLabel.UNLABELED
        return LabelImage(snap.tCaptureNs, ByteArray(labelK.width * labelK.height) { label.pixel.toByte() }, labelK, worldFromCam)
    }

    /** 결과 시각 [tNs]의 신호 상태(마지막으로 바뀐 상태, 첫 열쇠 전이면 OFF). */
    private fun signalState(tNs: Long): String {
        val tS = scene.startS + tNs / 1e9
        return scene.signal.lastOrNull { it.tS <= tS }?.state ?: "OFF"
    }

    companion object {
        /** 시간표 JSON(데모 페이지와 확인용). 화면 요소는 중계와 같은 모양([hearspace.core.relay.RelayBatch.screenJson]). */
        fun timelineJson(scene: VideoScene, r: VideoSceneResult): String {
            fun s(ns: Long) = JsonNumber(Math.round(ns / 1e6) / 1000.0)
            fun f(x: Float): JsonValue = if (x.isFinite()) JsonNumber(Math.round(x * 100) / 100.0) else hearspace.core.types.JsonNull
            fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(LinkedHashMap<String, JsonValue>().apply { for ((k, v) in p) put(k, v) })
            return MiniJson.write(obj(
                "id" to JsonString(scene.id), "title" to JsonString(scene.title),
                "durationS" to JsonNumber(r.audio.size / 2.0 / r.sampleRate),
                "screens" to JsonArray(r.screens.map { (t, sc) ->
                    JsonArray(listOf(s(t), hearspace.core.relay.RelayBatch.screenJson(hearspace.core.relay.RelayScreen(t, sc, null))))
                }),
                "speech" to JsonArray(r.speech.map { JsonArray(listOf(s(it.startNs), s(it.endNs), JsonString(it.text), JsonNumber(it.priority.toDouble()))) }),
                "truth" to JsonArray(r.truth.map {
                    JsonArray(listOf(s(it.tNs), JsonNumber(it.objectId.toDouble()), f(it.alongM), f(it.lateralM), f(it.speedMps),
                        hearspace.core.types.JsonBool(it.inCorridor), it.band?.let { b -> JsonString(b.name) } ?: hearspace.core.types.JsonNull))
                }),
            ), pretty = false)
        }
    }
}

