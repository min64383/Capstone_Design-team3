package walkassist.core.geometry

// M0: 데이터 계약(Types.kt)에 필요한 최소 타입만 둔다. 연산·역투영·규약 변환은 M2에서 구현한다.

/** 3차원 벡터(미터). 좌표계는 사용하는 쪽 이름으로 표시한다(예: `repPointW`). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    companion object {
        /** 영벡터. */
        val ZERO = Vec3(0f, 0f, 0f)
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

    override fun equals(other: Any?): Boolean = other is Mat4 && m.contentEquals(other.m)

    override fun hashCode(): Int = m.contentHashCode()

    override fun toString(): String = "Mat4(${m.joinToString()})"

    companion object {
        /** 단위 행렬. */
        val IDENTITY = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
    }
}
