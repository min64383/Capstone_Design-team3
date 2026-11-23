package hearspace.app.ui.user

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import hearspace.app.R
import hearspace.core.types.Band
import hearspace.core.types.HeightClass
import kotlin.math.min
import kotlin.math.sin

/**
 * 작동 화면 가운데 그림(M45 시안 §3): [UserScreen.Visual]을 그린다. 크기는 시안의 dp를 따르되 자리가 좁으면 줄인다.
 * 색은 res/values/colors.xml의 run_*·logo_*(늘 검은 배경 기준 대비).
 */
internal class GuideView(context: Context) : View(context) {

    var visual: UserScreen.Visual = UserScreen.Visual.None
        set(v) {
            if (field == v) return
            field = v
            (v as? UserScreen.Visual.Arrow)?.spatial?.let { cue ->
                val target = spatialColor(context, cue.tint)
                val nowMs = SystemClock.uptimeMillis()
                if (target != toColor) {
                    fromColor = animatedColor(nowMs)
                    toColor = target
                    colorStartedMs = nowMs
                }
                if (cue.band == Band.STOP || !animate) fromColor = target
            }
            invalidate()
        }

    var spatialConfig: SpatialUiConfig? = null

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
    private val muted = context.getColor(R.color.run_text2)
    private val heightLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var fromColor = caution
    private var toColor = caution
    private var colorStartedMs = 0L
    private val ring = listOf(R.color.logo_1, R.color.logo_2, R.color.logo_3, R.color.logo_4, R.color.logo_5, R.color.logo_6).map(context::getColor)

    /** 기기 설정에서 애니메이션을 끄면(접근성) 준비 고리를 멈춘 채 그린다. */
    private val animate: Boolean get() = ValueAnimator.areAnimatorsEnabled()

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
                if (animate) nextFrame()
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
                if (v.spatial != null && spatialConfig != null) {
                    drawSpatial(canvas, v.spatial)
                    return
                }
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

    /** A안: 방위는 왼쪽 그림, 높이는 오른쪽 사람 그림에 독립적으로 표시한다. */
    private fun drawSpatial(canvas: Canvas, cue: SpatialCue) {
        val cfg = spatialConfig ?: return
        val nowMs = SystemClock.uptimeMillis()
        val color = animatedColor(nowMs)
        val phase = if (animate) (nowMs % cfg.pulsePeriodMs).toFloat() / cfg.pulsePeriodMs else 0f
        val u = min(width / 360f, height / 240f)
        val saved = canvas.save()
        canvas.translate((width - 360f * u) / 2f, (height - 240f * u) / 2f)
        canvas.scale(u, u)

        val arrowSave = canvas.save()
        canvas.translate(126f, 118f)
        if (cue.band == Band.STOP) {
            stroke.color = color
            stroke.strokeWidth = 6f
            canvas.drawCircle(0f, 0f, 51f, stroke)
        }
        canvas.rotate(cue.azimuthDeg)
        val spacing = when (cue.height) { HeightClass.HEAD -> 6f; HeightClass.BODY -> 10f; HeightClass.FLOOR -> 14f }
        stroke.color = color
        stroke.strokeWidth = when (cue.tint) { DistanceTint.NEAR -> 5f; DistanceTint.MIDDLE -> 4f; DistanceTint.FAR -> 3f }
        for (i in 0 until 4) {
            val radius = 57f + i * spacing + phase * 8f
            rect.set(-radius, -radius, radius, radius)
            // 모두 사라지는 순간 없이 천천히 밝아졌다 어두워진다.
            stroke.alpha = if (animate) (160 + 70 * sin((phase - i * 0.12f) * 2f * Math.PI)).toInt() else 220
            canvas.drawArc(rect, -116f, 52f, false, stroke)
        }
        stroke.alpha = 255
        path.reset()
        path.moveTo(0f, -42f)
        path.lineTo(-19f, -17f); path.lineTo(-7f, -17f); path.lineTo(-7f, 30f)
        path.lineTo(7f, 30f); path.lineTo(7f, -17f); path.lineTo(19f, -17f)
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
        canvas.restoreToCount(arrowSave)

        val personSave = canvas.save()
        canvas.translate(282f, 117f)
        fill.color = if (cue.height == HeightClass.HEAD) color else muted
        canvas.drawCircle(0f, -58f, 14f, fill)
        if (cue.height == HeightClass.HEAD) {
            stroke.color = color
            stroke.strokeWidth = 3f
            canvas.drawCircle(0f, -58f, 20f, stroke)
        }
        fill.color = if (cue.height == HeightClass.BODY) color else muted
        rect.set(-16f, -34f, 16f, 22f)
        canvas.drawRoundRect(rect, 12f, 12f, fill)
        stroke.color = fill.color
        stroke.strokeWidth = 10f
        canvas.drawLine(-23f, -24f, -23f, 12f, stroke)
        canvas.drawLine(23f, -24f, 23f, 12f, stroke)
        stroke.color = if (cue.height == HeightClass.FLOOR) color else muted
        stroke.strokeWidth = 12f
        canvas.drawLine(-9f, 23f, -9f, 61f, stroke)
        canvas.drawLine(9f, 23f, 9f, 61f, stroke)
        heightLabel.color = color
        heightLabel.textSize = 14f * resources.configuration.fontScale.coerceAtMost(1.3f)
        canvas.drawText(cue.heightText, 0f, 95f, heightLabel)
        canvas.restoreToCount(personSave)
        canvas.restoreToCount(saved)
        if (animate) nextFrame()
    }

    private fun animatedColor(nowMs: Long): Int {
        val durationMs = spatialConfig?.colorTransitionMs ?: 0L
        if (!animate || durationMs == 0L || fromColor == toColor) return toColor
        val f = ((nowMs - colorStartedMs).toFloat() / durationMs).coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * f).toInt()
        return Color.rgb(channel(Color.red(fromColor), Color.red(toColor)),
            channel(Color.green(fromColor), Color.green(toColor)), channel(Color.blue(fromColor), Color.blue(toColor)))
    }

    private fun nextFrame() {
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            postInvalidateDelayed(spatialConfig?.frameIntervalMs ?: return)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
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

internal fun spatialColor(context: Context, tint: DistanceTint): Int = context.getColor(when (tint) {
    DistanceTint.NEAR -> R.color.spatial_near
    DistanceTint.MIDDLE -> R.color.spatial_middle
    DistanceTint.FAR -> R.color.spatial_far
})

/** 실제 TTS STARTED~종료 사이에만 그리는 상징 파형. 오디오 샘플을 측정한 스펙트럼은 아니다. */
internal class SpeechWaveView(context: Context) : View(context) {
    var config: SpatialUiConfig? = null
    var active: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.run_text)
        strokeCap = Paint.Cap.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val cfg = config ?: return
        val animate = ValueAnimator.areAnimatorsEnabled()
        val phase = (SystemClock.uptimeMillis() % cfg.pulsePeriodMs).toDouble() / cfg.pulsePeriodMs
        paint.strokeWidth = width / 12f
        for (i in 0 until 5) {
            val fraction = if (animate) (0.25 + 0.65 * absSin(phase * 2 * Math.PI + i * 0.8)).toFloat()
                else if (i == 2) 0.9f else 0.5f
            val x = width * (i + 1f) / 6f
            val half = height * fraction / 2f
            canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
        if (animate && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            postInvalidateDelayed(cfg.frameIntervalMs)
        }
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }
    private fun absSin(value: Double) = kotlin.math.abs(sin(value))
}
