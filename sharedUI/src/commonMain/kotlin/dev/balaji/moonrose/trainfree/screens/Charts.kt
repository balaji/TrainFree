package dev.balaji.moonrose.trainfree.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisTickComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.DashedShape
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import dev.balaji.moonrose.trainfree.calendar.WeekMath
import dev.balaji.moonrose.trainfree.insights.BmiCategory
import dev.balaji.moonrose.trainfree.insights.BmiClassification
import dev.balaji.moonrose.trainfree.insights.HistoryWindow
import dev.balaji.moonrose.trainfree.insights.InsightMath
import dev.balaji.moonrose.trainfree.insights.SparkPoint
import dev.balaji.moonrose.trainfree.insights.SparkSeries
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

// Categorical palette for series lines and the body-part pie, in a fixed
// CVD-safe order with a separate step set per color mode.
internal val PieColorsLight = listOf(
    Color(0xFF2A78D6), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFF008300),
    Color(0xFF4A3AA7), Color(0xFFE34948), Color(0xFFE87BA4), Color(0xFFEB6834),
)
internal val PieColorsDark = listOf(
    Color(0xFF3987E5), Color(0xFF199E70), Color(0xFFC98500), Color(0xFF008300),
    Color(0xFF9085E9), Color(0xFFE66767), Color(0xFFD55181), Color(0xFFD95926),
)
internal val PieOtherColor = Color(0xFF898781)

@Composable
internal fun seriesColor(index: Int): Color {
    val palette = if (isSystemInDarkTheme()) PieColorsDark else PieColorsLight
    return palette[index % palette.size]
}

private val ProgressionLabelSpacingKey = ExtraStore.Key<Int>()
private val ProgressionLabelOffsetKey = ExtraStore.Key<Int>()
private val ProgressionShowYearKey = ExtraStore.Key<Boolean>()
private val ProgressionShowPctChangeKey = ExtraStore.Key<Boolean>()

/**
 * Ensures at least a 2-unit span on integer boundaries, allowing negative bounds
 * when %-change mode drops below zero.
 */
internal fun progressionYAxisBounds(minY: Double, maxY: Double): Pair<Double, Double> {
    if (maxY - minY >= 2.0) return minY to maxY
    if (minY >= 0.0) return yAxisBounds(minY, maxY)
    val center = round((minY + maxY) / 2.0)
    if (center - 1.0 <= minY && center + 1.0 >= maxY) {
        return (center - 1.0) to (center + 1.0)
    }
    val lo = floor(minY)
    val hi = maxOf(ceil(maxY), lo + 2.0)
    return lo to hi
}

private val ProgressionYRangeProvider = object : CartesianLayerRangeProvider {
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) =
        progressionYAxisBounds(minY, maxY).first

    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) =
        progressionYAxisBounds(minY, maxY).second
}

private val SparklineYRangeProvider = object : CartesianLayerRangeProvider {
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) =
        yAxisBounds(minY, maxY).first

    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) =
        yAxisBounds(minY, maxY).second
}

/**
 * Overlaid Vico line chart for 1–3 series sharing one x/y domain, with y-axis
 * labels and weekly date ticks. In %-change mode every series is re-based to
 * percent moved since its first week.
 */
@Composable
internal fun ProgressionChart(
    series: List<SparkSeries>,
    showPctChange: Boolean,
    modifier: Modifier = Modifier,
) {
    val lines = series.mapIndexedNotNull { index, s ->
        if (s.points.size < 2) return@mapIndexedNotNull null
        val pts = if (showPctChange) {
            val base = s.points.first().value
            s.points.map { SparkPoint(it.epochDay, if (base == 0.0) 0.0 else (it.value / base - 1) * 100) }
        } else {
            s.points
        }
        index to pts
    }
    if (lines.isEmpty()) return

    val allPoints = lines.flatMap { it.second }
    val minX = allPoints.minOf { it.epochDay }
    val maxX = allPoints.maxOf { it.epochDay }
    val weekSpan = ((maxX - minX) / HistoryWindow.WEEK_DAYS).toInt().coerceAtLeast(0)
    val labelSpacing = HistoryWindow.labelSpacingWeeks(weekSpan)
    val labelOffset = weekSpan % labelSpacing
    val showYear = HistoryWindow.spansYears(
        listOf(SparkPoint(minX, 0.0), SparkPoint(maxX, 0.0)),
    )

    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val months = (1..12).map { monthAbbreviation(it) }
    val lineComponents = lines.map { (colorIndex, _) ->
        LineCartesianLayer.rememberLine(
            LineCartesianLayer.LineFill.single(Fill(seriesColor(colorIndex))),
        )
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(lines, showPctChange, labelSpacing, labelOffset, showYear) {
        modelProducer.runTransaction {
            lineSeries {
                lines.forEach { (_, pts) ->
                    series(x = pts.map { it.epochDay.toDouble() }, y = pts.map { it.value })
                }
            }
            extras {
                it[ProgressionLabelSpacingKey] = labelSpacing
                it[ProgressionLabelOffsetKey] = labelOffset
                it[ProgressionShowYearKey] = showYear
                it[ProgressionShowPctChangeKey] = showPctChange
            }
        }
    }

    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(lineComponents),
            rangeProvider = ProgressionYRangeProvider,
        ),
        startAxis = VerticalAxis.rememberStart(
            line = null,
            tick = null,
            label = rememberAxisLabelComponent(style = labelStyle),
            guideline = rememberAxisGuidelineComponent(fill = Fill(axisColor)),
            valueFormatter = remember {
                CartesianValueFormatter { context, value, _ ->
                    val pct = context.model.extraStore.getOrNull(ProgressionShowPctChangeKey) == true
                    val formatted = InsightMath.formatNumber(value, 0)
                    if (pct) "$formatted%" else formatted
                }
            },
            itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 3 }) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            line = rememberAxisLineComponent(fill = Fill(axisColor)),
            tick = rememberAxisTickComponent(fill = Fill(axisColor)),
            label = rememberAxisLabelComponent(style = labelStyle),
            guideline = null,
            valueFormatter = remember(months) {
                CartesianValueFormatter { context, value, _ ->
                    val useYear = context.model.extraStore.getOrNull(ProgressionShowYearKey) == true
                    if (useYear) formatMonthYear(value.toLong(), months) else formatMonthDay(value.toLong(), months)
                }
            },
            itemPlacer = remember {
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { (it.getOrNull(ProgressionLabelSpacingKey) ?: 1).coerceAtLeast(1) },
                    offset = { (it.getOrNull(ProgressionLabelOffsetKey) ?: 0).coerceAtLeast(0) },
                    addExtremeLabelPadding = false,
                )
            },
        ),
        getXStep = { _, _, _ -> HistoryWindow.WEEK_DAYS.toDouble() },
    )

    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
    )
}

private val MonthAbbrev = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Formats an epoch day as `MMM-dd` (e.g. `Nov-14`). */
internal fun formatMonthDay(epochDay: Long, months: List<String> = MonthAbbrev): String {
    val parts = WeekMath.dayParts(epochDay)
    return "${months[parts.monthOfYear - 1]}-${parts.dayOfMonth.toString().padStart(2, '0')}"
}

/** Formats an epoch day as `MMM yy` (e.g. `Nov 26`), for axes spanning more than a year. */
internal fun formatMonthYear(epochDay: Long, months: List<String> = MonthAbbrev): String {
    val parts = WeekMath.dayParts(epochDay)
    return "${months[parts.monthOfYear - 1]} ${(parts.year.mod(100)).toString().padStart(2, '0')}"
}

@Composable
internal fun Sparkline(points: List<SparkPoint>, modifier: Modifier = Modifier) {
    // Vico's lineSeries expects strictly increasing x-values; deduplicate by epochDay
    // in case multiple entries (e.g. body weight) were logged on the same day.
    val ordered = remember(points) {
        points.groupBy { it.epochDay }
            .entries
            .sortedBy { it.key }
            .map { SparkPoint(it.key, it.value.last().value) }
    }
    if (ordered.size < 2) return

    val lineColor = MaterialTheme.colorScheme.primary
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(ordered) {
        modelProducer.runTransaction {
            lineSeries {
                series(x = ordered.map { it.epochDay.toDouble() }, y = ordered.map { it.value })
            }
        }
    }

    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(
                LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(Fill(lineColor))),
            ),
            rangeProvider = SparklineYRangeProvider,
        ),
        getXStep = { _, _, _ -> 1.0 },
    )

    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
    )
}

/**
 * 8-week bar chart for weekly training volume, anchored at 0 with compact
 * Y-axis labels, weekly date ticks, and an optional dashed average line.
 */
@Composable
internal fun WeeklyVolumeBars(
    points: List<SparkPoint>,
    referenceValue: Double?,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val avgLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val months = (1..12).map { monthAbbreviation(it) }
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        modelProducer.runTransaction {
            columnSeries {
                series(x = points.map { it.epochDay.toDouble() }, y = points.map { it.value })
            }
        }
    }

    val avgLineComponent = rememberLineComponent(
        fill = Fill(avgLineColor),
        thickness = 1.dp,
        shape = remember { DashedShape(shape = RoundedCornerShape(0.dp), dashLength = 4.dp, gapLength = 3.dp) },
    )
    val decorations = remember(referenceValue, avgLineComponent) {
        if (referenceValue != null && referenceValue > 0.0) {
            listOf(HorizontalLine(y = { referenceValue }, line = avgLineComponent))
        } else {
            emptyList()
        }
    }

    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(
            columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                rememberLineComponent(
                    fill = Fill(barColor),
                    thickness = 16.dp,
                    shape = RoundedCornerShape(4.dp),
                ),
            ),
            rangeProvider = remember { CartesianLayerRangeProvider.fixed(minY = 0.0) },
        ),
        startAxis = VerticalAxis.rememberStart(
            line = null,
            tick = null,
            label = rememberAxisLabelComponent(style = labelStyle),
            guideline = rememberAxisGuidelineComponent(fill = Fill(axisColor)),
            valueFormatter = remember {
                CartesianValueFormatter { _, value, _ ->
                    if (value >= 1_000.0) {
                        val k = value / 1_000.0
                        "${InsightMath.formatNumber(k, 1)}k"
                    } else {
                        InsightMath.formatNumber(value, 0)
                    }
                }
            },
            itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 3 }) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            line = rememberAxisLineComponent(fill = Fill(axisColor)),
            tick = rememberAxisTickComponent(fill = Fill(axisColor)),
            label = rememberAxisLabelComponent(style = labelStyle),
            guideline = null,
            valueFormatter = remember(months) {
                CartesianValueFormatter { _, value, _ ->
                    formatMonthDay(value.toLong(), months)
                }
            },
            itemPlacer = remember {
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { 3 },
                    offset = { 1 },
                    addExtremeLabelPadding = true,
                )
            },
        ),
        decorations = decorations,
        getXStep = { _, _, _ -> HistoryWindow.WEEK_DAYS.toDouble() },
    )

    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
    )
}

// Health-band palette for the BMI spectrum, sharing the categorical hues used
// elsewhere: blue (underweight), green (normal), amber (overweight), red (obese).
private val BmiColorsLight = mapOf(
    BmiCategory.UNDERWEIGHT to Color(0xFF2A78D6),
    BmiCategory.NORMAL to Color(0xFF1BAF7A),
    BmiCategory.OVERWEIGHT to Color(0xFFEDA100),
    BmiCategory.OBESE to Color(0xFFE34948),
)
private val BmiColorsDark = mapOf(
    BmiCategory.UNDERWEIGHT to Color(0xFF3987E5),
    BmiCategory.NORMAL to Color(0xFF199E70),
    BmiCategory.OVERWEIGHT to Color(0xFFC98500),
    BmiCategory.OBESE to Color(0xFFE66767),
)

/**
 * Horizontal color-banded scale from [BmiCategory.CHART_MIN] to
 * [BmiCategory.CHART_MAX] with a marker at the calculated [bmi]. Each band is
 * one category under [classification]; the marker is clamped to the drawn
 * range so extreme values still land at an end rather than off-canvas.
 */
@Composable
internal fun BmiSpectrum(bmi: Double, classification: BmiClassification, modifier: Modifier = Modifier) {
    val colors = if (isSystemInDarkTheme()) BmiColorsDark else BmiColorsLight
    val markerColor = MaterialTheme.colorScheme.onSurface
    val trackColor = MaterialTheme.colorScheme.surface
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        val min = BmiCategory.CHART_MIN
        val max = BmiCategory.CHART_MAX
        val span = (max - min).toFloat()
        fun xFor(value: Double) = ((value.coerceIn(min, max) - min) / span * size.width).toFloat()

        val barTop = 14.dp.toPx()
        val barBottom = size.height
        val radius = (barBottom - barTop) / 2f
        val clip = Path().apply {
            addRoundRect(RoundRect(0f, barTop, size.width, barBottom, CornerRadius(radius, radius)))
        }
        clipPath(clip) {
            BmiCategory.entries.forEachIndexed { i, category ->
                val start = if (i == 0) min else classification.lowerBound(category)
                val end = if (i == BmiCategory.entries.lastIndex) {
                    max
                } else {
                    classification.lowerBound(BmiCategory.entries[i + 1])
                }
                val left = xFor(start)
                drawRect(
                    color = colors.getValue(category),
                    topLeft = Offset(left, barTop),
                    size = Size(xFor(end) - left, barBottom - barTop),
                )
            }
        }

        // Downward triangle above the bar plus a hairline through it, both
        // ringed in the track color so the marker reads over any band.
        val markerX = xFor(bmi)
        val half = 6.dp.toPx()
        val triangle = Path().apply {
            moveTo(markerX - half, 0f)
            lineTo(markerX + half, 0f)
            lineTo(markerX, barTop)
            close()
        }
        drawPath(triangle, trackColor, style = Stroke(width = 3.dp.toPx()))
        drawPath(triangle, markerColor)
        drawLine(
            trackColor,
            start = Offset(markerX, barTop),
            end = Offset(markerX, barBottom),
            strokeWidth = 4.dp.toPx(),
        )
        drawLine(
            markerColor,
            start = Offset(markerX, barTop),
            end = Offset(markerX, barBottom),
            strokeWidth = 2.dp.toPx(),
        )
    }
}
