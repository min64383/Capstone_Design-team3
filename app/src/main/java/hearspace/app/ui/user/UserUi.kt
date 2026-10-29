package hearspace.app.ui.user

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import hearspace.app.R

/** 설정·글·헤드폰 화면 공통 틀(M45 시안 §4): 위 막대(뒤로 + 제목) 아래 세로로 쌓는 본문. 글자 18 sp, 누르는 줄 56 dp 이상. */

internal fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).toInt()

/** 시스템 막대(상태·내비게이션)만큼 안쪽 여백(Android 15+ 전체 화면 그리기에서도 글이 막대 밑에 깔리지 않게). */
internal fun View.padForSystemBars() = setOnApplyWindowInsetsListener { v, insets ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = insets.getInsets(WindowInsets.Type.systemBars())
        v.setPadding(b.left, b.top, b.right, b.bottom)
    } else {
        @Suppress("DEPRECATION")
        v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }
    insets
}

private fun View.asHeading() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
}

private fun Context.attrResource(attr: Int): Int = TypedValue().also { theme.resolveAttribute(attr, it, true) }.resourceId

/** 화면을 만들고 본문을 돌려준다. */
internal fun Activity.userScreen(title: String): LinearLayout {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(8), 0, dp(8), 0)
        addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text))
            setBackgroundResource(attrResource(android.R.attr.selectableItemBackgroundBorderless))
            contentDescription = "뒤로"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(context).apply {
            text = title
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text))
            setPadding(dp(6), 0, 0, 0)
            asHeading()
        })
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.bg))
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(ScrollView(context).apply { addView(body, MATCH_PARENT, WRAP_CONTENT) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        padForSystemBars()
    }
    setContentView(page)
    return body
}

/** 소제목(TalkBack 제목). [note]는 소제목 옆 보조 글. */
internal fun LinearLayout.section(title: String, note: String? = null) = addView(TextView(context).apply {
    text = if (note == null) title else "$title · $note"
    textSize = 14f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(context.getColor(R.color.primary))
    setPadding(context.dp(20), context.dp(14), context.dp(20), context.dp(2))
    asHeading()
})

/** 한 줄 항목. [onClick]이 있으면 누를 수 있다. */
internal fun LinearLayout.item(title: String, sub: String? = null, onClick: (() -> Unit)? = null) = addView(LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = context.dp(56)
    setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
    addView(TextView(context).apply {
        text = title
        textSize = 18f
        setTextColor(context.getColor(R.color.text))
    })
    if (sub != null) addView(TextView(context).apply {
        text = sub
        textSize = 14f
        setTextColor(context.getColor(R.color.text2))
    })
    if (onClick != null) {
        setBackgroundResource(context.attrResource(android.R.attr.selectableItemBackground))
        setOnClickListener { onClick() }
    }
}, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

/** 본문 글(18 sp). */
internal fun LinearLayout.paragraph(text: String) = addView(TextView(context).apply {
    this.text = text
    textSize = 18f
    setTextColor(context.getColor(R.color.text))
    setLineSpacing(0f, 1.35f)
    setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(16))
})
