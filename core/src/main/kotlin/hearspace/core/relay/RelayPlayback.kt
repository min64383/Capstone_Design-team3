package hearspace.core.relay

import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.audio.Sounds
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import java.util.TreeMap

/**
 * 데모 중계의 재생 시계(M66 설계 3.3~3.4절, 그림 2, 표 3). 업로드 묶음을 받아 두고, 서버 시각마다 블록 하나를 렌더한다.
 * - 시계 차 = 묶음마다 (서버 도착 시각 − 보낸 시각)의 최솟값(가장 빨리 온 묶음의 망 지연을 0으로 봄). 세션이 바뀌면 다시 잰다.
 * - 재생 시각 P = 서버 시각 − 시계 차 − [RelaySettings.holdMs]. 시계 차는 줄기만 하므로 P는 뒤로 가지 않는다.
 * - P 이하의 마지막 표본만 쓴다(미래 값 금지). 음원 정보 나이에 (P − 표본 시각)을 더해, 렌더러가 허용치를 넘은 음원을 버리게 한다.
 * - 렌더러는 S10이 보낸 설정 층으로 합친 설정의 core [BinauralRenderer]이고, S10과 같은 `renderWithSpeech`로 부른다.
 * - 말 소리는 말 시작 사건이 지난 블록부터 섞고 끝 사건에서 멈춘다. 하나만 섞고 새 말이 앞 말을 끊는다.
 *   크기는 S10 설정의 `audio.ttsVolume` × [RelaySettings.speechGainDb]: S10은 TTS가 재생할 때 음량을 곱하지만 말 소리 모음(`synthesizeToFile`)에는
 *   음량이 걸리지 않는다(M70 설계 3.5절, S10에서 1.0·0.7로 만든 64개가 바이트까지 같음).
 * 업로드는 대기열이 아니다: 시각 순 저장소에 넣고 재생 시각으로 찾으므로 늦게 온 값이 쌓여 소리를 늦추지 않는다.
 * ponytail: 시계 차는 최솟값을 계속 쓴다. 두 시계의 빠르기 차이(수십 ppm, 검증 안 함)만큼 묵힘이 줄거나 늘며, 크면 창 최솟값으로.
 * [accept]와 [render]는 다른 스레드에서 불러도 된다(이 객체로 동기화).
 */
class RelayPlayback(
    private val hrtf: Hrtf,
    private val settings: RelaySettings,
    /** 서버의 기본 설정. 블록 크기·표본율은 이것으로 고정하고, 세션 설정이 다르면 받지 않는다. */
    baseConfig: Config,
    /** 문장 → 말 소리(모노, 표본율 = [baseConfig] 표본율, ±1). 없으면 null. */
    private val clip: (String) -> FloatArray?,
) {
    enum class Accept { OK, NEED_START, BAD }

    /** 블록 하나의 결과. [pNs]는 재생 시각(세션 전이면 null), [sampleTNs]는 쓴 표본 시각, [missing]은 이 블록에서 소리 모음에 없던 문장. */
    data class Block(val pNs: Long?, val live: Boolean, val screen: RelayScreen?, val sampleTNs: Long?, val missing: List<String>)

    val sampleRate = baseConfig.audio.sampleRate
    val blockSize = baseConfig.audio.blockSize
    private val holdNs = settings.holdMs * 1_000_000L
    private val lostNs = settings.lostMs * 1_000_000L
    private val speechGain = Sounds.db(settings.speechGainDb)

    private var session: String? = null
    private var config: Config? = null
    private var renderer: BinauralRenderer? = null
    private var offsetNs = Long.MAX_VALUE
    private var lastP = Long.MIN_VALUE
    private val samples = TreeMap<Long, RelaySample>()
    private val events = TreeMap<Long, MutableList<RelayEvent>>()
    private val screens = TreeMap<Long, RelayScreen>()
    /** 이미 지난 재생 시각으로 늦게 온 사건(다음 블록에서 처리). */
    private val late = ArrayList<RelayEvent>()
    private var speech: FloatArray? = null
    private var speechPos = 0

    /** 세션 설정의 앱 커밋(서버가 자기 커밋과 견준다). */
    @get:Synchronized var appCommit: String? = null; private set
    /** 늦게 온 사건 수, 그중 너무 늦어 버린 알림 수(서버 기록용). */
    @get:Synchronized var lateEvents = 0L; private set
    @get:Synchronized var lateAlertsDropped = 0L; private set

    /** 묶음 하나를 받는다. 처음 보는 세션인데 시작 칸이 없으면 [Accept.NEED_START], 설정을 합칠 수 없으면 [Accept.BAD]. */
    @Synchronized
    fun accept(b: RelayBatch, arrivalNs: Long): Accept {
        if (b.session != session) {
            val st = b.start ?: return Accept.NEED_START
            val cfg = try {
                ConfigLoader.load(st.configLayers.first(), st.configLayers.drop(1))
            } catch (e: Exception) {
                return Accept.BAD
            }
            if (cfg.audio.sampleRate != sampleRate || cfg.audio.blockSize != blockSize) return Accept.BAD
            session = b.session; config = cfg; renderer = BinauralRenderer(cfg, hrtf); appCommit = st.appCommit
            offsetNs = Long.MAX_VALUE; lastP = Long.MIN_VALUE
            samples.clear(); events.clear(); screens.clear(); late.clear(); speech = null
        }
        offsetNs = minOf(offsetNs, arrivalNs - b.sentNs)
        for (s in b.samples) samples[s.tNs] = s
        for (s in b.screens) screens[s.tNs] = s
        for (e in b.events) {
            if (e.tNs <= lastP) { late += e; lateEvents++ } else events.getOrPut(e.tNs) { ArrayList() } += e
        }
        return Accept.OK
    }

    /** 서버 시각 [wallNs]의 블록 하나를 [out](2 × blockSize, L R 교차)에 쓴다. 말 소리를 더한 뒤라 ±1을 넘을 수 있다(16-bit로 바꿀 때 자름). */
    @Synchronized
    fun render(wallNs: Long, out: FloatArray): Block {
        require(out.size == 2 * blockSize)
        val r = renderer
        val cfg = config
        if (r == null || cfg == null || offsetNs == Long.MAX_VALUE) {
            out.fill(0f)
            return Block(null, false, null, null, emptyList())
        }
        val p = wallNs - offsetNs - holdNs
        val maxAgeNs = (cfg.policy.maxInfoAgeMs * 1_000_000.0).toLong()
        val missing = ArrayList<String>()
        fun onEvent(e: RelayEvent, wasLate: Boolean) {
            when (e) {
                is RelayEvent.Alert -> if (p - e.tNs <= maxAgeNs) r.alert(e.kind) else lateAlertsDropped++
                is RelayEvent.SayStart -> {
                    val c = clip(e.text)
                    if (c == null) { missing += e.text; speech = null } else {
                        speech = c
                        // 블록 시작에 맞춰 시작(오차 한 블록 안). 늦게 온 말은 지난 만큼 건너뛰어 S10과 위치를 맞춘다
                        speechPos = if (wasLate) ((p - e.tNs) * sampleRate / 1_000_000_000L).toInt() else 0
                    }
                }
                is RelayEvent.SayEnd -> speech = null
            }
        }
        for (e in late) onEvent(e, true)
        late.clear()
        val due = events.headMap(p, true)
        for (list in due.values) for (e in list) onEvent(e, false)
        due.clear()

        val held = samples.floorEntry(p)?.value
        val g = if (held == null) GuidanceOutput(p, GuidanceState.UNKNOWN, emptyList(), Float.NaN, null) else {
            val addMs = (p - held.tNs) / 1e6f
            GuidanceOutput(p, held.state, held.commands.map { it.copy(infoAgeMs = it.infoAgeMs + addMs) }, held.headingDeg, null)
        }
        r.renderWithSpeech(g, out, held?.speechActive ?: false)
        speech?.let { c ->
            var i = 0
            while (i < blockSize && speechPos < c.size) {
                val v = c[speechPos++] * speechGain * cfg.audio.ttsVolume
                out[2 * i] += v
                out[2 * i + 1] += v
                i++
            }
            if (speechPos >= c.size) speech = null
        }

        val screen = screens.floorEntry(p)?.value
        val live = held != null && p - held.tNs <= lostNs
        // 정리: 끊김 기준보다 오래된 것은 지우되 P 이하 마지막 하나는 남긴다
        val cut = p - lostNs
        samples.floorKey(p)?.let { samples.headMap(minOf(it, cut)).clear() }
        screens.floorKey(p)?.let { screens.headMap(minOf(it, cut)).clear() }
        lastP = p
        return Block(p, live, screen, held?.tNs, missing)
    }
}
