package hearspace.viewer

import hearspace.core.audio.ObjectCueSettings
import hearspace.core.audio.ObjectCueTone
import java.util.Properties

/** Viewer settings are independent of algorithm override JSON. */
object ObjectCueOptions {
    fun load(): ObjectCueSettings {
        val p = Properties()
        checkNotNull(javaClass.getResourceAsStream("/object-cues.properties")).use { p.load(it) }
        fun value(k: String) = requireNotNull(p.getProperty(k)) { "Missing object cue setting: $k" }
        fun tone(prefix: String) = ObjectCueTone(
            value("$prefix.startHz").toDouble(), value("$prefix.endHz").toDouble(),
            value("$prefix.durationMs").toInt(), value("$prefix.amplitude").toDouble(),
            value("$prefix.fadeInMs").toInt(), value("$prefix.fadeOutMs").toInt())
        return ObjectCueSettings(value("appearHoldMs").toLong(), value("lostHoldMs").toLong(),
            value("cooldownMs").toLong(), value("alertGuardMs").toLong(), value("gain").toFloat(),
            tone("appear"), tone("lost"))
    }
}
