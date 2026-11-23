package hearspace.core.relay

import hearspace.core.screen.UserScreen
import hearspace.core.types.AlertKind
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonBool
import hearspace.core.types.JsonNull
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.MiniJson
import hearspace.core.types.SoundKind

/** 20 ms마다 읽은 안내 출력 하나(`lastOutput`). [speechActive]는 렌더러에 넘기는 "TTS가 다른 소리를 줄이는 중". */
data class RelaySample(val tNs: Long, val state: GuidanceState, val headingDeg: Float, val speechActive: Boolean, val commands: List<AudioCmd>)

/** 한 블록에만 있어 20 ms 표본에서 빠질 수 있는 것: 1회 알림, 말 시작·끝. */
sealed interface RelayEvent {
    val tNs: Long
    data class Alert(override val tNs: Long, val kind: AlertKind) : RelayEvent
    data class SayStart(override val tNs: Long, val text: String) : RelayEvent
    data class SayEnd(override val tNs: Long) : RelayEvent
}

/** 바뀐 화면 요소. [notice]는 시작 전 로고 화면에만 보이는 고지 글(앱 화면의 아래 작은 글). */
data class RelayScreen(val tNs: Long, val screen: UserScreen, val notice: String?)

/** 세션의 첫 묶음(또는 서버가 409로 달라고 할 때): 앱 커밋과 설정 층 글(기본 → 덮어쓰기, 앱이 합치는 순서). */
data class RelayStart(val appCommit: String, val configLayers: List<String>)

/**
 * 업로드 묶음 하나(M66 설계 3.2절, 표 2). 시각은 모두 S10 파이프라인 시계(ns). JSON은 core [MiniJson]으로 쓰고 읽으며,
 * float은 double로 오가므로 읽은 값이 쓴 값과 같다. NaN·무한은 null로 쓴다.
 */
data class RelayBatch(
    val session: String,
    val seq: Long,
    val sentNs: Long,
    val start: RelayStart?,
    val samples: List<RelaySample>,
    val events: List<RelayEvent>,
    val screens: List<RelayScreen>,
) {
    fun toJson(): String = MiniJson.write(obj(
        "v" to JsonNumber(1.0), "session" to JsonString(session), "seq" to num(seq), "sentNs" to num(sentNs),
        "start" to start?.let { obj("commit" to JsonString(it.appCommit), "config" to JsonArray(it.configLayers.map(::JsonString))) },
        "samples" to JsonArray(samples.map { s ->
            obj("t" to num(s.tNs), "st" to JsonString(s.state.name), "hd" to f(s.headingDeg), "sp" to JsonBool(s.speechActive),
                "c" to JsonArray(s.commands.map { c ->
                    JsonArray(listOf(num(c.obstacleId.toLong()), f(c.azimuthDeg), f(c.distanceM), JsonString(c.band.name), JsonString(c.sound.name),
                        f(c.infoAgeMs), JsonBool(c.inCorridor), f(c.heightDeltaM)))
                }))
        }),
        "events" to JsonArray(events.map { e ->
            when (e) {
                is RelayEvent.Alert -> obj("t" to num(e.tNs), "alert" to JsonString(e.kind.name))
                is RelayEvent.SayStart -> obj("t" to num(e.tNs), "say" to JsonString(e.text))
                is RelayEvent.SayEnd -> obj("t" to num(e.tNs), "sayEnd" to JsonBool(true))
            }
        }),
        "screens" to JsonArray(screens.map { JsonObject(LinkedHashMap<String, JsonValue>().apply { put("t", num(it.tNs)); putAll(screenJson(it).fields) }) }),
    ), pretty = false)

    companion object {
        const val VERSION = 1

        /** [toJson]의 반대. 형식이 틀리면 [IllegalArgumentException]. */
        fun parse(text: String): RelayBatch {
            val o = MiniJson.parse(text).o()
            require(o.n("v").toInt() == VERSION) { "relay batch version" }
            return RelayBatch(
                session = o.s("session"), seq = o.n("seq").toLong(), sentNs = o.n("sentNs").toLong(),
                start = (o.fields["start"] as? JsonObject)?.let { st -> RelayStart(st.s("commit"), st.a("config").map { (it as JsonString).value }) },
                samples = o.a("samples").map { v ->
                    val s = v.o()
                    RelaySample(s.n("t").toLong(), GuidanceState.valueOf(s.s("st")), s.fl("hd"), (s.fields["sp"] as JsonBool).value,
                        s.a("c").map { cv ->
                            val c = (cv as JsonArray).items
                            AudioCmd(c[0].num().toInt(), c[1].fl(), c[2].fl(), Band.valueOf(c[3].str()), SoundKind.valueOf(c[4].str()),
                                c[5].fl(), (c[6] as JsonBool).value, c[7].fl())
                        })
                },
                events = o.a("events").map { v ->
                    val e = v.o()
                    val t = e.n("t").toLong()
                    when {
                        "alert" in e.fields -> RelayEvent.Alert(t, AlertKind.valueOf(e.s("alert")))
                        "say" in e.fields -> RelayEvent.SayStart(t, e.s("say"))
                        "sayEnd" in e.fields -> RelayEvent.SayEnd(t)
                        else -> throw IllegalArgumentException("unknown relay event")
                    }
                },
                screens = o.a("screens").map { v -> v.o().let { RelayScreen(it.n("t").toLong(), screenOf(it), it.fields["notice"].strOrNull()) } },
            )
        }

        /** 화면 요소를 JSON 객체로(시각 없이). 서버가 페이지에 그대로 보낸다. */
        fun screenJson(r: RelayScreen): JsonObject {
            val s = r.screen
            val arrow = s.visual as? UserScreen.Visual.Arrow
            return obj(
                "title" to s.title?.let(::JsonString), "sub" to s.sub?.let(::JsonString), "titleTone" to JsonString(s.titleTone.name),
                "visual" to JsonString(visualName(s.visual)), "az" to arrow?.let { f(it.azimuthDeg) }, "stop" to arrow?.let { JsonBool(it.stop) },
                "label" to s.label?.let(::JsonString), "labelTone" to JsonString(s.labelTone.name),
                "hints" to JsonArray(s.hints.map { (g, m) -> JsonArray(listOf(JsonString(g), JsonString(m))) }),
                "idle" to JsonBool(s.idle), "spoken" to JsonString(s.spoken), "notice" to r.notice?.let(::JsonString),
            )
        }

        private fun screenOf(o: JsonObject): UserScreen {
            val visual = when (val v = o.s("visual")) {
                "Arrow" -> UserScreen.Visual.Arrow(o.fl("az"), (o.fields["stop"] as JsonBool).value)
                else -> VISUALS[v] ?: throw IllegalArgumentException("unknown visual $v")
            }
            return UserScreen(o.fields["title"].strOrNull(), o.fields["sub"].strOrNull(), UserScreen.Tone.valueOf(o.s("titleTone")), visual,
                o.fields["label"].strOrNull(), UserScreen.Tone.valueOf(o.s("labelTone")),
                o.a("hints").map { h -> (h as JsonArray).items.let { it[0].str() to it[1].str() } }, (o.fields["idle"] as JsonBool).value, o.s("spoken"))
        }

        private val VISUALS = listOf(UserScreen.Visual.Logo, UserScreen.Visual.Preparing, UserScreen.Visual.FloorCheck, UserScreen.Visual.None,
            UserScreen.Visual.Paused, UserScreen.Visual.Notice).associateBy(::visualName)

        private fun visualName(v: UserScreen.Visual) = when (v) {
            is UserScreen.Visual.Arrow -> "Arrow"
            else -> v.toString() // data object의 이름(Logo, Preparing, …)
        }

        private fun obj(vararg p: Pair<String, JsonValue?>) = JsonObject(LinkedHashMap<String, JsonValue>().apply { for ((k, v) in p) put(k, v ?: JsonNull) })
        private fun num(x: Long) = JsonNumber(x.toDouble())
        private fun f(x: Float): JsonValue = if (x.isFinite()) JsonNumber(x.toDouble()) else JsonNull
        private fun JsonValue.o() = this as? JsonObject ?: throw IllegalArgumentException("expected object")
        private fun JsonValue.num() = (this as? JsonNumber)?.value ?: throw IllegalArgumentException("expected number")
        private fun JsonValue.fl() = if (this is JsonNull) Float.NaN else num().toFloat()
        private fun JsonValue.str() = (this as? JsonString)?.value ?: throw IllegalArgumentException("expected string")
        private fun JsonValue?.strOrNull() = if (this == null || this is JsonNull) null else str()
        private fun JsonObject.n(k: String) = (fields[k] ?: throw IllegalArgumentException("missing $k")).num()
        private fun JsonObject.s(k: String) = (fields[k] ?: throw IllegalArgumentException("missing $k")).str()
        private fun JsonObject.fl(k: String) = (fields[k] ?: JsonNull).fl()
        private fun JsonObject.a(k: String) = (fields[k] as? JsonArray)?.items ?: throw IllegalArgumentException("missing $k")
    }
}
