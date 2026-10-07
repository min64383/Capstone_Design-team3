package hearspace.core.audio

import hearspace.core.types.AudioCmd

/** 렌더한 블록의 진단값. command=null은 소실된 음원의 release, 안전을 뜻하지 않는다. */
data class SonificationSample(
    val tBlockNs: Long,
    val obstacleId: Int,
    val command: AudioCmd?,
    val closingMps: Float,
    val ttcS: Float,
    val risk: Float?,
    val active: Boolean,
    val targetPitchHz: Float,
    val pitchHz: Float,
    val targetGain: Float,
    val gain: Float,
    val duckGain: Float,
)
