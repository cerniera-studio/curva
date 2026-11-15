package com.cerniera.curva

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.cerniera.kit.Kit
import com.cerniera.kit.withAlpha
import kotlin.math.abs
import kotlin.math.max

/**
 * A match drawn as one line from kick-off to the whistle, level at the middle, above when Arsenal lead,
 * below when behind. [big] adds the half-time hairline, the minute marks and, on a touch, the goal
 * nearest the finger rising from the line (Giulietta: the detail waits in the trim until asked for).
 */
class CurveView(ctx: Context, private val big: Boolean) : View(ctx) {
    var match: Match? = null
        set(v) { val changed = field?.id != v?.id; field = v; picked = null; if (changed) drawOn() else invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = d * 0.8f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.sans(ctx, 500); textSize = 12.5f * d }
    private var progress = 1f
    private var picked: Goal? = null
    private var pickedAt = 0L
    private val path = Path()

    private fun drawOn() {
        progress = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (big) 900 else 600; interpolator = Kit.ease
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(c: Canvas) {
        val m = match ?: return
        val s = m.shape
        val pad = if (big) 14 * d else 3 * d
        val left = pad; val right = width - pad
        val top = if (big) 26 * d else pad; val bottom = height - (if (big) 22 * d else pad)
        val mid = (top + bottom) / 2f
        val scale = max(2, max(abs(s.low), abs(s.high)))
        fun x(min: Float) = left + (right - left) * (min / s.end)
        fun y(diff: Float) = mid - diff / scale * (bottom - top) * 0.46f

        // Ground: level, half-time and the minute marks
        hair.color = Kit.HAIRLINE
        c.drawLine(left, mid, right, mid, hair)
        if (big) {
            hair.color = withAlpha(Kit.CHAMPAGNE, 0.28f)
            c.drawLine(x(45f), top - 8 * d, x(45f), bottom + 4 * d, hair)
            label.color = Kit.MUTED; label.textAlign = Paint.Align.CENTER
            for (mm in listOf(0, 45, 90)) if (mm <= s.end) c.drawText(if (mm == 45) "HT" else "$mm'", x(mm.toFloat()), height - 6 * d, label)
        }

        // The line, smoothed between its points and drawn on to [progress]
        path.reset()
        val pts = s.points
        val upTo = s.end * progress
        path.moveTo(x(pts[0].first), y(pts[0].second))
        var lastX = x(0f); var lastY = y(0f)
        for (i in 1 until pts.size) {
            val (m0, d0) = pts[i - 1]; val (m1, d1) = pts[i]
            if (m0 >= upTo) break
            val f = if (m1 <= upTo) 1f else (upTo - m0) / (m1 - m0)
            val mx = m0 + (m1 - m0) * f; val dy = d0 + (d1 - d0) * f
            val cx = (x(m0) + x(mx)) / 2f
            path.cubicTo(cx, y(d0), cx, y(dy), x(mx), y(dy))
            lastX = x(mx); lastY = y(dy)
        }
        // Lead above the line in champagne, deficit below in rosso, faint
        val area = Path(path).apply { lineTo(lastX, mid); lineTo(x(0f), mid); close() }
        fill.shader = LinearGradient(0f, top, 0f, bottom,
            intArrayOf(withAlpha(Kit.CHAMPAGNE, 0.22f), withAlpha(Kit.CHAMPAGNE, 0.02f), withAlpha(Kit.ROSSO, 0.02f), withAlpha(Kit.ROSSO, 0.22f)),
            floatArrayOf(0f, 0.49f, 0.51f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.color = Kit.INK
        line.strokeWidth = (if (big) 2.2f else 1.6f) * d
        line.pathEffect = if (s.exact) null else DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
        c.drawPath(path, line)

        // The result: the one champagne point, rosso for a defeat
        if (progress >= 1f && m.ft != null) {
            dot.color = if (m.ft.first < m.ft.second) Kit.ROSSO else Kit.CHAMPAGNE
            c.drawCircle(lastX, lastY, (if (big) 4.5f else 3f) * d, dot)
        }

        // Giulietta: the goal under the finger rises, then sinks back after 3.2 s
        val g = picked
        if (big && g != null && s.exact) {
            val age = System.currentTimeMillis() - pickedAt
            val a = when { age < 220 -> age / 220f; age > Kit.RECEDE_MS -> 1f - ((age - Kit.RECEDE_MS) / 320f); else -> 1f }.coerceIn(0f, 1f)
            if (a > 0f) {
                val gx = x(g.minute.toFloat()); val gy = y((s.points.firstOrNull { it.first >= g.minute + 0.1f } ?: s.points.last()).second)
                hair.color = withAlpha(if (g.ours) Kit.CHAMPAGNE else Kit.ROSSO, 0.6f * a)
                c.drawLine(gx, gy, gx, top - 6 * d * a, hair)
                label.color = withAlpha(if (g.ours) Kit.CHAMPAGNE else Kit.INK, a)
                label.textAlign = when { gx < width * 0.2f -> Paint.Align.LEFT; gx > width * 0.8f -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                c.drawText("${g.minute}'  ${g.scorer.ifEmpty { if (g.ours) "Arsenal" else m.opponent }}", gx, top - 10 * d * a, label)
                postInvalidateOnAnimation()
            } else picked = null
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = match ?: return false
        if (!big || m.goals.isNullOrEmpty()) return false
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val pad = 14 * d
            val minute = ((e.x - pad) / (width - 2 * pad)) * m.shape.end
            val g = m.goals.filter { !it.red }.minByOrNull { abs(it.minute - minute) }
            if (g != null && g != picked) { picked = g; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            pickedAt = System.currentTimeMillis(); invalidate()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return e.action == MotionEvent.ACTION_UP
    }
}
