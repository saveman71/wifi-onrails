package fr.onrails.trainwifi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Vertical route timeline, drawn like the portal's: times on the left, a burgundy line with one
 * dot per stop, the train marker between the last passed stop and the next one, name and status
 * on the right. Pure canvas, no dependency.
 */
class TimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var stops: List<Stop> = emptyList()
    private var nextIndex = -1
    private var segmentFraction = 0.5f

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val rowHeight = dp(60f)
    private val timeColumnWidth = dp(58f)
    private val lineX = timeColumnWidth + dp(22f)
    private val textX = lineX + dp(26f)
    private val dotRadius = dp(7f)

    private val brand = context.getColor(R.color.brand_red)
    private val brandSoft = context.getColor(R.color.brand_red_soft)
    private val surface = context.getColor(R.color.surface)
    private val textPrimary = context.getColor(R.color.text_primary)
    private val textSecondary = context.getColor(R.color.text_secondary)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = brand }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(16f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(16f) }
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(13f) }

    private val trainIcon = context.getDrawable(R.drawable.ic_train)?.mutate()?.apply { setTint(brand) }

    fun setTrip(trip: Trip) {
        stops = trip.stops
        nextIndex = trip.nextStopIndex()
        segmentFraction = trip.segmentFraction()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (paddingTop + paddingBottom + rowHeight * stops.size).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    private fun centerY(index: Int): Float = paddingTop + rowHeight * index + dp(14f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (stops.isEmpty()) return
        val last = stops.lastIndex

        // Line: soft for the whole route, brand for the part already travelled.
        linePaint.color = brandSoft
        canvas.drawLine(lineX, centerY(0), lineX, centerY(last), linePaint)
        val travelledUntil: Float? = when {
            nextIndex < 0 -> null
            nextIndex == 0 -> centerY(0)
            else -> centerY(nextIndex - 1) + (centerY(nextIndex) - centerY(nextIndex - 1)) * segmentFraction
        }
        if (travelledUntil != null) {
            linePaint.color = brand
            canvas.drawLine(lineX, centerY(0), lineX, travelledUntil, linePaint)
        }

        val nameWidth = width - paddingRight - textX
        for (i in stops.indices) {
            val stop = stops[i]
            val cy = centerY(i)
            val passed = nextIndex >= 0 && i < nextIndex
            val highlighted = i == nextIndex || i == last

            // Dot
            if (passed) {
                fillPaint.color = brand
                canvas.drawCircle(lineX, cy, dotRadius, fillPaint)
            } else {
                fillPaint.color = surface
                canvas.drawCircle(lineX, cy, dotRadius, fillPaint)
                canvas.drawCircle(lineX, cy, dotRadius, ringPaint)
            }

            // Time and name share a baseline aligned with the dot.
            val baseline = cy + timePaint.textSize * 0.35f
            timePaint.color = if (passed) textSecondary else textPrimary
            canvas.drawText(Formatting.time(stop.eta), timeColumnWidth, baseline, timePaint)

            namePaint.color = if (passed) textSecondary else textPrimary
            namePaint.typeface = if (highlighted) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val name = TextUtils.ellipsize(stop.name, namePaint, nameWidth, TextUtils.TruncateAt.END)
            canvas.drawText(name, 0, name.length, textX, baseline, namePaint)

            // Status line
            val prefix = when (i) {
                0 -> "Departure"
                last -> "Terminus"
                else -> ""
            }
            val delay = stop.delayMinutes ?: 0
            val status = when {
                delay > 0 -> "+$delay min, planned ${Formatting.time(stop.theoric)}"
                stop.delayMinutes != null -> "on time"
                else -> ""
            }
            statusPaint.color = if (delay > 0) brand else textSecondary
            val statusText = listOf(prefix, status).filter { it.isNotEmpty() }.joinToString(", ")
            if (statusText.isNotEmpty()) {
                canvas.drawText(statusText, textX, baseline + sp(18f), statusPaint)
            }
        }

        // Train marker on the travelled segment.
        if (travelledUntil != null && nextIndex > 0) {
            val r = dp(12f)
            fillPaint.color = surface
            canvas.drawCircle(lineX, travelledUntil, r, fillPaint)
            canvas.drawCircle(lineX, travelledUntil, r, ringPaint)
            trainIcon?.let {
                val half = dp(8f).toInt()
                it.setBounds((lineX - half).toInt(), (travelledUntil - half).toInt(), (lineX + half).toInt(), (travelledUntil + half).toInt())
                it.draw(canvas)
            }
        }
    }
}
