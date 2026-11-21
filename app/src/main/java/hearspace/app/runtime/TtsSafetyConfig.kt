package hearspace.app.runtime

/** TTS 어댑터의 안전 한계. 감지/장애물 정책과 분리하며 시험에서 짧게 주입할 수 있다. */
data class TtsSafetyConfig(
    val initTimeoutMs: Long = 10_000,
    val startTimeoutMs: Long = 4_000,
    val maxQueueWaitMs: Long = 12_000,
    val completionBaseMs: Long = 3_000,
    val completionPerCharMs: Long = 250,
    val maxCompletionMs: Long = 30_000,
) {
    init {
        require(initTimeoutMs in 1..60_000 && startTimeoutMs in 1..60_000)
        require(maxQueueWaitMs in 1..60_000)
        require(completionBaseMs in 1..60_000 && completionPerCharMs in 1..1_000)
        require(maxCompletionMs in completionBaseMs..120_000)
    }
}
