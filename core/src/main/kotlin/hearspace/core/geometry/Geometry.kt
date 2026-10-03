package hearspace.core.geometry

import hearspace.core.types.Intrinsics
import kotlin.math.sqrt

// 기하 (MVP_SPEC §5, §7.1). 좌표계는 월드(W, +Y 위)와 카메라 C_cv(+X 오른쪽, +Y 아래, +Z 앞)만 쓴다.
// ARCore GL 카메라(C_gl)는 입력 변환(glToCv)에서만 등장한다.

/** 3차원 벡터(미터). 좌표계는 사용하는 쪽 이름으로 표시한다(예: `repPointW`). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    /** 합. */
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    /** 차. */
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    /** 스칼라 곱. */
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    /** 부호 반전. */
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    /** 내적. */
    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    /** 외적. */
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    /** 길이. */
    fun norm(): Float = sqrt(this dot this)

    /** 단위 벡터. 길이가 0이면 [IllegalArgumentException]. */
    fun normalized(): Vec3 {
        val n = norm()
        require(n > 0f) { "cannot normalize zero vector" }
        return this * (1f / n)
    }

    /** 수평(XZ) 성분만 남긴 벡터(Y = 0). */
    fun horizontal() = Vec3(x, 0f, z)

    companion object {
        /** 영벡터. */
        val ZERO = Vec3(0f, 0f, 0f)

        /** 월드 위쪽(+Y). */
        val UP = Vec3(0f, 1f, 0f)
    }
}

/**
 * 4×4 강체·동차 변환 행렬. 저장 순서는 **행 우선**(`m[row * 4 + col]`)이다.
 * 이름 규약 `aFromB`: B 좌표의 점을 A 좌표로 옮긴다(예: `worldFromCam`).
 */
class Mat4(values: FloatArray) {
    private val m: FloatArray = values.copyOf()

    init {
        require(values.size == 16) { "Mat4 needs 16 values, got ${values.size}" }
    }

    /** (row, col) 원소. */
    operator fun get(row: Int, col: Int): Float = m[row * 4 + col]

    /** 행 우선 16개 값의 복사본. */
    fun toRowMajorArray(): FloatArray = m.copyOf()

    /** 행렬 곱 `this · o`. */
    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (i in 0..3) for (j in 0..3) {
            var s = 0f
            for (k in 0..3) s += m[i * 4 + k] * o.m[k * 4 + j]
            r[i * 4 + j] = s
        }
        return Mat4(r)
    }

    /** 점 변환(평행이동 포함). 강체·아핀 변환만 가정한다(마지막 행 0 0 0 1). */
    fun transformPoint(p: Vec3) = Vec3(
        m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3],
        m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7],
        m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11],
    )

    /** 방향 변환(평행이동 제외). */
    fun transformDir(d: Vec3) = Vec3(
        m[0] * d.x + m[1] * d.y + m[2] * d.z,
        m[4] * d.x + m[5] * d.y + m[6] * d.z,
        m[8] * d.x + m[9] * d.y + m[10] * d.z,
    )

    /** 평행이동 성분. `worldFromCam`이면 카메라 위치(월드). */
    fun translation() = Vec3(m[3], m[7], m[11])

    /** j번째 열의 회전 성분(축 방향). `worldFromCam.axis(2)`는 카메라 +Z축의 월드 방향. */
    fun axis(j: Int): Vec3 {
        require(j in 0..2) { "axis index $j" }
        return Vec3(m[j], m[4 + j], m[8 + j])
    }

    /** 강체 변환의 역변환(R^T, −R^T·t). 회전 부분이 정규직교라고 가정한다. */
    fun rigidInverse(): Mat4 {
        val r = FloatArray(16)
        for (i in 0..2) for (j in 0..2) r[i * 4 + j] = m[j * 4 + i]
        for (i in 0..2) r[i * 4 + 3] = -(r[i * 4] * m[3] + r[i * 4 + 1] * m[7] + r[i * 4 + 2] * m[11])
        r[15] = 1f
        return Mat4(r)
    }

    override fun equals(other: Any?): Boolean = other is Mat4 && m.contentEquals(other.m)

    override fun hashCode(): Int = m.contentHashCode()

    override fun toString(): String = "Mat4(${m.joinToString()})"

    companion object {
        /** 단위 행렬. */
        val IDENTITY = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))

        /** 3×3 회전(행 우선 9개) + 평행이동으로 만든다. */
        fun fromRotationTranslation(r: FloatArray, t: Vec3): Mat4 {
            require(r.size == 9) { "rotation needs 9 values" }
            return Mat4(
                floatArrayOf(
                    r[0], r[1], r[2], t.x,
                    r[3], r[4], r[5], t.y,
                    r[6], r[7], r[8], t.z,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        /** 열벡터(축 방향)로 회전을 만든다: `x`, `y`, `z`가 각각 새 좌표계 축의 월드 방향. */
        fun fromAxes(x: Vec3, y: Vec3, z: Vec3, t: Vec3): Mat4 =
            fromRotationTranslation(floatArrayOf(x.x, y.x, z.x, x.y, y.y, z.y, x.z, y.z, z.z), t)
    }
}

/** 규약 변환 (§5). */
object Conventions {
    /** diag(1, −1, −1, 1): C_cv ↔ C_gl. 자기 자신이 역행렬이다. */
    val GL_CV_FLIP = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))

    /** `T_world_from_cv = T_world_from_gl · diag(1, −1, −1, 1)`. */
    fun glToCv(worldFromGl: Mat4): Mat4 = worldFromGl * GL_CV_FLIP

    /** [glToCv]의 역: `T_world_from_gl = T_world_from_cv · diag(1, −1, −1, 1)`. */
    fun cvToGl(worldFromCv: Mat4): Mat4 = worldFromCv * GL_CV_FLIP
}

/** 핀홀 투영·역투영 (C_cv 규약, §7.1). */
object Projection {
    /** 픽셀 (u, v)와 깊이 [depthM]를 C_cv 점으로. `X = (u − cx)·d / fx`, `Y = (v − cy)·d / fy`, `Z = d`. */
    fun backproject(u: Float, v: Float, depthM: Float, k: Intrinsics) =
        Vec3((u - k.cx) * depthM / k.fx, (v - k.cy) * depthM / k.fy, depthM)

    /** C_cv 점을 픽셀로. 카메라 뒤(Z ≤ 0)면 null. */
    fun project(pCv: Vec3, k: Intrinsics): Pair<Float, Float>? {
        if (pCv.z <= 0f) return null
        return Pair(k.fx * pCv.x / pCv.z + k.cx, k.fy * pCv.y / pCv.z + k.cy)
    }

    /**
     * 깊이 이미지 전체를 역투영해 월드 점 배열(x, y, z 교차, 길이 3N)로 돌려준다.
     * 픽셀은 [subsample] 간격으로 고르고(§7.1, `depth.subsample`), 0(무효) 깊이는 건너뛴다.
     * 픽셀 좌표는 픽셀 중심이 정수인 규약(ARCore 내부 파라미터와 같음)을 쓴다.
     */
    fun backprojectToWorld(depthMm: ShortArray, k: Intrinsics, worldFromCam: Mat4, subsample: Int): FloatArray {
        require(subsample >= 1) { "subsample must be >= 1" }
        require(depthMm.size == k.width * k.height) { "depth size ${depthMm.size} != ${k.width}x${k.height}" }
        val out = FloatArray(3 * ((k.width + subsample - 1) / subsample) * ((k.height + subsample - 1) / subsample))
        var n = 0
        var v = 0
        while (v < k.height) {
            var u = 0
            while (u < k.width) {
                val mm = depthMm[v * k.width + u].toInt() and 0xFFFF
                if (mm != 0) {
                    val p = worldFromCam.transformPoint(backproject(u.toFloat(), v.toFloat(), mm / 1000f, k))
                    out[n++] = p.x
                    out[n++] = p.y
                    out[n++] = p.z
                }
                u += subsample
            }
            v += subsample
        }
        return out.copyOf(n)
    }
}
