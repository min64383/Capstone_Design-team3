package walkassist.core.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/** 단위 쿼터니언 (x, y, z, w). ARCore Pose와 같은 성분 순서. */
data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) {

    /** 길이 1로 정규화한 쿼터니언. */
    fun normalized(): Quaternion {
        val n = sqrt(x * x + y * y + z * z + w * w)
        require(n > 0f) { "zero quaternion" }
        return Quaternion(x / n, y / n, z / n, w / n)
    }

    /** 회전행렬(행 우선 9개). 입력은 정규화해서 쓴다. */
    fun toRotation(): FloatArray {
        val (qx, qy, qz, qw) = normalized()
        return floatArrayOf(
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
            2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
            2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
        )
    }

    /** 이 회전과 평행이동 [t]로 강체 변환을 만든다. */
    fun toMat4(t: Vec3): Mat4 = Mat4.fromRotationTranslation(toRotation(), t)

    companion object {
        /** 항등 회전. */
        val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

        /** 단위 축 [axis] 둘레로 [angleRad] 회전(오른손 규칙). */
        fun fromAxisAngle(axis: Vec3, angleRad: Float): Quaternion {
            val a = axis.normalized()
            val s = kotlin.math.sin(angleRad / 2)
            return Quaternion(a.x * s, a.y * s, a.z * s, kotlin.math.cos(angleRad / 2))
        }

        /**
         * 회전행렬(행 우선 9개) → 쿼터니언. 대각합이 작을 때도 안정적인 분기(Shepperd 방식).
         * 결과는 w ≥ 0으로 맞춘다(q와 −q는 같은 회전).
         */
        fun fromRotation(r: FloatArray): Quaternion {
            require(r.size == 9) { "rotation needs 9 values" }
            val m00 = r[0]; val m01 = r[1]; val m02 = r[2]
            val m10 = r[3]; val m11 = r[4]; val m12 = r[5]
            val m20 = r[6]; val m21 = r[7]; val m22 = r[8]
            val trace = m00 + m11 + m22
            val q = when {
                trace > 0f -> {
                    val s = sqrt(trace + 1f) * 2f
                    Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
                }
                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1f + m00 - m11 - m22) * 2f
                    Quaternion(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
                }
                m11 > m22 -> {
                    val s = sqrt(1f + m11 - m00 - m22) * 2f
                    Quaternion((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
                }
                else -> {
                    val s = sqrt(1f + m22 - m00 - m11) * 2f
                    Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
                }
            }.normalized()
            return if (q.w < 0f) Quaternion(-q.x, -q.y, -q.z, -q.w) else q
        }

        /** 강체 변환의 회전 부분 → 쿼터니언. */
        fun fromMat4(t: Mat4): Quaternion =
            fromRotation(floatArrayOf(t[0, 0], t[0, 1], t[0, 2], t[1, 0], t[1, 1], t[1, 2], t[2, 0], t[2, 1], t[2, 2]))

        /** 두 쿼터니언이 같은 회전인지(부호 무관) 허용 오차 [eps]로 비교. */
        fun sameRotation(a: Quaternion, b: Quaternion, eps: Float): Boolean {
            val d = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w)
            return d >= 1f - eps
        }
    }
}
