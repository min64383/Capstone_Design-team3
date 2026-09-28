package walkassist.core.audio

import walkassist.core.geometry.HeadPose
import walkassist.core.geometry.Vec3
import walkassist.core.geometry.headRelative

/**
 * 3D 맵으로부터 전달받은 하나의 음원 위치.
 *
 * 좌표는 월드 좌표계 W이며 단위는 meter이다.
 */
data class SpatialAudioPoint(
    val obstacleId: Int,
    val positionW: Vec3,
    val tCaptureNs: Long,
)

/**
 * 월드 좌표의 음원을 사용자의 머리 기준으로 변환한 결과.
 */
data class SpatialAudioTarget(
    val obstacleId: Int,

    /** 머리 정면 기준 방위각. 오른쪽이 양수, 단위 degree. */
    val azimuthDeg: Float,

    /** 머리에서 장애물까지의 수평 거리, 단위 meter. */
    val distanceM: Float,

    /** 머리와 장애물 사이의 높이 차이, 단위 meter. */
    val heightDeltaM: Float,

    /** 원본 위치 정보가 측정된 시각. */
    val tCaptureNs: Long,
)

/**
 * 3D 월드 좌표를 공간 음향 모듈에서 사용할 입력으로 변환한다.
 */
object SpatialAudioInput {

    /**
     * x, y, z 월드 좌표를 하나의 공간 음향 입력으로 만든다.
     */
    fun fromWorldXyz(
        obstacleId: Int,
        xM: Float,
        yM: Float,
        zM: Float,
        tCaptureNs: Long,
    ): SpatialAudioPoint {

        require(xM.isFinite()) {
            "xM must be finite: $xM"
        }

        require(yM.isFinite()) {
            "yM must be finite: $yM"
        }

        require(zM.isFinite()) {
            "zM must be finite: $zM"
        }

        return SpatialAudioPoint(
            obstacleId = obstacleId,
            positionW = Vec3(
                x = xM,
                y = yM,
                z = zM,
            ),
            tCaptureNs = tCaptureNs,
        )
    }

    /**
     * 월드 좌표 음원을 현재 사용자의 머리 기준 방위각과 거리로 변환한다.
     */
    fun toHeadTarget(
        source: SpatialAudioPoint,
        headPose: HeadPose,
    ): SpatialAudioTarget {

        val relative = headRelative(
            pW = source.positionW,
            head = headPose,
        )

        val heightDeltaM =
            source.positionW.y - headPose.positionW.y

        return SpatialAudioTarget(
            obstacleId = source.obstacleId,
            azimuthDeg = relative.azimuthDeg,
            distanceM = relative.horizontalDistM,
            heightDeltaM = heightDeltaM,
            tCaptureNs = source.tCaptureNs,
        )
    }

    /**
     * x, y, z 좌표를 받아 바로 머리 기준 공간 음향 위치로 변환한다.
     */
    fun mapWorldXyz(
        obstacleId: Int,
        xM: Float,
        yM: Float,
        zM: Float,
        tCaptureNs: Long,
        headPose: HeadPose,
    ): SpatialAudioTarget {

        val source = fromWorldXyz(
            obstacleId = obstacleId,
            xM = xM,
            yM = yM,
            zM = zM,
            tCaptureNs = tCaptureNs,
        )

        return toHeadTarget(
            source = source,
            headPose = headPose,
        )
    }
}