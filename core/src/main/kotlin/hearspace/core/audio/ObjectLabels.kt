package hearspace.core.audio

import hearspace.core.geometry.Vec3
import hearspace.core.types.DepthFrame
import hearspace.core.types.ObjectClassification
import hearspace.core.types.Obstacle
import hearspace.core.types.SemanticLabel

/** 이번에 관측한 장애물 표면 점을 기존 의미 영상에 투영해 물체별 라벨로 묶는다. */
class ObjectLabels(private val config: ObjectSpeechConfig, private val maxLabelAgeNs: Long) {
    fun attach(obstacles: List<Obstacle>, visiblePointsW: List<Vec3>, depth: DepthFrame): List<Obstacle> {
        if (!config.enabled || !config.useSemanticLabels) return obstacles
        val image = depth.labels ?: return obstacles
        if (depth.tCaptureNs - image.tCaptureNs !in 0L..maxLabelAgeNs) return obstacles
        return obstacles.map { obstacle ->
            val pixels = HashSet<Int>()
            val votes = IntArray(SemanticLabel.entries.size)
            for (point in visiblePointsW) {
                if (point.x !in obstacle.aabbMinW.x..obstacle.aabbMaxW.x ||
                    point.y !in obstacle.aabbMinW.y..obstacle.aabbMaxW.y ||
                    point.z !in obstacle.aabbMinW.z..obstacle.aabbMaxW.z) continue
                val index = image.indexAt(point)
                if (index < 0 || !pixels.add(index)) continue
                val pixel = image.labels.getOrNull(index)?.toInt()?.and(0xff) ?: continue
                if (pixel in votes.indices) votes[pixel]++
            }
            val total = votes.sum()
            val winner = votes.indices.maxByOrNull { votes[it] } ?: 0
            val fraction = if (total > 0) votes[winner].toFloat() / total else 0f
            val label = SemanticLabel.ofPixel(winner)
            val classification = if (total >= config.minLabelPixels && fraction >= config.minLabelFraction && label != null) {
                ObjectClassification(label, fraction, image.tCaptureNs)
            } else null
            obstacle.copy(classification = classification)
        }
    }
}
