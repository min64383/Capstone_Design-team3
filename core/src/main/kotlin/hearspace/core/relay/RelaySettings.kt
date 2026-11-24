package hearspace.core.relay

import hearspace.core.types.ConfigException
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.MiniJson

/**
 * 데모 중계 설정(M66 설계 3.9절, `app/src/bench/assets/relay.json`). 앱 기본 설정(`default.json`)과 따로 둔다:
 * 데모용 bench 빌드와 중계 서버·PC 녹화 재생기만 읽는다. 값의 출처는 설계 표 6.
 * 열쇠는 git에 없는 덮어쓰기 파일로 얹는다(서버 `data/relay/server.json`).
 */
data class RelaySettings(
    /** 재생 시각을 서버 시각보다 늦추는 묵힘(모으기 0.2 s + 망 흔들림). */
    val holdMs: Int,
    /** 마지막 표본이 이보다 늙으면 "중계 끊김". 저장소 정리·다시 붙기 간격으로도 쓴다. */
    val lostMs: Int,
    val sampleHz: Int,
    val batchMs: Int,
    /** 페이지 링 버퍼: 이만큼 채운 뒤 재생, [clientMaxBufferMs]를 넘으면 이만큼만 남기고 버림. */
    val clientBufferMs: Int,
    val clientMaxBufferMs: Int,
    /** 소리 고리 길이. 이만큼 뒤처진 청취자는 최신으로 건너뛴다. */
    val listenerLagMs: Int,
    val speechGainDb: Float,
    val chatKeep: Int,
    val chatMaxChars: Int,
    val chatMinIntervalMs: Int,
    val nickMaxChars: Int,
    val maxPages: Int,
    val sseKeepaliveS: Int,
    val clientStatsS: Int,
    val uploadMaxBytes: Int,
    val port: Int,
    val bindHost: String,
    /** 말 소리 모음 폴더(저장소 기준). 파일 이름은 문장(UTF-8)의 SHA-1 + `.wav`. */
    val clipDir: String,
    /** 영상 데모(M73) 결과 폴더(저장소 기준): `<장면>.mp4`·`<장면>.json`을 `/demo/`로 준다. */
    val demoDir: String,
    val uploadKey: String,
    val viewKey: String,
    /** S10 업로더가 올릴 서버 주소(M67, 기기 파일에서 얹음). 서버는 읽지 않는다. */
    val serverUrl: String,
    /** S10 업로더의 연결·읽기 시간 제한(M67). 막혀도 이만큼 뒤에는 다음 묶음을 보낼 수 있다. */
    val uploadTimeoutMs: Int,
) {
    companion object {
        /** [base] 위에 [overrides]를 차례로 얹는다. 덮어쓰기는 [base]에 있는 키만 쓸 수 있다(모르는 키 거부). */
        fun load(base: String, overrides: List<String> = emptyList()): RelaySettings {
            fun obj(text: String, where: String) =
                (MiniJson.parse(text) as? JsonObject)?.fields ?: throw ConfigException(where, "must be a JSON object")
            val v = LinkedHashMap(obj(base, "relay"))
            for (o in overrides) for ((k, x) in obj(o, "relay override")) {
                if (k !in v) throw ConfigException("relay.$k", "unknown key")
                v[k] = x
            }
            val used = HashSet<String>()
            fun raw(k: String): JsonValue = v[k]?.also { used += k } ?: throw ConfigException("relay.$k", "missing")
            fun num(k: String) = (raw(k) as? JsonNumber)?.value ?: throw ConfigException("relay.$k", "must be a number")
            fun int(k: String, min: Int = 1): Int {
                val d = num(k)
                if (d != Math.rint(d) || d < min || d > Int.MAX_VALUE) throw ConfigException("relay.$k", "must be an integer ≥ $min")
                return d.toInt()
            }
            fun str(k: String) = (raw(k) as? JsonString)?.value ?: throw ConfigException("relay.$k", "must be a string")
            val s = RelaySettings(
                holdMs = int("holdMs"), lostMs = int("lostMs"), sampleHz = int("sampleHz"), batchMs = int("batchMs"),
                clientBufferMs = int("clientBufferMs"), clientMaxBufferMs = int("clientMaxBufferMs"), listenerLagMs = int("listenerLagMs"),
                speechGainDb = num("speechGainDb").toFloat(), chatKeep = int("chatKeep"), chatMaxChars = int("chatMaxChars"),
                chatMinIntervalMs = int("chatMinIntervalMs", 0), nickMaxChars = int("nickMaxChars"), maxPages = int("maxPages"),
                sseKeepaliveS = int("sseKeepaliveS"), clientStatsS = int("clientStatsS"), uploadMaxBytes = int("uploadMaxBytes"),
                port = int("port", 0), bindHost = str("bindHost"), clipDir = str("clipDir"), demoDir = str("demoDir"),
                uploadKey = str("uploadKey"), viewKey = str("viewKey"),
                serverUrl = str("serverUrl"), uploadTimeoutMs = int("uploadTimeoutMs"),
            )
            (v.keys - used).firstOrNull()?.let { throw ConfigException("relay.$it", "unknown key") }
            if (s.clientMaxBufferMs <= s.clientBufferMs) throw ConfigException("relay.clientMaxBufferMs", "must exceed clientBufferMs")
            if (s.port > 65535) throw ConfigException("relay.port", "must be ≤ 65535")
            return s
        }
    }
}
