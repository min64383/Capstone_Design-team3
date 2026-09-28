package walkassist.core.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import walkassist.core.geometry.HeadPose
import walkassist.core.geometry.Vec3
import kotlin.math.sqrt

class SpatialAudioInputTest {

    private val headPose = HeadPose(
        positionW = Vec3(
            x = 0f,
            y = 1.5f,
            z = 0f,
        ),
        headingW = Vec3(
            x = 0f,
            y = 0f,
            z = -1f,
        ),
    )

    @Test
    fun `front obstacle becomes zero degree`() {

        val target = SpatialAudioInput.mapWorldXyz(
            obstacleId = 1,
            xM = 0f,
            yM = 1.5f,
            zM = -2f,
            tCaptureNs = 100L,
            headPose = headPose,
        )

        assertEquals(
            0f,
            target.azimuthDeg,
            0.001f,
        )

        assertEquals(
            2f,
            target.distanceM,
            0.001f,
        )
    }

    @Test
    fun `right obstacle becomes positive azimuth`() {

        val target = SpatialAudioInput.mapWorldXyz(
            obstacleId = 2,
            xM = 1f,
            yM = 1.5f,
            zM = -1f,
            tCaptureNs = 200L,
            headPose = headPose,
        )

        assertEquals(
            45f,
            target.azimuthDeg,
            0.001f,
        )

        assertEquals(
            sqrt(2f),
            target.distanceM,
            0.001f,
        )
    }

    @Test
    fun `left obstacle becomes negative azimuth`() {

        val target = SpatialAudioInput.mapWorldXyz(
            obstacleId = 3,
            xM = -1f,
            yM = 1.5f,
            zM = -1f,
            tCaptureNs = 300L,
            headPose = headPose,
        )

        assertEquals(
            -45f,
            target.azimuthDeg,
            0.001f,
        )
    }

    @Test
    fun `height difference is preserved`() {

        val target = SpatialAudioInput.mapWorldXyz(
            obstacleId = 4,
            xM = 0f,
            yM = 0.5f,
            zM = -2f,
            tCaptureNs = 400L,
            headPose = headPose,
        )

        assertEquals(
            0f,
            target.azimuthDeg,
            0.001f,
        )

        assertEquals(
            2f,
            target.distanceM,
            0.001f,
        )

        assertEquals(
            -1f,
            target.heightDeltaM,
            0.001f,
        )
    }

    @Test
    fun `invalid xyz is rejected`() {

        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            SpatialAudioInput.fromWorldXyz(
                obstacleId = 5,
                xM = Float.NaN,
                yM = 0f,
                zM = -1f,
                tCaptureNs = 500L,
            )
        }
    }
}