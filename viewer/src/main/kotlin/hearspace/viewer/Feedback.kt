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
 * 책갈피 정성 평가 문항(M30 설계 5절). 앞의 셋은 칸 분류 색이 있는 장면(정답이 장면 전체인 E 세션)에서만, [SHAPE]는 모든 장면에서 매긴다.
 */
enum class BookmarkItem(val label: String, val needsTruthColors: Boolean) {
    HIT("맞은 칸(초록)이 실제 면인가", true),
    FALSE("헛 칸(빨강)이 실제 빈 곳인가", true),
    MISSED("놓친 표면(흰 테)이 실제로 보였던 면인가", true),
    SHAPE("지도가 실제 공간과 닮았는가", false),
}

/** 책갈피 하나의 평가: 시각·이름, 문항별 1~5점(매기지 않은 문항은 없음), 한 줄 메모. */
data class BookmarkScore(val tS: Double, val label: String, val scores: Map<BookmarkItem, Int>, val memo: String)

/** 정해 둔 정성 평가 시점(`viewer/bookmarks.json`, `tools/analysis/scene_eval.py bookmarks`가 만든다). */
data class Bookmark(val tS: Double, val label: String)

object Bookmarks {
    val file: File get() = File(Repo.root, "viewer/bookmarks.json")

    /** 책갈피가 있는 세션 ID 목록. */
    fun sessionIds(f: File = file): List<String> = if (!f.isFile) emptyList() else
        ((MiniJson.parse(f.readText()) as JsonObject).fields["sessions"] as JsonArray).items.map { ((it as JsonObject).fields["session"] as JsonString).value }

    /** 세션 [sessionId]의 책갈피와, 칸 분류 색으로 평가하는 장면인지. 없으면 빈 목록. */
    fun of(sessionId: String, f: File = file): Pair<List<Bookmark>, Boolean> {
        if (!f.isFile) return emptyList<Bookmark>() to false
        val s = ((MiniJson.parse(f.readText()) as JsonObject).fields["sessions"] as JsonArray).items
            .map { (it as JsonObject).fields }.firstOrNull { (it["session"] as JsonString).value == sessionId } ?: return emptyList<Bookmark>() to false
        val marks = (s["marks"] as JsonArray).items.map { m ->
            val o = (m as JsonObject).fields
            Bookmark((o["tS"] as JsonNumber).value, (o["label"] as JsonString).value)
        }
        return marks to ((s["truthColors"] as? hearspace.core.types.JsonBool)?.value ?: false)
    }
}

/**
 * 평가 한 번(IMPROVE_SPEC §9.3). `data/feedback/<세션ID>/<시각>.json`에 저장한다(`data/`는 git 제외).
 * 어떤 설정으로 들었는지 알 수 있게 덮어쓴 설정과 그 해시를 함께 남긴다.
 */
data class Feedback(
    val sessionId: String,
    /** 저장소 기준 상대경로(`testdata/sessions/<ID>`). 같은 ID가 testdata와 data 양쪽에 있을 수 있어 함께 남긴다. */
    val sessionPath: String,
    val scene: String,
    val variant: String,
    val overridesJson: String,
    val ratings: Map<RatingItem, Int>,
    val notes: List<Note>,
    val createdAt: String,
    /** 비교한 두 번째 결과(나란히 보기, M30)의 이름. 없으면 빈 글. */
    val compare: String = "",
    val bookmarks: List<BookmarkScore> = emptyList(),
) {
    init {
        require(ratings.values.all { it in 1..5 }) { "ratings must be 1..5" }
        require(bookmarks.all { b -> b.scores.values.all { it in 1..5 } }) { "bookmark scores must be 1..5" }
    }

    fun toJson(): String = MiniJson.write(
        JsonObject(
            linkedMapOf(
                "version" to JsonNumber(1.0),
                "session" to JsonString(sessionId),
                "sessionPath" to JsonString(sessionPath),
                "scene" to JsonString(scene),
                "variant" to JsonString(variant),
                "configHash" to JsonString(configHash(overridesJson)),
                "overrides" to MiniJson.parse(overridesJson),
                "createdAt" to JsonString(createdAt),
                "ratings" to JsonObject(ratings.entries.associate { (k, v) -> k.name to JsonNumber(v.toDouble()) as JsonValue }),
                "notes" to JsonArray(notes.map { JsonObject(linkedMapOf("tS" to JsonNumber(it.tS), "text" to JsonString(it.text))) }),
                "compare" to JsonString(compare),
                "bookmarks" to JsonArray(
                    bookmarks.map { b ->
                        JsonObject(
                            linkedMapOf(
                                "tS" to JsonNumber(b.tS),
                                "label" to JsonString(b.label),
                                "scores" to JsonObject(b.scores.entries.associate { (k, v) -> k.name to JsonNumber(v.toDouble()) as JsonValue }),
                                "memo" to JsonString(b.memo),
                            ),
                        )
                    },
                ),
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
                sessionPath = (f["sessionPath"] as? JsonString)?.value ?: str("session"),
                scene = str("scene"),
                variant = str("variant"),
                overridesJson = MiniJson.write(f["overrides"]!!, pretty = false),
                ratings = (f["ratings"] as JsonObject).fields.entries.associate { (k, v) -> RatingItem.valueOf(k) to (v as JsonNumber).value.toInt() },
                notes = (f["notes"] as JsonArray).items.map { n ->
                    val o = (n as JsonObject).fields
                    Note((o["tS"] as JsonNumber).value, (o["text"] as JsonString).value)
                },
                createdAt = str("createdAt"),
                compare = (f["compare"] as? JsonString)?.value ?: "",
                bookmarks = (f["bookmarks"] as? JsonArray)?.items.orEmpty().map { b ->
                    val o = (b as JsonObject).fields
                    BookmarkScore(
                        (o["tS"] as JsonNumber).value,
                        (o["label"] as JsonString).value,
                        (o["scores"] as JsonObject).fields.entries.associate { (k, v) -> BookmarkItem.valueOf(k) to (v as JsonNumber).value.toInt() },
                        (o["memo"] as JsonString).value,
                    )
                },
            )
        }
    }
}
