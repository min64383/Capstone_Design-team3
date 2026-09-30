package walkassist.app.ui.dev

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import walkassist.core.geometry.HeadPose
import walkassist.core.geometry.Vec3
import walkassist.core.types.Band
import walkassist.core.types.Config
import walkassist.core.types.GuidanceOutput
import walkassist.core.types.HeightClass
import walkassist.core.types.ObstacleSnapshot
import walkassist.core.types.RepStrategy
import kotlin.math.cos
import kotlin.math.sin

/**
 * 디버그 오버레이(§11.3): 위에서 본 미니맵(머리 기준, 진행 방향이 위) + 상태 텍스트 + 현재 음원 큰 화살표(시연용).
 * UI 스레드에서 [update] 후 다시 그린다. 안내 로직은 없고 core 출력만 그린다.
 */
class DebugOverlay(context: Context, config: Config) : View(context) {

    private val corridor = config.corridor
    private val halfVoxelM = config.map.voxelSizeM / 2

    class State(
        val head: HeadPose?,
        val output: GuidanceOutput?,
        val snapshot: ObstacleSnapshot?,
        val voxelsW: List<Vec3>,
        val lines: List<String>,
    )

    private var s: State? = null

    fun update(state: State) {
        s = state
        invalidate()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 72f; isFakeBoldText = true }

    override fun onDraw(canvas: Canvas) {
        val st = s ?: return
        val mapSize = width * 0.9f
        val left = (width - mapSize) / 2
        val top = height - mapSize - 40f
        fill.color = 0xAA000000.toInt()
        canvas.drawRect(left, top, left + mapSize, top + mapSize, fill)
        st.head?.let { drawMap(canvas, st, it, left, top, mapSize) }
        drawSource(canvas, st.output)

        fill.color = 0x99000000.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), 40f + st.lines.size * 42f, fill)
        st.lines.forEachIndexed { i, l -> canvas.drawText(l, 24f, 50f + i * 42f, text) }
    }

    /** 미니맵: 범위 가로 4 m × 세로 4 m, 머리는 아래에서 1/4 지점. */
    private fun drawMap(c: Canvas, st: State, head: HeadPose, left: Float, top: Float, size: Float) {
        val scale = size / MAP_RANGE_M
        val ox = left + size / 2
        val oy = top + size * 0.75f
        fun sx(p: Vec3) = ox + ((p - head.positionW) dot head.rightW) * scale
        fun sy(p: Vec3) = oy - ((p - head.positionW) dot head.headingW) * scale

        c.save()
        c.clipRect(left, top, left + size, top + size)
        // 통로
        stroke.color = Color.CYAN
        val hw = corridor.widthM / 2 * scale
        c.drawRect(ox - hw, oy - corridor.lengthM * scale, ox + hw, oy + corridor.behindM * scale, stroke)
        // 복셀
        fill.color = 0x88AAAAAA.toInt()
        val v = halfVoxelM * scale
        for (p in st.voxelsW) c.drawRect(sx(p) - v, sy(p) - v, sx(p) + v, sy(p) + v, fill)
        // 물체 AABB(높이 분류 색) + 대표점 3방식
        for (o in st.snapshot?.obstacles.orEmpty()) {
            stroke.color = when (o.heightClass) {
                HeightClass.HEAD -> Color.MAGENTA
                HeightClass.BODY -> Color.rgb(255, 150, 0)
                HeightClass.FLOOR -> Color.GREEN
            }
            val a = o.aabbMinW
            val b = o.aabbMaxW
            val corners = listOf(Vec3(a.x, 0f, a.z), Vec3(b.x, 0f, a.z), Vec3(b.x, 0f, b.z), Vec3(a.x, 0f, b.z))
            val path = Path()
            corners.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p), sy(p)) else path.lineTo(sx(p), sy(p)) }
            path.close()
            c.drawPath(path, stroke)
            for ((strategy, p) in o.repCandidatesW) {
                fill.color = REP_COLORS.getValue(strategy)
                c.drawCircle(sx(p), sy(p), if (p == o.repPointW) 12f else 7f, fill)
            }
        }
        // 머리(진행 방향 위)
        fill.color = Color.WHITE
        c.drawPath(Path().apply { moveTo(ox, oy - 24f); lineTo(ox - 14f, oy + 12f); lineTo(ox + 14f, oy + 12f); close() }, fill)
        c.restore()
    }

    /** 현재 음원: 화면 가운데 큰 화살표(방위각) + 구간·거리. 음원이 없으면 상태만(무음 ≠ 안전이므로 "없음"이라 쓰지 않는다). */
    private fun drawSource(c: Canvas, g: GuidanceOutput?) {
        val cx = width / 2f
        val cy = height * 0.33f
        val cmd = g?.commands?.firstOrNull()
        if (cmd == null) {
            c.drawText(g?.state?.name ?: "-", cx - big.measureText(g?.state?.name ?: "-") / 2, cy, big)
            return
        }
        val color = if (cmd.band == Band.STOP) Color.RED else Color.YELLOW
        val r = 160f
        val rad = Math.toRadians(cmd.azimuthDeg.toDouble())
        val tx = cx + (r * sin(rad)).toFloat()
        val ty = cy - (r * cos(rad)).toFloat()
        stroke.color = color
        stroke.strokeWidth = 16f
        c.drawLine(cx, cy, tx, ty, stroke)
        stroke.strokeWidth = 3f
        fill.color = color
        c.drawCircle(tx, ty, 24f, fill)
        val label = "%s %.1fm %+.0f°".format(cmd.band, cmd.distanceM, cmd.azimuthDeg)
        big.color = color
        c.drawText(label, cx - big.measureText(label) / 2, cy + r + 90f, big)
        big.color = Color.WHITE
    }

    companion object {
        private const val MAP_RANGE_M = 4f
        private val REP_COLORS = mapOf(
            RepStrategy.CENTROID to Color.BLUE,
            RepStrategy.NEAREST to Color.RED,
            RepStrategy.CORRIDOR_NEAREST to Color.YELLOW,
        )
    }
}
