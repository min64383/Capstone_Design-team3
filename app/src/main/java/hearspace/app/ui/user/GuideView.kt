package hearspace.app.ui.user

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import hearspace.app.R
import kotlin.math.min

/**
 * 작동 화면 가운데 그림(M45 시안 §3): [UserScreen.Visual]을 그린다. 크기는 시안의 dp를 따르되 자리가 좁으면 줄인다.
 * 색은 res/values/colors.xml의 run_*·logo_*(늘 검은 배경 기준 대비).
 */
internal class GuideView(context: Context) : View(context) {

    var visual: UserScreen.Visual = UserScreen.Visual.None
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    private val logo = BitmapFactory.decodeResource(resources, R.drawable.logo_run)
    private val rect = RectF() // 그릴 때마다 만들지 않고 다시 쓴다(준비 고리는 화면마다 다시 그린다)
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val white = context.getColor(R.color.run_text)
    private val caution = context.getColor(R.color.run_caution)
    private val stop = context.getColor(R.color.run_stop)
    private val ring = listOf(R.color.logo_1, R.color.logo_2, R.color.logo_3, R.color.logo_4, R.color.logo_5, R.color.logo_6).map(context::getColor)

    /** 기기 설정에서 애니메이션을 끄면(접근성) 준비 고리를 멈춘 채 그린다. */
    private val animate = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val room = min(width, height).toFloat()
        when (val v = visual) {
            UserScreen.Visual.None -> Unit
            UserScreen.Visual.Logo -> {
                val s = min(context.dp(150).toFloat(), room)
                rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
                canvas.drawBitmap(logo, null, rect, fill.apply { color = white })
            }
            UserScreen.Visual.Preparing -> {
                // 로고 여섯 색이 차례로 켜지는 고리: 단위 = 시안 120 칸, 원 지름 26, 고리 반지름 42
                val u = min(context.dp(180).toFloat(), room) / 120f
                val t = SystemClock.uptimeMillis()
                for (i in 0 until 6) {
                    val a = Math.toRadians(-90.0 + 60.0 * i)
                    fill.color = ring[i]
                    fill.alpha = if (animate) (255 * pulse(((t - i * 300) % 1800 + 1800) % 1800 / 1800f)).toInt() else 255
                    canvas.drawCircle(cx + 42 * u * Math.cos(a).toFloat(), cy + 42 * u * Math.sin(a).toFloat(), 13 * u, fill)
                }
                fill.alpha = 255
                if (animate) postInvalidateOnAnimation()
            }
            UserScreen.Visual.FloorCheck -> {
                // 주의 세모 + 느낌표(시안 100 칸 상자, 150 dp)
                val u = min(context.dp(150).toFloat(), room) / 100f
                canvas.translate(cx - 50 * u, cy - 46 * u)
                stroke.color = caution
                stroke.strokeWidth = 8 * u
                path.reset()
                path.moveTo(50 * u, 6 * u); path.lineTo(95 * u, 86 * u); path.lineTo(5 * u, 86 * u); path.close()
                canvas.drawPath(path, stroke)
                exclamation(canvas, u, 34f, 58f, 72f, caution)
            }
            UserScreen.Visual.Paused -> {
                val u = min(context.dp(120).toFloat(), room) / 100f
                fill.color = white
                canvas.translate(cx - 50 * u, cy - 50 * u)
                for (left in floatArrayOf(22f, 60f)) {
                    rect.set(left * u, 14 * u, (left + 18) * u, 86 * u)
                    canvas.drawRoundRect(rect, 6 * u, 6 * u, fill)
                }
            }
            UserScreen.Visual.Notice -> {
                // 안내가 멈춤: 흰 원 + 느낌표(장애물 멈춤과 헷갈리지 않게 빨강을 쓰지 않는다)
                val u = min(context.dp(120).toFloat(), room) / 100f
                canvas.translate(cx - 50 * u, cy - 50 * u)
                stroke.color = white
                stroke.strokeWidth = 8 * u
                canvas.drawCircle(50 * u, 50 * u, 42 * u, stroke)
                exclamation(canvas, u, 28f, 56f, 71f, white)
            }
            is UserScreen.Visual.Arrow -> {
                // 시안 120 칸(230 dp). 주의: 화살표만. 멈춤: 굵은 고리 + 작은 화살표
                val u = min(context.dp(230).toFloat(), room) / 120f
                val color = if (v.stop) stop else caution
                canvas.translate(cx, cy)
                if (v.stop) {
                    stroke.color = color
                    stroke.strokeWidth = 9 * u
                    canvas.drawCircle(0f, 0f, 54 * u, stroke)
                }
                canvas.rotate(v.azimuthDeg)
                // 끝 y, 머리 밑 y, 몸통 반폭, 꼬리 y, 머리 반폭(시안 값)
                val tip = if (v.stop) -36f else -46f
                val head = if (v.stop) -11f else -16f
                val shaft = if (v.stop) 7f else 8f
                val tail = if (v.stop) 32f else 40f
                val half = if (v.stop) 20f else 24f
                path.reset()
                path.moveTo(0f, tip * u)
                path.lineTo(-half * u, head * u); path.lineTo(-shaft * u, head * u); path.lineTo(-shaft * u, tail * u)
                path.lineTo(shaft * u, tail * u); path.lineTo(shaft * u, head * u); path.lineTo(half * u, head * u)
                path.close()
                fill.color = color
                stroke.color = color
                stroke.strokeWidth = 5 * u // 모서리를 둥글게
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
        }
    }

    /** 느낌표: x = 50 칸, 막대 [top]~[bottom], 점 [dot]. */
    private fun exclamation(canvas: Canvas, u: Float, top: Float, bottom: Float, dot: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 9 * u
        canvas.drawLine(50 * u, top * u, 50 * u, bottom * u, stroke)
        fill.color = color
        canvas.drawCircle(50 * u, dot * u, 5.5f * u, fill)
    }

    /** 시안의 켜짐 곡선: 0 → 15%에서 가장 밝고 100%까지 천천히 어두워진다(투명도 0.25 ~ 1). */
    private fun pulse(f: Float) = if (f < 0.15f) 0.25f + 0.75f * f / 0.15f else 1f - 0.75f * (f - 0.15f) / 0.85f
}
