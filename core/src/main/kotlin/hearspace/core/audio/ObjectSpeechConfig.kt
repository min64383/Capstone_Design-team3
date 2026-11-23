package hearspace.core.audio

import hearspace.core.types.JsonBool
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.MiniJson

data class ObjectSpeechConfig(
    val enabled: Boolean,
    val useSemanticLabels: Boolean,
    val minLabelPixels: Int,
    val minLabelFraction: Float,
    val frontHalfAngleDeg: Float,
    val normalConfirmMs: Long,
    val urgentConfirmMs: Long,
    val repeatMs: Long,
    val gapMs: Long,
) {
    init {
        require(minLabelPixels > 0)
        require(minLabelFraction.isFinite() && minLabelFraction > 0.5f && minLabelFraction <= 1f)
        require(frontHalfAngleDeg.isFinite() && frontHalfAngleDeg > 0f && frontHalfAngleDeg < 90f)
        require(normalConfirmMs >= 0 && urgentConfirmMs >= 0 && repeatMs > 0 && gapMs >= 0)
        require(listOf(normalConfirmMs, urgentConfirmMs, repeatMs, gapMs).all { it <= Long.MAX_VALUE / 1_000_000L })
    }

    companion object {
        fun parse(text: String): ObjectSpeechConfig {
            val root = MiniJson.parse(text) as? JsonObject ?: error("object-speech must be an object")
            fun boolean(key: String) = (root.fields[key] as? JsonBool)?.value ?: error("Invalid $key")
            fun number(key: String): Double {
                val value = (root.fields[key] as? JsonNumber)?.value ?: error("Invalid $key")
                require(value.isFinite()) { "Invalid $key" }
                return value
            }
            fun integer(key: String): Long {
                val value = number(key)
                require(value >= 0 && value <= Long.MAX_VALUE / 1_000_000.0 && value == value.toLong().toDouble())
                return value.toLong()
            }
            val pixels = integer("minLabelPixels")
            require(pixels <= Int.MAX_VALUE)
            return ObjectSpeechConfig(boolean("enabled"), boolean("useSemanticLabels"), pixels.toInt(),
                number("minLabelFraction").toFloat(), number("frontHalfAngleDeg").toFloat(),
                integer("normalConfirmMs"), integer("urgentConfirmMs"), integer("repeatMs"), integer("gapMs"))
        }
    }
}
