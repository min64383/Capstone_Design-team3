package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.tracking.Cluster
import hearspace.core.tracking.HeightClassifier
import hearspace.core.tracking.RepPoint
import hearspace.core.types.Config
import hearspace.core.types.Obstacle
import kotlin.math.abs
import kotlin.math.floor

/**
 * Experimental near STOP from current filtered depth, after a known floor. No persistent map insertion.
 * Requires spatial support and distinct consecutive frames; publishes only currently observed candidates.
 * Negative ids are local warning identities, never normal map track ids.
 */
class NearStopDetector(private val config: Config) {
    internal data class FrameEvidence(val roiPoints: Int, val cells: Int, val sizeCandidates: Int,
        val maxObservations: Int, val published: Int)
    internal var observeMatch: ((Long, Vec3, Obstacle?, Obstacle?, Float) -> Unit)? = null
    internal var observeGroup: ((cells: Int, heightM: Float, widthM: Float, firstFailedGate: String) -> Unit)? = null
    internal var observeFrame: ((FrameEvidence) -> Unit)? = null

    private val cfg = requireNotNull(config.policy.nearStop)
    private data class Candidate(val obstacle: Obstacle, val fullWidth: Boolean)
    private var previous = emptyList<Candidate>()
    private var lastTNs: Long? = null
    private var nextId = -1

    fun update(pointsW: List<Vec3>, tNs: Long, corridor: Corridor, confirmed: List<Obstacle>): List<Obstacle> {
        val last = lastTNs
        if (last != null && tNs <= last) return emptyList()
        if (last != null && (tNs - last) / 1e6f > cfg.maxGapMs) previous = emptyList()
        lastTNs = tNs
        val voxelM = config.map.voxelSizeM
        // One vote per spatial cell. Dense pixels on the same cell cannot meet minVoxels alone.
        val unique = LinkedHashMap<Triple<Int, Int, Int>, Vec3>()
        var roiPoints = 0
        for (p in pointsW) {
            if (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite()) continue
            val along = corridor.alongM(p)
            if (along < 0f || along >= config.policy.stopM || abs(corridor.lateralM(p)) > config.corridor.widthM / 2 ||
                p.y - corridor.floorY < cfg.minAboveFloorM || p.y - corridor.floorY > config.corridor.heightM) continue
            roiPoints++
            val key = Triple(floor(p.x / voxelM).toInt(), floor(p.y / voxelM).toInt(), floor(p.z / voxelM).toInt())
            unique.putIfAbsent(key, p)
        }
        val pts = unique.values.toList()
        val groups = Cluster.dbscanXZ(pts, config.cluster.epsM, config.cluster.minSamples)
        val candidates = ArrayList<Candidate>()
        val used = HashSet<Int>()
        var sizeCandidates = 0
        for (indices in groups) {
            if (indices.size < cfg.minVoxels && observeGroup == null) continue
            val group = indices.map { pts[it] }
            val height = group.maxOf { it.y } - group.minOf { it.y }
            if (height < cfg.minHeightSpanM && observeGroup == null) continue
            val laterals = group.map { corridor.lateralM(it) }
            val width = laterals.max() - laterals.min()
            val gate = when {
                indices.size < cfg.minVoxels -> "cells"
                height < cfg.minHeightSpanM -> "height"
                width < cfg.confirmMinLateralSpanM -> "width"
                else -> "pass"
            }
            if (gate != "pass") {
                observeGroup?.invoke(indices.size, height, width, gate)
                continue
            }
            val fullWidth = width >= cfg.minLateralSpanM
            val reps = RepPoint.candidates(group, corridor, voxelM, config.repPoint.bandM, config.repPoint.stableTies)
            val rep = reps.getValue(config.repPoint.strategy)
            val old = previous.filter { it.obstacle.id !in used && (fullWidth || it.fullWidth) &&
                (it.obstacle.repPointW - rep).norm() <= config.track.matchRadiusM }
                .minByOrNull { (it.obstacle.repPointW - rep).norm() }?.obstacle
            observeMatch?.let { observer ->
                val nearest = previous.minByOrNull { (it.obstacle.repPointW - rep).norm() }?.obstacle
                observer(tNs, rep, nearest, old, config.track.matchRadiusM)
            }
            if (!fullWidth && old == null) {
                observeGroup?.invoke(indices.size, height, width, "width")
                continue
            }
            sizeCandidates++
            observeGroup?.invoke(indices.size, height, width, if (fullWidth) "pass" else "matched_width")
            val id = old?.id ?: nextId--
            used += id
            val observations = ((old?.nObservations ?: 0) + 1).coerceAtMost(cfg.confirmFrames)
            val obstacle = Obstacle(id, rep, reps,
                Vec3(group.minOf { it.x }, group.minOf { it.y }, group.minOf { it.z }),
                Vec3(group.maxOf { it.x }, group.maxOf { it.y }, group.maxOf { it.z }),
                HeightClassifier.classify(group.map { it.y - corridor.floorY }, config.cluster) ?: continue,
                true, observations.toFloat() / cfg.confirmFrames, tNs, observations, earlyStop = true)
            candidates += Candidate(obstacle, fullWidth)
        }
        previous = candidates
        val published = candidates.map { it.obstacle }.filter { early ->
            early.nObservations >= cfg.confirmFrames && confirmed.none { normal ->
                corridor.alongM(normal.repPointW) >= 0f && corridor.alongM(normal.repPointW) < config.policy.stopM &&
                    abs(corridor.lateralM(normal.repPointW)) <= config.corridor.widthM / 2 && overlap(early, normal, voxelM)
            }
        }
        observeFrame?.invoke(FrameEvidence(roiPoints, unique.size, sizeCandidates,
            candidates.maxOfOrNull { it.obstacle.nObservations } ?: 0, published.size))
        return published
    }

    private fun overlap(a: Obstacle, b: Obstacle, marginM: Float) =
        a.aabbMinW.x <= b.aabbMaxW.x + marginM && a.aabbMaxW.x >= b.aabbMinW.x - marginM &&
        a.aabbMinW.y <= b.aabbMaxW.y + marginM && a.aabbMaxW.y >= b.aabbMinW.y - marginM &&
        a.aabbMinW.z <= b.aabbMaxW.z + marginM && a.aabbMaxW.z >= b.aabbMinW.z - marginM

    fun reset() { previous = emptyList(); lastTNs = null; nextId = -1 }
}
