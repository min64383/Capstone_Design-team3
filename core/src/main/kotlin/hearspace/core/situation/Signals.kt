package hearspace.core.situation

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.types.Intrinsics
import hearspace.core.types.SignalConfig
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 신호 판단에 넣는 컬러 영상 한 장(센서 방향). [rgb](PC 재생: 행 우선 RGB 바이트 3개씩)와 [nv21](앱: CPU 영상 NV21, 가로세로 짝수) 중 하나만 준다.
 * [K]는 이 영상 크기의 내부 파라미터, [worldFromCam]은 촬영 시각의 C_cv → W 자세. [tracking]이 false면(추적 잃음) 자세를 믿지 않는다.
 * NV21이면 RGB는 색차가 켜진 등이 될 수 있는 2×2 칸에서만 [SurfaceIO.nv21ToRgb]와 같은 식으로 계산하고, 꺼진 칸 밝기는 Y 평면에서 읽는다.
 */
class ColorImage(
    val tCaptureNs: Long, val K: Intrinsics, val worldFromCam: Mat4, val tracking: Boolean = true,
    val rgb: ByteArray? = null, val nv21: ByteArray? = null,
) {
    init {
        require((rgb == null) != (nv21 == null)) { "give rgb or nv21" }
        if (rgb != null) require(rgb.size == K.width * K.height * 3) { "rgb size ${rgb.size} != ${K.width}x${K.height}x3" }
        if (nv21 != null) require(K.width % 2 == 0 && K.height % 2 == 0 && nv21.size >= K.width * K.height * 3 / 2) { "nv21 ${K.width}x${K.height}" }
    }
}

/** 신호 말 하나. [untilNs]까지만 낸다(“초록불로 바뀜”은 전환 뒤 3 s). [evidenceNs]는 근거 관측의 촬영 시각. */
data class SignalSpeech(val key: String, val text: String, val priority: Int, val evidenceNs: Long, val untilNs: Long)

/** 켜진 등 덩어리 하나의 잰 값(기록·보정용). 각은 °, 크기는 화소(초점거리 × 각). [pass]는 모양 조건을 모두 넘었는지. */
data class SignalBlob(
    val tNs: Long, val red: Boolean, val nPx: Int, val azDeg: Float, val elDeg: Float, val heightPx: Float, val widthPx: Float,
    val darkLuma: Float, val pass: Boolean, val track: Int,
)

/** 한 장의 track 상태(기록용). obs: R 빨강, G 초록, B 둘 다, . 없음. */
data class SignalTrackRecord(val id: Int, val azDeg: Float, val elDeg: Float, val obs: Char, val redConfirmed: Boolean, val transitionNs: Long?)

/** 한 장 처리 결과. [speech]는 다음 장까지 상황 판단이 읽는 최신 값이다(최신 값 교체). */
class SignalOutput(val tNs: Long, val still: Boolean, val speech: List<SignalSpeech>, val blobs: List<SignalBlob>, val tracks: List<SignalTrackRecord>)

/**
 * 보행 신호 판단(M71 설계 3.3·3.4절). RGB 한 장마다:
 * 1. 높이 띠: 월드 광선이 수평 위 [SignalConfig.bandMinDeg]~[SignalConfig.bandMaxDeg], 카메라 정면 ± [SignalConfig.bandHalfWidthDeg]인 화소만.
 * 2. 켜진 등: 밝고 채도 높은 빨강 또는 청록 화소의 4-연결 덩어리.
 * 3. 사람 꼴: 덩어리의 세로 ÷ 가로(월드 수직·수평 각으로 잼) ≥ 하한, 폭이 [SignalConfig.minWidthPx]보다 작으면 버림.
 * 4. 세로 짝: 빨강이면 한 칸 아래, 초록이면 한 칸 위(칸 간격 = 덩어리 높이 × [SignalConfig.pairRatio])의 평균 밝기가 어두움.
 * 5. track: 등틀 가운데(빨강은 반 칸 아래, 초록은 반 칸 위) 월드 방향이 [SignalConfig.trackGateDeg] 안이면 같은 신호.
 * 상태는 사용자가 멈춰 있을 때만 쌓는다(걷기 시작하면 track을 비움). 빨강 확인 → 전환(초록 첫 장)을 본 track만 “초록불로 바뀜”을 낸다.
 * LED가 꺼져 찍히거나 색이 날아가면 말을 놓칠 뿐 전환을 만들지 않는다(전환에는 확인된 빨강 바로 아래 칸의 초록 사람 꼴이 필요).
 */
class Signals(private val cfg: SignalConfig) {
    private class Obs(val tNs: Long, val red: Boolean, val green: Boolean)

    private class Track(val id: Int, var dir: Vec3, val bornNs: Long) {
        var lastLitNs = bornNs
        val history = ArrayDeque<Obs>()
        var lastRedNs = Long.MIN_VALUE
        var lastGreenNs = Long.MIN_VALUE
        var confirmedNow = false
        var lastConfirmedNs = Long.MIN_VALUE
        /** 전환 후보: 초록 첫 장 시각과 그 뒤 초록 장 수. */
        var greenStartNs = Long.MIN_VALUE
        var greenCount = 0
        var transitionNs = Long.MIN_VALUE
        var transitions = 0
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 0
    private val positions = ArrayDeque<Pair<Long, Vec3>>()
    private var mask = ByteArray(0)
    private var stack = IntArray(0)
    /** 이번 장에 표시한 켜진 화소 번호(덩어리 씨앗과 다음 장 지우기에 씀, 영상 전체를 다시 훑지 않게). */
    private var lit = IntArray(0)
    private var nLit = 0

    fun reset() {
        tracks.clear()
        positions.clear()
    }

    /** 한 장을 처리한다. 과거 장만 차례로 넣는다. */
    fun process(img: ColorImage): SignalOutput {
        val t = img.tCaptureNs
        val still = img.tracking && updateStill(t, img.worldFromCam.translation())
        if (!still) {
            tracks.clear()
            return SignalOutput(t, false, emptyList(), emptyList(), emptyList())
        }
        val blobs = detect(img)
        val red = HashSet<Int>()
        val green = HashSet<Int>()
        val out = ArrayList<SignalBlob>(blobs.size)
        for (b in blobs) {
            if (!b.pass) {
                out.add(b.record(-1))
                continue
            }
            val tr = associate(b.center, t)
            if (b.red) red.add(tr.id) else green.add(tr.id)
            out.add(b.record(tr.id))
        }
        val keepNs = (cfg.trackKeepS * 1e9).toLong()
        tracks.removeAll { t - it.lastLitNs > keepNs }
        val recs = ArrayList<SignalTrackRecord>()
        for (tr in tracks) {
            update(tr, Obs(t, tr.id in red, tr.id in green))
            val o = when {
                tr.id in red && tr.id in green -> 'B'
                tr.id in red -> 'R'
                tr.id in green -> 'G'
                else -> '.'
            }
            recs.add(SignalTrackRecord(tr.id, azDeg(tr.dir), elDeg(tr.dir), o, tr.confirmedNow, tr.transitionNs.takeIf { it != Long.MIN_VALUE }))
        }
        return SignalOutput(t, true, speech(), out, recs)
    }

    private fun speech(): List<SignalSpeech> {
        val s = ArrayList<SignalSpeech>()
        val red = tracks.filter { it.confirmedNow }.maxByOrNull { it.lastRedNs }
        if (red != null) s.add(SignalSpeech(RED_KEY, RED_TEXT, 3, red.lastRedNs, Long.MAX_VALUE))
        val withinNs = (cfg.speakWithinS * 1e9).toLong()
        for (tr in tracks) {
            if (tr.transitionNs == Long.MIN_VALUE) continue
            s.add(SignalSpeech("$GREEN_KEY#${tr.id}.${tr.transitions}", GREEN_TEXT, 2, tr.lastGreenNs, tr.transitionNs + withinNs))
        }
        return s
    }

    /** 멈춤: 지난 [SignalConfig.stillS] 동안 지금 자리에서 수평 [SignalConfig.stillSpeedMps] × [SignalConfig.stillS] 안. 기록이 그만큼 쌓여야 한다. */
    private fun updateStill(tNs: Long, p: Vec3): Boolean {
        positions.addLast(tNs to p)
        val windowNs = (cfg.stillS * 1e9).toLong()
        while (positions.size > 1 && positions[1].first <= tNs - windowNs) positions.removeFirst()
        if (positions.first().first > tNs - windowNs) return false
        val r = cfg.stillSpeedMps * cfg.stillS
        return positions.all { (it.second - p).horizontal().norm() < r }
    }

    private fun associate(center: Vec3, tNs: Long): Track {
        val gate = Math.toRadians(cfg.trackGateDeg.toDouble())
        val best = tracks.minByOrNull { angle(it.dir, center) }
        val tr = if (best != null && angle(best.dir, center) <= gate) best else Track(nextId++, center, tNs).also { tracks.add(it) }
        tr.dir = center
        tr.lastLitNs = tNs
        return tr
    }

    private fun update(tr: Track, o: Obs) {
        val t = o.tNs
        tr.history.addLast(o)
        val confirmNs = (cfg.confirmS * 1e9).toLong()
        while (tr.history.isNotEmpty() && tr.history.first().tNs <= t - confirmNs) tr.history.removeFirst()
        if (o.red) tr.lastRedNs = t
        if (o.green) tr.lastGreenNs = t
        val n = tr.history.size
        val reds = tr.history.count { it.red }
        tr.confirmedNow = tr.bornNs <= t - confirmNs && tr.history.none { it.green } && reds > cfg.redFraction * n
        if (tr.confirmedNow) tr.lastConfirmedNs = t
        // 전환 후보: 확인된 빨강 뒤 [switchGapS] 안에 나타난 초록. 그동안 빨강이 다시 보이면 버린다
        val gapNs = (cfg.switchGapS * 1e9).toLong()
        val windowNs = (cfg.greenWindowS * 1e9).toLong()
        if (o.red) {
            tr.greenStartNs = Long.MIN_VALUE
            tr.greenCount = 0
            return
        }
        if (tr.greenStartNs != Long.MIN_VALUE && t - tr.greenStartNs > windowNs) {
            tr.greenStartNs = Long.MIN_VALUE
            tr.greenCount = 0
        }
        if (!o.green) return
        if (tr.greenStartNs == Long.MIN_VALUE) {
            if (tr.lastConfirmedNs == Long.MIN_VALUE || tr.lastRedNs == Long.MIN_VALUE || t - tr.lastConfirmedNs > gapNs || t - tr.lastRedNs > gapNs) return
            tr.greenStartNs = t
            tr.greenCount = 0
        }
        tr.greenCount++
        if (tr.greenCount >= cfg.greenFrames) {
            tr.transitionNs = tr.greenStartNs
            tr.transitions++
            tr.lastConfirmedNs = Long.MIN_VALUE // 다음 전환에는 새 빨강 확인이 필요하다
            tr.greenStartNs = Long.MIN_VALUE
            tr.greenCount = 0
        }
    }

    private class Blob(
        val tNs: Long, val red: Boolean, val nPx: Int, val dir: Vec3, val heightPx: Float, val widthPx: Float, val darkLuma: Float,
        val pass: Boolean, val center: Vec3,
    ) {
        fun record(track: Int) = SignalBlob(tNs, red, nPx, azDeg(dir), elDeg(dir), heightPx, widthPx, darkLuma, pass, track)
    }

    /** 띠 안의 켜진 등 덩어리를 찾아 모양 조건을 잰다. */
    private fun detect(img: ColorImage): List<Blob> {
        val k = img.K
        val w = k.width
        val h = k.height
        if (mask.size != w * h) {
            mask = ByteArray(w * h)
            stack = IntArray(w * h)
            lit = IntArray(w * h)
        }
        for (q in 0 until nLit) mask[lit[q]] = 0
        nLit = 0
        val m = img.worldFromCam
        val fwd = m.axis(2).horizontal()
        if (fwd.norm() < 1e-3f) return emptyList()
        val fwdN = fwd.normalized()
        val sinMin = sin(Math.toRadians(cfg.bandMinDeg.toDouble())).toFloat()
        val sinMax = sin(Math.toRadians(cfg.bandMaxDeg.toDouble())).toFloat()
        val cosHalf = cos(Math.toRadians(cfg.bandHalfWidthDeg.toDouble())).toFloat()
        val satMin = cfg.minSaturation * 255f
        val valMin = cfg.minValue * 255f
        /** 1: 켜진 화소만 높이 띠를 본다(광선 계산은 비싸다). */
        fun mark(u: Int, v: Int, cls: Int) {
            val d = ray(m, k, u, v)
            if (d.y < sinMin || d.y > sinMax) return
            val hz = d.horizontal()
            val hn = hz.norm()
            if (hn < 1e-6f || (hz dot fwdN) / hn < cosHalf) return
            val i = v * w + u
            mask[i] = cls.toByte()
            lit[nLit++] = i
        }
        val rgb = img.rgb
        val nv21 = img.nv21
        if (rgb != null) {
            // 2: 색으로 먼저 거른다
            for (v in 0 until h) for (u in 0 until w) {
                val j = (v * w + u) * 3
                val cls = litClass(rgb[j].toInt() and 0xFF, rgb[j + 1].toInt() and 0xFF, rgb[j + 2].toInt() and 0xFF, satMin, valMin)
                if (cls != 0) mark(u, v, cls)
            }
        } else if (nv21 != null) {
            // 2(앱): 2×2 칸의 색차로 먼저 거른다. BT.601 식에서 RGB 색폭(최대 − 최소)은 2.116136 × (|Cb| + |Cr|) + 1(반올림)을 넘지 않고,
            // 켜진 등은 색폭이 채도 하한 × 밝기 하한 × 255 이상이므로 걸러진 칸에는 켜진 화소가 없다(RGB 경로와 같은 화소를 받음, 시험으로 확인)
            val minSum = ((cfg.minSaturation * cfg.minValue * 255f - 1f) / MAX_CHROMA_PER_UV).toInt()
            val uv0 = w * h
            for (by in 0 until h / 2) {
                val uvRow = uv0 + by * w
                for (bx in 0 until w / 2) {
                    val cr = (nv21[uvRow + bx * 2].toInt() and 0xFF) - 128
                    val cb = (nv21[uvRow + bx * 2 + 1].toInt() and 0xFF) - 128
                    if (abs(cr) + abs(cb) < minSum) continue
                    for (v in by * 2..by * 2 + 1) for (u in bx * 2..bx * 2 + 1) {
                        val yv = (nv21[v * w + u].toInt() and 0xFF).toFloat()
                        val cls = litClass(to8(yv + 1.402f * cr), to8(yv - 0.344136f * cb - 0.714136f * cr), to8(yv + 1.772f * cb), satMin, valMin)
                        if (cls != 0) mark(u, v, cls)
                    }
                }
            }
        }
        lit.sort(0, nLit) // 덩어리를 줄 순서로 찾는다(RGB·NV21 경로가 같은 씨앗, 같은 결과)
        val out = ArrayList<Blob>()
        for (q in 0 until nLit) {
            val i = lit[q]
            val cls = mask[i].toInt()
            if (cls <= 0) continue
            // 4-연결 덩어리(채운 화소는 음수로 표시)
            var sp = 0
            stack[sp++] = i
            mask[i] = (-cls).toByte()
            var n = 0
            var sx = 0f; var sy = 0f; var sz = 0f
            var elLo = Float.MAX_VALUE; var elHi = -Float.MAX_VALUE
            var azRef = Float.NaN; var azLo = Float.MAX_VALUE; var azHi = -Float.MAX_VALUE
            while (sp > 0) {
                val p = stack[--sp]
                val u = p % w
                val v = p / w
                val d = ray(m, k, u, v)
                n++
                sx += d.x; sy += d.y; sz += d.z
                val el = asin(d.y.coerceIn(-1f, 1f))
                val azRaw = atan2(d.x, -d.z)
                if (azRef.isNaN()) azRef = azRaw
                var az = azRaw - azRef
                if (az > Math.PI) az -= (2 * Math.PI).toFloat() else if (az < -Math.PI) az += (2 * Math.PI).toFloat()
                if (el < elLo) elLo = el
                if (el > elHi) elHi = el
                val azc = az * cos(el)
                if (azc < azLo) azLo = azc
                if (azc > azHi) azHi = azc
                if (u > 0 && mask[p - 1].toInt() == cls) { mask[p - 1] = (-cls).toByte(); stack[sp++] = p - 1 }
                if (u < w - 1 && mask[p + 1].toInt() == cls) { mask[p + 1] = (-cls).toByte(); stack[sp++] = p + 1 }
                if (v > 0 && mask[p - w].toInt() == cls) { mask[p - w] = (-cls).toByte(); stack[sp++] = p - w }
                if (v < h - 1 && mask[p + w].toInt() == cls) { mask[p + w] = (-cls).toByte(); stack[sp++] = p + w }
            }
            if (n < 2) continue
            val f = (k.fx + k.fy) / 2f
            val heightPx = (elHi - elLo) * f + 1f
            val widthPx = (azHi - azLo) * f + 1f
            val center = Vec3(sx, sy, sz).normalized()
            val red = cls == RED
            val cellRad = (heightPx / f) * cfg.pairRatio
            // 4: 빨강은 한 칸 아래, 초록은 한 칸 위가 꺼진 등이어야 한다
            val pairDir = tilt(center, if (red) -cellRad else cellRad)
            val dark = meanLuma(img, pairDir, heightPx, widthPx)
            val shape = widthPx >= cfg.minWidthPx && heightPx / widthPx >= cfg.minAspect
            val pass = shape && !dark.isNaN() && dark <= cfg.darkMaxLuma
            // 5: 등틀 가운데 = 반 칸 아래(빨강) / 위(초록)
            val housing = tilt(center, if (red) -cellRad / 2f else cellRad / 2f)
            out.add(Blob(img.tCaptureNs, red, n, center, heightPx, widthPx, dark, pass, housing))
        }
        return out
    }

    /** 방향 [d]의 화소 둘레(덩어리 크기 상자)의 평균 밝기. 카메라 뒤이거나 영상 밖으로 나가면 NaN(잴 수 없음 → 버림). */
    private fun meanLuma(img: ColorImage, d: Vec3, heightPx: Float, widthPx: Float): Float {
        val k = img.K
        val c = img.worldFromCam.rigidInverse().transformDir(d)
        if (c.z <= 1e-6f) return Float.NaN
        val u = k.fx * c.x / c.z + k.cx
        val v = k.fy * c.y / c.z + k.cy
        val half = maxOf(0, (minOf(heightPx, widthPx) / 2f).roundToInt() - 1)
        val u0 = u.toInt() - half
        val v0 = v.toInt() - half
        val u1 = u.toInt() + half
        val v1 = v.toInt() + half
        if (u0 < 0 || v0 < 0 || u1 >= k.width || v1 >= k.height) return Float.NaN
        var s = 0f
        var n = 0
        val rgb = img.rgb
        val nv21 = img.nv21
        for (y in v0..v1) for (x in u0..u1) {
            val p = y * k.width + x
            s += if (nv21 != null) (nv21[p].toInt() and 0xFF).toFloat() else {
                val j = p * 3
                0.299f * (rgb!![j].toInt() and 0xFF) + 0.587f * (rgb[j + 1].toInt() and 0xFF) + 0.114f * (rgb[j + 2].toInt() and 0xFF)
            }
            n++
        }
        return s / n
    }

    /** 0 = 켜진 등 아님, [RED], [GREEN]. HSV(0~255 채도·밝기, 색상 °)로 가른다. */
    private fun litClass(r: Int, g: Int, b: Int, satMin: Float, valMin: Float): Int {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx < valMin) return 0
        val chroma = mx - mn
        if (chroma <= 0 || chroma * 255f < satMin * mx) return 0
        val hue = when (mx) {
            r -> 60f * (g - b) / chroma
            g -> 60f * (b - r) / chroma + 120f
            else -> 60f * (r - g) / chroma + 240f
        }.let { if (it < 0f) it + 360f else it }
        return when {
            hue <= cfg.redHueDeg || hue >= 360f - cfg.redHueDeg -> RED
            hue >= cfg.greenHueMinDeg && hue <= cfg.greenHueMaxDeg -> GREEN
            else -> 0
        }
    }

    companion object {
        /** BT.601 전범위 식에서 |Cb| + |Cr| 하나당 RGB 색폭의 상한(R−G = 0.344136 Cb + 2.116136 Cr 등 세 차의 가장 큰 계수). */
        private const val MAX_CHROMA_PER_UV = 2.116136f

        /** [SurfaceIO.nv21ToRgb]와 같은 반올림·자르기. */
        private fun to8(v: Float): Int = Math.round(v).coerceIn(0, 255)

        const val RED_KEY = "signal:red"
        const val GREEN_KEY = "signal:green"
        const val RED_TEXT = "빨간불"
        const val GREEN_TEXT = "초록불로 바뀜"
        private const val RED = 1
        private const val GREEN = 2

        /** 화소 (u, v) 가운데의 월드 단위 광선(C_cv → W). */
        private fun ray(m: Mat4, k: Intrinsics, u: Int, v: Int): Vec3 =
            m.transformDir(Vec3((u + 0.5f - k.cx) / k.fx, (v + 0.5f - k.cy) / k.fy, 1f)).normalized()

        /** [d]를 수직면 안에서 [rad]만큼 올린(+) / 내린(−) 방향. */
        private fun tilt(d: Vec3, rad: Float): Vec3 {
            val el = asin(d.y.coerceIn(-1f, 1f)) + rad
            val hz = d.horizontal().normalized()
            return Vec3(hz.x * cos(el), sin(el), hz.z * cos(el))
        }

        private fun angle(a: Vec3, b: Vec3): Double = kotlin.math.acos((a dot b).toDouble().coerceIn(-1.0, 1.0))

        fun azDeg(d: Vec3): Float = Math.toDegrees(atan2(d.x, -d.z).toDouble()).toFloat()

        fun elDeg(d: Vec3): Float = Math.toDegrees(asin(d.y.coerceIn(-1f, 1f)).toDouble()).toFloat()
    }
}
