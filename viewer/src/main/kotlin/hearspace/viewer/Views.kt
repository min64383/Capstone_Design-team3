package hearspace.viewer

import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.session.Png16
import hearspace.core.session.SessionReader
import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.HeightClass
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 화면들이 함께 보는 상태. Swing 이벤트 스레드에서만 바꾸고 읽는다. */
class ViewerModel {
    var result: ReplayResult? = null
        private set
    var tNs: Long = 0
        private set
    var showDepth = true
    /** 위에서 본 그림에 그릴 복셀 높이. 높이를 모두 겹치면 벽이 실제보다 두꺼워 보인다(M13: 한 층은 1~2칸). */
    var voxelHeight = VoxelHeight.ALL
    /** 지금 쓰고 있는 평가의 시점 메모(타임라인에 표시). */
    val notes = mutableListOf<Note>()
    private val listeners = mutableListOf<() -> Unit>()

    fun onChange(l: () -> Unit) { listeners += l }

    /** 결과를 바꾼다. 같은 세션을 다시 돌린 경우 보던 시각을 유지한다. */
    fun setResult(r: ReplayResult) {
        val same = result?.session == r.session
        result = r
        if (!same) { tNs = r.t0Ns; notes.clear() }
        fire()
    }

    fun setTime(t: Long) { tNs = t; fire() }
    fun fire() = listeners.forEach { it() }
}

/** 위에서 본 그림의 복셀 높이 범위(바닥 기준 m, 아래 끝 포함·위 끝 제외). 통로는 설정의 `corridor.heightM`까지. */
enum class VoxelHeight(private val label: String, private val loM: Float, private val hiM: Float) {
    ALL("복셀 높이: 전체", Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY),
    CORRIDOR("통로 높이(설정)", 0f, Float.NaN),
    H0("높이 0~0.5 m", 0f, 0.5f),
    H1("높이 0.5~1.0 m", 0.5f, 1f),
    H2("높이 1.0~1.5 m", 1f, 1.5f),
    H3("높이 1.5~2.0 m", 1.5f, 2f),
    H4("높이 2.0 m 이상", 2f, Float.POSITIVE_INFINITY);

    fun contains(yM: Float, corridorHeightM: Float) = yM >= loM && yM < (if (hiM.isNaN()) corridorHeightM else hiM)
    override fun toString() = label
}

internal object Palette {
    val bg = Color(24, 24, 28)
    val grid = Color(60, 60, 68)
    val text = Color(220, 220, 225)
    val truth = Color(80, 220, 120)
    val source = Color(255, 230, 80)
    val free = Color(150, 150, 170)
    val phantom = Color(230, 90, 90)
    fun height(h: HeightClass): Color = when (h) {
        HeightClass.FLOOR -> Color(255, 160, 40)
        HeightClass.BODY -> Color(60, 200, 255)
        HeightClass.HEAD -> Color(240, 80, 240)
    }
    fun band(b: Band?): Color = when (b) {
        Band.STOP -> Color(255, 70, 70)
        Band.WARN -> Color(255, 170, 40)
        Band.SILENT, null -> Color(150, 150, 150)
    }
    fun state(s: GuidanceState): Color = when (s) {
        GuidanceState.NORMAL -> Color(70, 170, 90)
        GuidanceState.DEGRADED -> Color(170, 160, 60)
        GuidanceState.UNKNOWN -> Color(200, 70, 70)
        GuidanceState.PAUSED -> Color(110, 110, 120)
    }
}

private fun Graphics2D.smooth() = setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

private fun boxCorners(min: Vec3, max: Vec3): List<Vec3> =
    listOf(min.x, max.x).flatMap { x -> listOf(min.y, max.y).flatMap { y -> listOf(min.z, max.z).map { z -> Vec3(x, y, z) } } }

private val BOX_EDGES = listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)

/**
 * 영상: RGB(없으면 검은 바탕) + 깊이 겹침 + 물체·정답 상자 투영. 저장된 영상은 센서 방향이라(세로 파지 시 90° 돌아간 가로 영상,
 * docs/FORMAT.md F3) 센서 좌표로 그린 뒤 시계 방향 90° 돌려 보여 준다.
 */
class CameraView(private val model: ViewerModel) : JPanel() {
    private val rgbCache = lru<String, BufferedImage?>()
    private val depthCache = lru<String, BufferedImage?>()

    init {
        preferredSize = Dimension(360, 480)
        model.onChange { repaint() }
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0 as Graphics2D
        g.color = Palette.bg
        g.fillRect(0, 0, width, height)
        val r = model.result ?: return message(g, "세션을 여세요")
        val t = model.tNs
        val k = r.reader.meta.camera.imageIntrinsics
        val sw = k.width
        val sh = k.height
        val sensor = BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB)
        val s = sensor.createGraphics().apply { smooth() }
        val rgbRow = r.rgbRowAt(t)
        rgbRow?.let { row -> rgbCache.getOrPut(row.rgbFile!!) { readImage(File(r.session, row.rgbFile!!)) }?.let { s.drawImage(it, 0, 0, sw, sh, null) } }
        val depthRow = r.depthRowAt(t)
        if (model.showDepth && depthRow != null) {
            depthCache.getOrPut(depthRow.depthFile!!) { depthOverlay(File(r.session, depthRow.depthFile!!)) }?.let { d ->
                // 깊이 = 영상의 세로 가운데 16:9 부분(F3·F4)
                val ch = sw * d.height / d.width
                s.drawImage(d, 0, (sh - ch) / 2, sw, ch, null)
            }
        }
        val labels = ArrayList<Triple<Float, Float, Pair<String, Color>>>()
        val row = rgbRow ?: depthRow
        if (row != null) {
            val camFromWorld = SessionReader.toPoseFrame(row).worldFromCam.rigidInverse()
            fun px(w: Vec3) = Projection.project(camFromWorld.transformPoint(w), k)
            fun drawBox(corners: List<Vec3>, color: Color, dashed: Boolean): Pair<Float, Float>? {
                val p = corners.map { px(it) }
                s.color = color
                s.stroke = if (dashed) BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f) else BasicStroke(2f)
                for ((a, b) in BOX_EDGES) {
                    val pa = p[a] ?: continue
                    val pb = p[b] ?: continue
                    s.drawLine(pa.first.toInt(), pa.second.toInt(), pb.first.toInt(), pb.second.toInt())
                }
                return p.filterNotNull().minByOrNull { it.first } // 센서 좌표 왼쪽 = 화면 위
            }
            val al = r.alignment
            r.truth?.takeIf { al != null }?.obstacles?.forEach { o ->
                drawBox(boxCorners(o.minM, o.maxM).map { al!!.toWorld(it) }, Palette.truth, dashed = true)
                    ?.let { labels += Triple(it.first, it.second, "정답 ${o.name}" to Palette.truth) }
            }
            val block = r.blockIndexAt(t).takeIf { it >= 0 }?.let { r.blocks[it] }
            val active = block?.g?.commands?.map { it.obstacleId }?.toSet() ?: emptySet()
            r.slowAt(t)?.snapshot?.obstacles?.forEach { o ->
                drawBox(boxCorners(o.aabbMinW, o.aabbMaxW), Palette.height(o.heightClass), dashed = false)
                    ?.let { labels += Triple(it.first, it.second, "#${o.id}" to Palette.height(o.heightClass)) }
                if (o.id in active) px(o.repPointW)?.let { (u, v) ->
                    s.color = Palette.source
                    s.fillOval(u.toInt() - 7, v.toInt() - 7, 14, 14)
                }
            }
        }
        s.dispose()

        // 센서 영상(sw × sh)을 시계 방향 90° 돌려 패널(sh × sw 비율)에 맞춘다: 화면 (x, y) = (ox + sc·(sh − v), oy + sc·u)
        val sc = min(width.toDouble() / sh, height.toDouble() / sw)
        val ox = (width - sh * sc) / 2
        val oy = (height - sw * sc) / 2
        val saved = g.transform
        g.translate(ox + sh * sc, oy)
        g.scale(sc, sc)
        g.rotate(Math.PI / 2)
        g.drawImage(sensor, 0, 0, null)
        g.transform = saved
        g.smooth()
        g.font = g.font.deriveFont(Font.BOLD, 12f)
        for ((u, v, lc) in labels) {
            g.color = lc.second
            g.drawString(lc.first, (ox + sc * (sh - v)).toInt() + 4, (oy + sc * u).toInt() - 4)
        }
        g.color = Palette.text
        val src = if (rgbRow == null) "RGB 없음(깊이만)" else "RGB 프레임 ${rgbRow.frameIndex}"
        g.drawString(src, 8, height - 8)
    }

    private fun readImage(f: File): BufferedImage? = runCatching { ImageIO.read(f) }.getOrNull()

    /** 깊이(mm) → 가까움 빨강 ~ 4 m 이상 파랑, 반투명. 0(무효)은 투명. */
    private fun depthOverlay(f: File): BufferedImage? = runCatching {
        val img = Png16.decode(f.readBytes())
        val mm = img.gray16 ?: return@runCatching null
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val d = mm[y * img.width + x].toInt() and 0xFFFF
            if (d == 0) continue
            val f01 = (d / 4000f).coerceIn(0f, 1f)
            val c = Color.HSBtoRGB(0.66f * f01, 1f, 1f) and 0x00FFFFFF
            out.setRGB(x, y, (110 shl 24) or c)
        }
        out
    }.getOrNull()
}

/**
 * 위에서 본 그림(보행선 좌표: 위쪽 = 보행선 앞 +z, 오른쪽 = +x). 궤적·점유 복셀·통로·추적 물체·정답 상자·음원 방향.
 * 음원 선은 구간 색, 굵기는 STOP이 가장 굵다.
 */
class TopView(private val model: ViewerModel) : JPanel() {
    init {
        preferredSize = Dimension(420, 480)
        model.onChange { repaint() }
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0 as Graphics2D
        g.smooth()
        g.color = Palette.bg
        g.fillRect(0, 0, width, height)
        val r = model.result ?: return message(g, "")
        val al = r.alignment ?: return message(g, "바닥을 잡지 못해 보행선 좌표가 없음")
        val track = r.reader.rows.map { al.toTruth(Vec3(it.pose.tx, it.pose.ty, it.pose.tz)) }
        val truthBoxes = r.truth?.obstacles.orEmpty()
        val xs = track.map { it.x } + truthBoxes.flatMap { listOf(it.minM.x, it.maxM.x) }
        val zs = track.map { it.z } + truthBoxes.flatMap { listOf(it.minM.z, it.maxM.z) }
        val x0 = min(-1.5f, xs.min() - 0.5f)
        val x1 = max(1.5f, xs.max() + 0.5f)
        val z0 = min(-1f, zs.min() - 0.5f)
        val z1 = max(r.config.corridor.lengthM + 1f, zs.max() + 0.5f)
        val sc = min((width - 20) / (x1 - x0), (height - 20) / (z1 - z0))
        val cx = width / 2f - (x0 + x1) / 2 * sc
        val by = height - 10f + z0 * sc
        fun sx(x: Float) = cx + x * sc
        fun sy(z: Float) = by - z * sc

        // 0.5 m 격자
        g.color = Palette.grid
        var gx = Math.floor(x0 * 2.0).toFloat() / 2
        while (gx <= x1) { g.drawLine(sx(gx).toInt(), 0, sx(gx).toInt(), height); gx += 0.5f }
        var gz = Math.floor(z0 * 2.0).toFloat() / 2
        while (gz <= z1) { g.drawLine(0, sy(gz).toInt(), width, sy(gz).toInt()); gz += 0.5f }

        // 궤적(카메라)
        g.color = Color(120, 120, 130)
        val path = Path2D.Float()
        track.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p.x), sy(p.z)) else path.lineTo(sx(p.x), sy(p.z)) }
        g.draw(path)

        val t = model.tNs
        val slow = r.slowAt(t)
        // 점유 복셀(밝을수록 높음)
        slow?.voxelsW?.let { v ->
            for (i in 0 until v.size / 3) {
                val p = al.toTruth(Vec3(v[3 * i], v[3 * i + 1], v[3 * i + 2]))
                if (!model.voxelHeight.contains(p.y, r.config.corridor.heightM)) continue
                val b = (80 + 120 * (p.y / 2f).coerceIn(0f, 1f)).toInt()
                g.color = Color(b, b, b)
                g.fillRect(sx(p.x).toInt() - 1, sy(p.z).toInt() - 1, 3, 3)
            }
        }
        // 확실히 빈 공간(정답 free, M12): 이 안의 복셀은 헛 복셀
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(3f, 3f), 0f)
        g.color = Palette.free
        for (f in r.truth?.free.orEmpty()) {
            g.drawRect(sx(f.minM.x).toInt(), sy(f.maxM.z).toInt(), ((f.maxM.x - f.minM.x) * sc).toInt(), ((f.maxM.z - f.minM.z) * sc).toInt())
        }
        // 정답 상자
        g.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
        for (o in truthBoxes) {
            g.color = Palette.truth
            g.drawRect(sx(o.minM.x).toInt(), sy(o.maxM.z).toInt(), ((o.maxM.x - o.minM.x) * sc).toInt(), ((o.maxM.z - o.minM.z) * sc).toInt())
            g.drawString(o.name, sx(o.maxM.x) + 3, sy(o.maxM.z) + 12)
        }
        g.stroke = BasicStroke(2f)
        // 추적 물체
        slow?.snapshot?.obstacles?.forEach { o ->
            val c = listOf(Vec3(o.aabbMinW.x, 0f, o.aabbMinW.z), Vec3(o.aabbMaxW.x, 0f, o.aabbMinW.z), Vec3(o.aabbMaxW.x, 0f, o.aabbMaxW.z), Vec3(o.aabbMinW.x, 0f, o.aabbMaxW.z))
                .map { al.toTruth(it) }
            val poly = Path2D.Float().apply { c.forEachIndexed { i, p -> if (i == 0) moveTo(sx(p.x), sy(p.z)) else lineTo(sx(p.x), sy(p.z)) }; closePath() }
            val col = Palette.height(o.heightClass)
            g.color = Color(col.red, col.green, col.blue, 70); g.fill(poly)
            g.color = col; g.draw(poly)
            val rp = al.toTruth(o.repPointW)
            g.fillOval(sx(rp.x).toInt() - 3, sy(rp.z).toInt() - 3, 6, 6)
            g.drawString("#${o.id}", sx(c.maxOf { it.x }) + 3, sy(c.maxOf { it.z }))
        }
        // 머리, 통로, 음원
        val i = r.blockIndexAt(t)
        if (i >= 0) {
            val b = r.blocks[i]
            val head = r.headTruthAt(i)!!
            val headW = al.toWorld(head)
            val hd = Math.toRadians(b.g.headingDeg.toDouble())
            if (!b.g.headingDeg.isNaN()) {
                val hW = Vec3(sin(hd).toFloat(), 0f, -cos(hd).toFloat()) // headingDeg = atan2(x, −z)
                val rW = Vec3(-hW.z, 0f, hW.x) // 오른쪽
                val cc = r.config.corridor
                val corners = listOf(-cc.behindM to -cc.widthM / 2, -cc.behindM to cc.widthM / 2, cc.lengthM to cc.widthM / 2, cc.lengthM to -cc.widthM / 2)
                    .map { (a, l) -> al.toTruth(headW + hW * a + rW * l) }
                g.color = Color(255, 255, 255, 25)
                val poly = Path2D.Float().apply { corners.forEachIndexed { k, p -> if (k == 0) moveTo(sx(p.x), sy(p.z)) else lineTo(sx(p.x), sy(p.z)) }; closePath() }
                g.fill(poly)
                g.color = Color(255, 255, 255, 70); g.draw(poly)
                for (c in b.g.commands) {
                    val a = Math.toRadians(c.azimuthDeg.toDouble())
                    val d = hW * cos(a).toFloat() + rW * sin(a).toFloat()
                    val end = al.toTruth(headW + d * c.distanceM)
                    g.color = Palette.band(c.band)
                    g.stroke = BasicStroke(if (c.band == Band.STOP) 5f else 3f)
                    g.drawLine(sx(head.x).toInt(), sy(head.z).toInt(), sx(end.x).toInt(), sy(end.z).toInt())
                }
            }
            g.stroke = BasicStroke(2f)
            g.color = Color.WHITE
            g.fillOval(sx(head.x).toInt() - 5, sy(head.z).toInt() - 5, 10, 10)
            g.color = Palette.text
            g.drawString("${b.g.state}  ${"%.2f".format(r.secondsOf(t))} s  물체 ${slow?.snapshot?.obstacles?.size ?: 0}  음원 ${b.g.commands.size}", 8, 16)
        }
    }
}

/**
 * 타임라인: 상태 띠, 첫 음원 거리(점, 구간 색)와 정답 거리(초록 선), 방위각(같은 방식), 메모 표시, 현재 위치.
 * 클릭·끌기로 시각을 옮긴다.
 */
class TimelineView(private val model: ViewerModel, private val onSeek: (Long) -> Unit) : JPanel() {
    private val left = 48
    private val right = 12

    init {
        preferredSize = Dimension(900, 230)
        model.onChange { repaint() }
        val m = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = seek(e.x)
            override fun mouseDragged(e: MouseEvent) = seek(e.x)
        }
        addMouseListener(m)
        addMouseMotionListener(m)
    }

    private fun seek(x: Int) {
        val r = model.result ?: return
        val f = ((x - left).toDouble() / (width - left - right)).coerceIn(0.0, 1.0)
        onSeek(r.t0Ns + (f * r.durationS * 1e9).toLong())
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0 as Graphics2D
        g.smooth()
        g.color = Palette.bg
        g.fillRect(0, 0, width, height)
        val r = model.result ?: return
        val w = width - left - right
        if (w <= 0 || r.blocks.isEmpty()) return
        val n = r.blocks.size
        fun xOf(i: Int) = left + i.toFloat() * w / n
        val stripY = 6
        val stripH = 12
        val phantomY = stripY + stripH + 3
        val phantomH = 12
        val plotH = (height - stripY - stripH - phantomH - 47) / 2 // 아래 16 px는 범례·메모 표시
        val distTop = phantomY + phantomH + 8
        val azTop = distTop + plotH + 8
        val maxD = r.config.corridor.lengthM + 0.5f
        val azRange = 45f

        // 상태 띠
        for (px in 0 until w) {
            val i = (px.toLong() * n / w).toInt().coerceIn(0, n - 1)
            g.color = Palette.state(r.state[i])
            g.drawLine(left + px, stripY, left + px, stripY + stripH)
        }
        // 헛 복셀(정답 free 안 점유 복셀, M12): 막대 높이 = 그 시각 개수(최대값 기준)
        val maxPhantom = r.phantomAtBlock.maxOrNull() ?: -1
        if (maxPhantom >= 0) {
            g.color = Palette.phantom
            for (px in 0 until w) {
                val i = (px.toLong() * n / w).toInt().coerceIn(0, n - 1)
                val v = r.phantomAtBlock[i]
                if (v > 0) {
                    val h = (v.toFloat() / maxOf(1, maxPhantom) * phantomH).toInt().coerceAtLeast(1)
                    g.drawLine(left + px, phantomY + phantomH - h, left + px, phantomY + phantomH)
                }
            }
        }
        // 축
        g.color = Palette.grid
        g.drawRect(left, distTop, w, plotH)
        g.drawRect(left, azTop, w, plotH)
        val zeroAz = azTop + plotH / 2
        g.drawLine(left, zeroAz, left + w, zeroAz)
        listOf(1f, 2.5f).forEach { d ->
            val y = distTop + plotH - (d / maxD * plotH).toInt()
            g.drawLine(left, y, left + w, y)
        }
        g.color = Palette.text
        g.font = g.font.deriveFont(11f)
        g.drawString("거리", 4, distTop + 12)
        g.drawString("${maxD}m", 4, distTop + 26)
        g.drawString("방위", 4, azTop + 12)
        g.drawString("±${azRange.toInt()}°", 4, azTop + 26)
        g.drawString("상태", 4, stripY + 11)
        g.drawString("헛 ${if (maxPhantom >= 0) maxPhantom else "–"}", 4, phantomY + 11)

        fun yDist(d: Float) = distTop + plotH - (d.coerceIn(0f, maxD) / maxD * plotH)
        fun yAz(a: Float) = zeroAz - (a.coerceIn(-azRange, azRange) / azRange * plotH / 2)
        // 정답(선)
        g.color = Palette.truth
        g.stroke = BasicStroke(2f)
        for (i in 1 until n) {
            if (!r.truthDistM[i - 1].isNaN() && !r.truthDistM[i].isNaN()) {
                g.drawLine(xOf(i - 1).toInt(), yDist(r.truthDistM[i - 1]).toInt(), xOf(i).toInt(), yDist(r.truthDistM[i]).toInt())
                g.drawLine(xOf(i - 1).toInt(), yAz(r.truthAzDeg[i - 1]).toInt(), xOf(i).toInt(), yAz(r.truthAzDeg[i]).toInt())
            }
        }
        // 추정 음원(점)
        val step = max(1, n / w)
        for (i in 0 until n step step) {
            if (r.estDistM[i].isNaN()) continue
            g.color = Palette.band(r.estBand[i])
            g.fillRect(xOf(i).toInt(), yDist(r.estDistM[i]).toInt() - 1, 2, 3)
            g.fillRect(xOf(i).toInt(), yAz(r.estAzDeg[i]).toInt() - 1, 2, 3)
        }
        // 메모
        g.color = Palette.source
        for (note in model.notes) {
            val x = left + (note.tS / r.durationS * w).toInt()
            g.fillPolygon(intArrayOf(x - 5, x + 5, x), intArrayOf(height - 4, height - 4, height - 14), 3)
        }
        // 현재 위치
        val cur = left + (r.secondsOf(model.tNs) / r.durationS * w).toInt()
        g.color = Color.WHITE
        g.drawLine(cur, 0, cur, height)
        g.color = Palette.text
        g.drawString("초록 = 정답 통로 안 가장 가까운 정답, 점 = 첫 음원(구간 색)", left + 6, height - 4)
    }
}

private fun JPanel.message(g: Graphics2D, text: String) {
    g.color = Palette.text
    g.drawString(text, 12, 22)
}

private fun <K, V> lru(capacity: Int = 48): MutableMap<K, V> = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
}
