package hearspace.viewer

import hearspace.core.types.JsonArray
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.MiniJson
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 정성 평가 항목(IMPROVE_SPEC §9.3). 1~5점. */
enum class RatingItem(val label: String) {
    DIRECTION("방향 정확성"),
    DISTANCE("거리감"),
    SEPARATION("물체 구분"),
    DANGER("위험 인지"),
    COMFORT("소음·피로(5 = 편함)"),
}

/** 재생 중 특정 시각의 메모. [tS]는 세션 시작 후 초. */
data class Note(val tS: Double, val text: String)

/**
 * 평가 한 번(IMPROVE_SPEC §9.3). `data/feedback/<세션ID>/<시각>.json`에 저장한다(`data/`는 git 제외).
 * 어떤 설정으로 들었는지 알 수 있게 덮어쓴 설정과 그 해시를 함께 남긴다.
 */
data class Feedback(
    val sessionId: String,
    val scene: String,
    val variant: String,
    val overridesJson: String,
    val ratings: Map<RatingItem, Int>,
    val notes: List<Note>,
    val createdAt: String,
) {
    init {
        require(ratings.values.all { it in 1..5 }) { "ratings must be 1..5" }
    }

    fun toJson(): String = MiniJson.write(
        JsonObject(
            linkedMapOf(
                "version" to JsonNumber(1.0),
                "session" to JsonString(sessionId),
                "scene" to JsonString(scene),
                "variant" to JsonString(variant),
                "configHash" to JsonString(configHash(overridesJson)),
                "overrides" to MiniJson.parse(overridesJson),
                "createdAt" to JsonString(createdAt),
                "ratings" to JsonObject(ratings.entries.associate { (k, v) -> k.name to JsonNumber(v.toDouble()) as JsonValue }),
                "notes" to JsonArray(notes.map { JsonObject(linkedMapOf("tS" to JsonNumber(it.tS), "text" to JsonString(it.text))) }),
            ),
        ),
    )

    /** `data/feedback/<세션ID>/<시각>.json`으로 저장하고 파일을 돌려준다. */
    fun save(root: File = Repo.feedback): File {
        val dir = File(root, sessionId).apply { mkdirs() }
        return File(dir, createdAt.replace(":", "").replace("-", "") + ".json").apply { writeText(toJson() + "\n") }
    }

    companion object {
        fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        /** 덮어쓴 설정의 해시. 공백·줄바꿈이 달라도 같은 설정이면 같다(JSON을 한 줄로 다시 써서 잰다). */
        fun configHash(overridesJson: String): String {
            val canonical = MiniJson.write(MiniJson.parse(overridesJson), pretty = false)
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        }

        fun parse(text: String): Feedback {
            val f = (MiniJson.parse(text) as JsonObject).fields
            fun str(k: String) = (f[k] as JsonString).value
            return Feedback(
                sessionId = str("session"),
                scene = str("scene"),
                variant = str("variant"),
                overridesJson = MiniJson.write(f["overrides"]!!, pretty = false),
                ratings = (f["ratings"] as JsonObject).fields.entries.associate { (k, v) -> RatingItem.valueOf(k) to (v as JsonNumber).value.toInt() },
                notes = (f["notes"] as JsonArray).items.map { n ->
                    val o = (n as JsonObject).fields
                    Note((o["tS"] as JsonNumber).value, (o["text"] as JsonString).value)
                },
                createdAt = str("createdAt"),
            )
        }
    }
}
