package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.types.Config
import hearspace.core.types.DepthSource

// 요소 분해 단계(M43에서 SlowPath에서 옮김, 동작 같음). 바닥부터 요소 분해(M35)가 이 자리에 들어온다.

/**
 * TRISTATE(M32): 지도에는 바닥이 남으므로, 안내로 넘기기 전에 바닥 추정값 + max(τ, `map.sigmaMultiple` × σ(거리)) 이하의 칸(바닥 아래 포함)을
 * 뺀다. 거리는 지금 카메라 [cameraW]에서 칸 중심까지(깊이 Z보다 크거나 같아 띠가 넓은 쪽). 요소 분해(M35)가 들어오기 전까지의 임시 규칙이다.
 */
fun withoutFloorBand(occupied: List<VoxelView>, floorY: Float, cameraW: Vec3, config: Config): List<VoxelView> {
    val raw = config.depth.source == DepthSource.RAW
    val tau = config.map.voxelSizeM * kotlin.math.sqrt(3f) / 2f
    return occupied.filter {
        val band = kotlin.math.max(tau, config.map.sigmaMultiple * config.depth.sigmaM(raw, (it.centerW - cameraW).norm()))
        it.centerW.y - floorY > band
    }
}
