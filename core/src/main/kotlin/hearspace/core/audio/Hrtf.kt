package hearspace.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * 수평면(고도 0°) HRIR 세트 (§7.6). `tools/analysis/extract_hrir.py`가 만든 바이너리를 읽는다.
 * 방위각은 명세 규약(오른쪽 +, −180~180, 오름차순). 사이 방위각은 인접 두 HRIR을 선형 보간한다.
 */
class Hrtf private constructor(
    val sampleRate: Int,
    val taps: Int,
    private val azimuthsDeg: FloatArray,
    private val left: Array<FloatArray>,
    private val right: Array<FloatArray>,
) {
    /** 측정 방위각 수. */
    val count: Int get() = azimuthsDeg.size

    /** [azimuthDeg]의 좌우 HRIR을 [outLeft]·[outRight](길이 [taps])에 쓴다. 범위 밖은 −180~180으로 감는다. */
    fun hrir(azimuthDeg: Float, outLeft: FloatArray, outRight: FloatArray) {
        val a = wrap(azimuthDeg)
        val n = azimuthsDeg.size
        // a 이하의 마지막 측정점(없으면 마지막 점, 360° 감기)
        var lo = upperBound(a) - 1
        val hi: Int
        val span: Float
        val t: Float
        if (lo < 0) {
            lo = n - 1
            hi = 0
            span = azimuthsDeg[0] + 360f - azimuthsDeg[n - 1]
            t = (a + 360f - azimuthsDeg[n - 1]) / span
        } else if (lo == n - 1) {
            hi = 0
            span = azimuthsDeg[0] + 360f - azimuthsDeg[n - 1]
            t = (a - azimuthsDeg[n - 1]) / span
        } else {
            hi = lo + 1
            span = azimuthsDeg[hi] - azimuthsDeg[lo]
            t = (a - azimuthsDeg[lo]) / span
        }
        val l0 = left[lo]; val l1 = left[hi]; val r0 = right[lo]; val r1 = right[hi]
        for (i in 0 until taps) {
            outLeft[i] = l0[i] + (l1[i] - l0[i]) * t
            outRight[i] = r0[i] + (r1[i] - r0[i]) * t
        }
    }

    private fun upperBound(a: Float): Int {
        var lo = 0
        var hi = azimuthsDeg.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (azimuthsDeg[mid] <= a) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val MAGIC = 0x52484157 // "WAHR" 리틀 엔디언

        /** 바이너리를 읽는다. 형식이 다르면 [IllegalArgumentException]. */
        fun parse(bytes: ByteArray): Hrtf {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 20 && b.getInt(0) == MAGIC) { "not a WAHR HRIR file" }
            b.position(4)
            val version = b.int
            require(version == 1) { "unsupported HRIR version $version" }
            val sr = b.int
            val taps = b.int
            val count = b.int
            require(taps > 0 && count > 1 && bytes.size == 20 + count * 4 * (1 + 2 * taps)) { "HRIR size mismatch" }
            val az = FloatArray(count)
            val l = Array(count) { FloatArray(taps) }
            val r = Array(count) { FloatArray(taps) }
            for (i in 0 until count) {
                az[i] = b.float
                for (k in 0 until taps) l[i][k] = b.float
                for (k in 0 until taps) r[i][k] = b.float
            }
            for (i in 1 until count) require(az[i] > az[i - 1]) { "azimuths must be ascending" }
            return Hrtf(sr, taps, az, l, r)
        }

        /** −180 이상 180 미만으로. */
        fun wrap(deg: Float): Float = deg - 360f * floor((deg + 180f) / 360f)
    }
}
