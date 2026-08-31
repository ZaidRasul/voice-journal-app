package com.voicejournal.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A single value measured at [timestampMillis]. */
data class TimeSeriesPoint(
    val timestampMillis: Long,
    val value: Double
)

/**
 * A lightweight, dependency-free line chart for timestamped numeric values.
 *
 * The chart is exposed to accessibility services as one focusable element. Its
 * content description summarizes the date range, value range, and overall trend.
 */
class TrendLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BACKGROUND
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRID
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_AXIS
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LINE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE
    }
    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BACKGROUND
        style = Paint.Style.FILL
    }
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LINE
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = sp(16f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LABEL
        textSize = sp(11f)
    }
    private val emptyTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        textSize = sp(15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val emptyBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LABEL
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
    }

    private var chartPoints: List<TimeSeriesPoint> = emptyList()
    private var chartTitle: String = DEFAULT_TITLE
    private var valueUnit: String = ""

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        updateAccessibilityDescription()
    }

    /**
     * Replaces the displayed values. Non-finite values are ignored and values
     * are ordered by timestamp before being drawn.
     */
    fun setPoints(points: List<TimeSeriesPoint>) {
        chartPoints = points
            .asSequence()
            .filter { it.value.isFinite() }
            .sortedBy { it.timestampMillis }
            .toList()
        updateAccessibilityDescription()
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /** Sets the visible and spoken chart title. Blank text falls back to "Trend". */
    fun setChartTitle(title: String) {
        chartTitle = title.trim().ifEmpty { DEFAULT_TITLE }
        updateAccessibilityDescription()
        invalidate()
    }

    /** Adds a short unit, such as "kg", to value labels and accessibility text. */
    fun setValueUnit(unit: String?) {
        valueUnit = unit.orEmpty().trim()
        updateAccessibilityDescription()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = max(suggestedMinimumWidth, dp(320f).roundToInt())
        val desiredHeight = max(suggestedMinimumHeight, dp(240f).roundToInt())
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            dp(12f),
            dp(12f),
            backgroundPaint
        )

        if (chartPoints.isEmpty()) {
            drawEmptyState(canvas)
            return
        }

        val contentLeft = paddingLeft.toFloat() + dp(16f)
        val contentRight = width.toFloat() - paddingRight - dp(16f)
        val contentTop = paddingTop.toFloat() + dp(14f)
        val contentBottom = height.toFloat() - paddingBottom - dp(14f)
        if (contentRight <= contentLeft || contentBottom <= contentTop) return

        val titleBaseline = contentTop - titlePaint.fontMetrics.top
        canvas.drawText(chartTitle, contentLeft, titleBaseline, titlePaint)

        val rawMinimum = chartPoints.minOf { it.value }
        val rawMaximum = chartPoints.maxOf { it.value }
        val axisRange = expandedRange(rawMinimum, rawMaximum)
        val axisMinimum = axisRange.first
        val axisMaximum = axisRange.second

        val largestLabelWidth = (0..HORIZONTAL_GRID_DIVISIONS).maxOf { index ->
            val fraction = index.toDouble() / HORIZONTAL_GRID_DIVISIONS
            labelPaint.measureText(formatValue(axisMaximum - (axisMaximum - axisMinimum) * fraction))
        }
        val xLabelHeight = -labelPaint.fontMetrics.top + labelPaint.fontMetrics.bottom
        val plot = RectF(
            contentLeft + largestLabelWidth + dp(10f),
            titleBaseline + dp(14f),
            contentRight,
            contentBottom - xLabelHeight - dp(8f)
        )

        if (plot.width() < dp(48f) || plot.height() < dp(48f)) return

        drawGridAndValueLabels(canvas, plot, axisMinimum, axisMaximum)
        drawTimeLabels(canvas, plot)
        drawSeries(canvas, plot, axisMinimum, axisMaximum)
    }

    private fun drawEmptyState(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        canvas.drawText(
            "No trend data yet",
            centerX,
            centerY - dp(4f),
            emptyTitlePaint
        )
        canvas.drawText(
            "Add dated numeric entries to see a chart",
            centerX,
            centerY + dp(20f),
            emptyBodyPaint
        )
    }

    private fun drawGridAndValueLabels(
        canvas: Canvas,
        plot: RectF,
        axisMinimum: Double,
        axisMaximum: Double
    ) {
        labelPaint.textAlign = Paint.Align.RIGHT
        val labelOffset = -(labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
        for (index in 0..HORIZONTAL_GRID_DIVISIONS) {
            val fraction = index.toFloat() / HORIZONTAL_GRID_DIVISIONS
            val y = plot.top + plot.height() * fraction
            val value = axisMaximum - (axisMaximum - axisMinimum) * fraction
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            canvas.drawText(
                formatValue(value),
                plot.left - dp(7f),
                y + labelOffset,
                labelPaint
            )
        }

        for (index in 0..VERTICAL_GRID_DIVISIONS) {
            val fraction = index.toFloat() / VERTICAL_GRID_DIVISIONS
            val x = plot.left + plot.width() * fraction
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
        }
        canvas.drawLine(plot.left, plot.top, plot.left, plot.bottom, axisPaint)
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axisPaint)
    }

    private fun drawTimeLabels(canvas: Canvas, plot: RectF) {
        val minimumTime = chartPoints.first().timestampMillis
        val maximumTime = chartPoints.last().timestampMillis
        val baseline = plot.bottom + dp(7f) - labelPaint.fontMetrics.top
        val formatter = timeAxisFormatter(maximumTime - minimumTime)

        if (minimumTime == maximumTime) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(formatter.format(Date(minimumTime)), plot.centerX(), baseline, labelPaint)
            return
        }

        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(formatter.format(Date(minimumTime)), plot.left, baseline, labelPaint)

        val middleTime = minimumTime + (maximumTime - minimumTime) / 2L
        val middleLabel = formatter.format(Date(middleTime))
        val leftLabelWidth = labelPaint.measureText(formatter.format(Date(minimumTime)))
        val rightLabelWidth = labelPaint.measureText(formatter.format(Date(maximumTime)))
        val middleLabelWidth = labelPaint.measureText(middleLabel)
        if (plot.width() > leftLabelWidth + middleLabelWidth + rightLabelWidth + dp(32f)) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(middleLabel, plot.centerX(), baseline, labelPaint)
        }

        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatter.format(Date(maximumTime)), plot.right, baseline, labelPaint)
    }

    private fun drawSeries(
        canvas: Canvas,
        plot: RectF,
        axisMinimum: Double,
        axisMaximum: Double
    ) {
        val minimumTime = chartPoints.first().timestampMillis
        val maximumTime = chartPoints.last().timestampMillis
        val path = Path()
        val coordinates = ArrayList<Pair<Float, Float>>(chartPoints.size)

        chartPoints.forEachIndexed { index, point ->
            val xFraction = if (minimumTime == maximumTime) {
                0.5
            } else {
                (point.timestampMillis.toDouble() - minimumTime.toDouble()) /
                    (maximumTime.toDouble() - minimumTime.toDouble())
            }
            val yFraction = (point.value - axisMinimum) / (axisMaximum - axisMinimum)
            val x = plot.left + plot.width() * xFraction.toFloat()
            val y = plot.bottom - plot.height() * yFraction.toFloat()
            coordinates += x to y
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        val checkpoint = canvas.save()
        canvas.clipRect(plot)
        if (coordinates.size > 1) canvas.drawPath(path, linePaint)
        coordinates.forEach { (x, y) ->
            canvas.drawCircle(x, y, dp(3.5f), pointFillPaint)
            canvas.drawCircle(x, y, dp(3.5f), pointStrokePaint)
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun expandedRange(minimum: Double, maximum: Double): Pair<Double, Double> {
        if (minimum != maximum) {
            val padding = (maximum - minimum) * 0.12
            return (minimum - padding) to (maximum + padding)
        }

        val padding = max(abs(minimum) * 0.1, 1.0)
        return (minimum - padding) to (maximum + padding)
    }

    private fun formatValue(value: Double): String {
        val normalizedValue = if (abs(value) < 0.0000001) 0.0 else value
        val formatter = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
        }
        val formatted = formatter.format(normalizedValue)
        return if (valueUnit.isEmpty()) formatted else "$formatted $valueUnit"
    }

    private fun timeAxisFormatter(spanMillis: Long): DateFormat {
        val pattern = when {
            spanMillis < MILLIS_PER_DAY -> "HH:mm"
            spanMillis < 120L * MILLIS_PER_DAY -> "MMM d"
            else -> "MMM yyyy"
        }
        return SimpleDateFormat(pattern, Locale.getDefault())
    }

    private fun updateAccessibilityDescription() {
        contentDescription = buildAccessibilityDescription()
    }

    private fun buildAccessibilityDescription(): String {
        if (chartPoints.isEmpty()) return "$chartTitle chart. No data available."

        val spokenDate = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        )
        if (chartPoints.size == 1) {
            val point = chartPoints.first()
            return "$chartTitle chart. One data point on ${spokenDate.format(Date(point.timestampMillis))} " +
                "with value ${formatValue(point.value)}."
        }

        val first = chartPoints.first()
        val last = chartPoints.last()
        val minimum = chartPoints.minOf { it.value }
        val maximum = chartPoints.maxOf { it.value }
        val direction = when {
            last.value > first.value -> "increased"
            last.value < first.value -> "decreased"
            else -> "did not change"
        }
        return "$chartTitle chart. ${chartPoints.size} data points from " +
            "${spokenDate.format(Date(first.timestampMillis))} to " +
            "${spokenDate.format(Date(last.timestampMillis))}. Values range from " +
            "${formatValue(minimum)} to ${formatValue(maximum)}. Overall, the value $direction " +
            "from ${formatValue(first.value)} to ${formatValue(last.value)}."
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private companion object {
        const val DEFAULT_TITLE = "Trend"
        const val HORIZONTAL_GRID_DIVISIONS = 4
        const val VERTICAL_GRID_DIVISIONS = 2
        const val MILLIS_PER_DAY = 86_400_000L

        val COLOR_BACKGROUND: Int = Color.rgb(255, 255, 255)
        val COLOR_TEXT: Int = Color.rgb(23, 33, 43)
        val COLOR_LABEL: Int = Color.rgb(71, 85, 105)
        val COLOR_GRID: Int = Color.rgb(226, 232, 240)
        val COLOR_AXIS: Int = Color.rgb(100, 116, 139)
        val COLOR_LINE: Int = Color.rgb(15, 118, 110)
    }
}
