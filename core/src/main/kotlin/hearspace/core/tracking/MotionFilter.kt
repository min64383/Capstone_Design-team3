package hearspace.core.tracking

import hearspace.core.geometry.Vec3

/**
 * 월드 수평(x, z) 등속 칼만 필터(M48, IMPROVE_SPEC §13, 설계 3.2절). 축마다 [위치, 속도] 2상태를 따로 둔다.
 * 과정 잡음은 이산 백색 가속 모형(가속 표준편차 [accelSigmaMps2]), 측정은 위치뿐이고 잡음은 측정마다 받는다.
 * 측정 잡음·과정 잡음이 축마다 독립이고 측정 행렬이 단위라 두 축은 섞이지 않는다(4×4 필터와 같은 결과).
 * 시작은 두 점 차분(Bar-Shalom 외 2001): 첫 측정은 기억만 하고, 둘째 측정에서 위치 = 둘째, 속도 = 차분, 공분산은 측정 잡음에서.
 * 검증 게이트(같은 책): 예측에서 벗어난 정도(혁신의 정규화 제곱, 자유도 2)가 문턱을 넘는 측정은 버리고 예측만 한다.
 * 과거 측정만 쓴다(미래 프레임 없음).
 */
class MotionFilter(private val accelSigmaMps2: Float) {

    /** 한 축의 상태: 위치 p, 속도 v, 공분산 [[pp, pv], [pv, vv]]. */
    private class Axis(var p: Double, var v: Double, var pp: Double, var pv: Double, var vv: Double)

    private var first: Triple<Long, Vec3, Double>? = null
    private var x: Axis? = null
    private var z: Axis? = null

    /** 마지막으로 반영한 측정(또는 예측)의 시각. */
    var tNs: Long = Long.MIN_VALUE
        private set

    /** 위치·속도 추정이 있는지(측정 두 번 뒤). */
    val ready: Boolean get() = x != null

    /** 지금까지 반영한 측정 수. */
    var nUpdates: Int = 0
        private set

    /** 마지막으로 버린 측정의 혁신 χ²(기록용). */
    var lastInnovationChi2: Float = 0f
        private set

    /**
     * 시각 [tNs]의 측정 [pW](수평만 씀)와 그 표준편차 [sigmaM]을 반영한다. 같은 시각 이하의 측정은 무시한다.
     * 준비된 뒤 혁신 χ²가 [gateChi2]를 넘으면 측정을 버리고 예측만 한다. 반영했으면 true.
     */
    fun update(tNs: Long, pW: Vec3, sigmaM: Float, gateChi2: Float = Float.POSITIVE_INFINITY): Boolean {
        if (tNs <= this.tNs) return false
        val r = sigmaM.toDouble() * sigmaM
        val f = first
        val ax = x
        if (ax == null) {
            if (f == null) {
                first = Triple(tNs, pW, r)
                this.tNs = tNs
                nUpdates = 1
                return true
            }
            val dt = (tNs - f.first) / 1e9
            // 두 점 차분: 속도 = (z2 − z1)/dt, var(v) = (r1 + r2)/dt², cov(p, v) = r2/dt
            x = Axis(pW.x.toDouble(), (pW.x - f.second.x) / dt, r, r / dt, (r + f.third) / (dt * dt))
            z = Axis(pW.z.toDouble(), (pW.z - f.second.z) / dt, r, r / dt, (r + f.third) / (dt * dt))
            first = null
            this.tNs = tNs
            nUpdates = 2
            return true
        }
        val dt = (tNs - this.tNs) / 1e9
        predictAxis(ax, dt)
        predictAxis(z!!, dt)
        this.tNs = tNs
        val ex = pW.x - ax.p
        val ez = pW.z - z!!.p
        val nis = (ex * ex / (ax.pp + r) + ez * ez / (z!!.pp + r)).toFloat()
        lastInnovationChi2 = nis
        if (nis > gateChi2) return false
        correctAxis(ax, pW.x.toDouble(), r)
        correctAxis(z!!, pW.z.toDouble(), r)
        nUpdates++
        return true
    }

    /** 측정 없이 시각 [tNs]까지 예측한다(놓친 장). */
    fun predictTo(tNs: Long) {
        val ax = x ?: return
        if (tNs <= this.tNs) return
        val dt = (tNs - this.tNs) / 1e9
        predictAxis(ax, dt)
        predictAxis(z!!, dt)
        this.tNs = tNs
    }

    /** 위치 추정(y = 0). 준비 전이면 null. */
    val positionW: Vec3? get() = x?.let { Vec3(it.p.toFloat(), 0f, z!!.p.toFloat()) }

    /** 속도 추정(y = 0). 준비 전이면 null. */
    val velocityW: Vec3? get() = x?.let { Vec3(it.v.toFloat(), 0f, z!!.v.toFloat()) }

    /** 위치 표준편차(두 축 분산 평균의 제곱근, m). */
    val positionSigmaM: Float get() = x?.let { Math.sqrt((it.pp + z!!.pp) / 2).toFloat() } ?: Float.NaN

    /** 속도 표준편차(두 축 분산 평균의 제곱근, m/s). */
    val velocitySigmaMps: Float get() = x?.let { Math.sqrt((it.vv + z!!.vv) / 2).toFloat() } ?: Float.NaN

    /** 속도가 0이라는 가설의 χ²(자유도 2) = vᵀ P_v⁻¹ v. 두 축이 독립이라 각 축 v²/var(v)의 합이다. */
    val velocityChi2: Float get() = x?.let { (it.v * it.v / it.vv + z!!.v * z!!.v / z!!.vv).toFloat() } ?: 0f

    /** 수평 방향 [dirW](단위 벡터) 성분의 위치·속도 분산(축 독립). */
    fun positionVarAlong(dirW: Vec3): Float = x?.let { (dirW.x * dirW.x * it.pp + dirW.z * dirW.z * z!!.pp).toFloat() } ?: Float.NaN
    fun velocityVarAlong(dirW: Vec3): Float = x?.let { (dirW.x * dirW.x * it.vv + dirW.z * dirW.z * z!!.vv).toFloat() } ?: Float.NaN

    private fun predictAxis(a: Axis, dt: Double) {
        val q = accelSigmaMps2.toDouble() * accelSigmaMps2
        // F = [[1, dt], [0, 1]], Q = q [[dt⁴/4, dt³/2], [dt³/2, dt²]](이산 백색 가속)
        a.p += a.v * dt
        val pp = a.pp + 2 * dt * a.pv + dt * dt * a.vv + q * dt * dt * dt * dt / 4
        val pv = a.pv + dt * a.vv + q * dt * dt * dt / 2
        val vv = a.vv + q * dt * dt
        a.pp = pp; a.pv = pv; a.vv = vv
    }

    private fun correctAxis(a: Axis, meas: Double, r: Double) {
        val s = a.pp + r
        val kp = a.pp / s
        val kv = a.pv / s
        val innov = meas - a.p
        a.p += kp * innov
        a.v += kv * innov
        val pp = (1 - kp) * a.pp
        val pv = (1 - kp) * a.pv
        val vv = a.vv - kv * a.pv
        a.pp = pp; a.pv = pv; a.vv = vv
    }
}
