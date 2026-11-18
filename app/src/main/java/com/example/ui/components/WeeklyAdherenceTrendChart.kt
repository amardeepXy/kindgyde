package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.MedicationHistoryEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class WeeklyAdherenceDayPoint(
    val dateKey: String,
    val shortDayLabel: String,
    val fullDateLabel: String,
    val takenCount: Int,
    val missedCount: Int,
    val isToday: Boolean
) {
    val totalCount: Int
        get() = takenCount + missedCount

    val adherencePercent: Float?
        get() = if (totalCount > 0) (takenCount * 100f) / totalCount else null
}

fun buildWeeklyAdherencePoints(
    entries: List<MedicationHistoryEntity>,
    nowMillis: Long = System.currentTimeMillis()
): List<WeeklyAdherenceDayPoint> {
    val byDateKey = entries.groupBy { it.dateKey }
    val shortDayFormat = SimpleDateFormat("EEE", Locale.US)
    val fullDateFormat = SimpleDateFormat("EEE, MMM d", Locale.US)
    val todayKey = MedicationHistoryEntity.formatDateKey(nowMillis)

    return (-6..0).map { offset ->
        val dayCal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayMs = dayCal.timeInMillis
        val dateKey = MedicationHistoryEntity.formatDateKey(dayMs)
        val dayEntries = byDateKey[dateKey].orEmpty()
        val isToday = dateKey == todayKey
        WeeklyAdherenceDayPoint(
            dateKey = dateKey,
            shortDayLabel = if (isToday) "Today" else shortDayFormat.format(Date(dayMs)),
            fullDateLabel = if (isToday) "Today • ${fullDateFormat.format(Date(dayMs))}" else fullDateFormat.format(Date(dayMs)),
            takenCount = dayEntries.count { it.isTaken },
            missedCount = dayEntries.count { it.isMissed },
            isToday = isToday
        )
    }
}

@Composable
fun WeeklyAdherenceTrendCard(
    historyEntries: List<MedicationHistoryEntity>,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis()
) {
    val points = remember(historyEntries, nowMillis) {
        buildWeeklyAdherencePoints(entries = historyEntries, nowMillis = nowMillis)
    }
    val totalTakenWeek = remember(points) { points.sumOf { it.takenCount } }
    val totalMissedWeek = remember(points) { points.sumOf { it.missedCount } }
    val totalDosesWeek = totalTakenWeek + totalMissedWeek
    val weeklyAdherencePct = remember(totalTakenWeek, totalDosesWeek) {
        if (totalDosesWeek > 0) ((totalTakenWeek * 100f) / totalDosesWeek).roundToInt() else null
    }
    val defaultSelectedKey = remember(points) {
        points.lastOrNull { it.totalCount > 0 }?.dateKey ?: points.last().dateKey
    }
    var selectedDateKey by remember(defaultSelectedKey) { mutableStateOf(defaultSelectedKey) }
    LaunchedEffect(points) {
        if (points.none { it.dateKey == selectedDateKey }) {
            selectedDateKey = defaultSelectedKey
        }
    }
    val selectedPoint = points.firstOrNull { it.dateKey == selectedDateKey } ?: points.last()

    val takenColor = MaterialTheme.colorScheme.secondary
    val missedColor = MaterialTheme.colorScheme.error
    val trendLineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val emptyTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val selectedHighlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
    val nodeCenterColor = MaterialTheme.colorScheme.surface

    WarmSectionCard(
        modifier = modifier.testTag("weekly_adherence_chart_card"),
        borderColor = MaterialTheme.colorScheme.outline
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "7-Day Adherence Trend",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Past week taken vs. missed doses",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                color = if (weeklyAdherencePct == null) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else if (weeklyAdherencePct >= 80) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                shape = RoundedCornerShape(50.dp),
                modifier = Modifier.testTag("weekly_adherence_avg_badge")
            ) {
                Text(
                    text = weeklyAdherencePct?.let { "$it% 7d Avg" } ?: "No 7d logs",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (weeklyAdherencePct == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else if (weeklyAdherencePct >= 80) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Interactive Selected-Day Breakdown Banner
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("weekly_trend_selected_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedPoint.fullDateLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                val detailText = if (selectedPoint.totalCount == 0) {
                    "No doses logged"
                } else {
                    val pct = selectedPoint.adherencePercent?.roundToInt() ?: 0
                    "${selectedPoint.takenCount} taken • ${selectedPoint.missedCount} missed ($pct%)"
                }
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        selectedPoint.totalCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                        selectedPoint.missedCount == 0 -> MaterialTheme.colorScheme.secondary
                        selectedPoint.takenCount == 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Native Jetpack Compose Canvas (Stacked Taken/Missed Bars + Adherence % Trend Line)
        val maxDailyTotal = remember(points) {
            max(1, points.maxOfOrNull { it.totalCount } ?: 1)
        }
        val selectedIndex = points.indexOfFirst { it.dateKey == selectedPoint.dateKey }.coerceAtLeast(0)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .height(144.dp)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "100%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "50%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "0%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(144.dp)
                    .testTag("weekly_adherence_canvas")
                    .semantics {
                        contentDescription =
                            "7-day medication adherence chart showing $totalTakenWeek taken and $totalMissedWeek missed doses"
                    }
            ) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f || points.isEmpty()) return@Canvas

                val topPad = 10.dp.toPx()
                val bottomPad = 10.dp.toPx()
                val plotHeight = (h - topPad - bottomPad).coerceAtLeast(1f)
                val slotWidth = w / points.size.toFloat()
                val barWidth = (slotWidth * 0.44f).coerceIn(10.dp.toPx(), 28.dp.toPx())
                val barCorner = 6.dp.toPx()
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                // Horizontal reference gridlines (100%, 50%, 0%)
                listOf(0f, 0.5f, 1f).forEach { fraction ->
                    val y = topPad + plotHeight * fraction
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = if (fraction == 1f) null else dashEffect
                    )
                }

                // Selected column highlight + Stacked daily bars (Taken at bottom, Missed on top)
                points.forEachIndexed { idx, point ->
                    val centerX = slotWidth * idx + slotWidth / 2f
                    if (idx == selectedIndex) {
                        val highlightWidth = slotWidth * 0.78f
                        drawRoundRect(
                            color = selectedHighlightColor,
                            topLeft = Offset(centerX - highlightWidth / 2f, topPad * 0.4f),
                            size = Size(highlightWidth, h - topPad * 0.8f),
                            cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
                        )
                    }

                    val barLeft = centerX - barWidth / 2f
                    val baseY = topPad + plotHeight

                    if (point.totalCount == 0) {
                        val stubHeight = 6.dp.toPx()
                        drawRoundRect(
                            color = emptyTrackColor,
                            topLeft = Offset(barLeft, baseY - stubHeight),
                            size = Size(barWidth, stubHeight),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                    } else {
                        val totalBarHeight = (point.totalCount.toFloat() / maxDailyTotal.toFloat()) * plotHeight
                        val takenHeight = (point.takenCount.toFloat() / point.totalCount.toFloat()) * totalBarHeight
                        val missedHeight = totalBarHeight - takenHeight

                        // Background container track for full adherence height context
                        drawRoundRect(
                            color = emptyTrackColor.copy(alpha = 0.35f),
                            topLeft = Offset(barLeft, topPad),
                            size = Size(barWidth, plotHeight),
                            cornerRadius = CornerRadius(barCorner, barCorner)
                        )

                        if (takenHeight > 0f) {
                            drawRoundRect(
                                color = takenColor,
                                topLeft = Offset(barLeft, baseY - takenHeight),
                                size = Size(barWidth, takenHeight),
                                cornerRadius = CornerRadius(barCorner, barCorner)
                            )
                        }

                        if (missedHeight > 0f) {
                            val gap = if (takenHeight > 0f) 2.dp.toPx().coerceAtMost(missedHeight * 0.3f) else 0f
                            val missedTop = baseY - totalBarHeight
                            drawRoundRect(
                                color = missedColor,
                                topLeft = Offset(barLeft, missedTop),
                                size = Size(barWidth, (missedHeight - gap).coerceAtLeast(2.dp.toPx())),
                                cornerRadius = CornerRadius(barCorner, barCorner)
                            )
                        }
                    }
                }

                // Adherence % trend line connecting days with logged doses
                val linePoints = points.mapIndexedNotNull { idx, point ->
                    val pct = point.adherencePercent ?: return@mapIndexedNotNull null
                    val x = slotWidth * idx + slotWidth / 2f
                    val y = topPad + plotHeight * (1f - (pct / 100f).coerceIn(0f, 1f))
                    Offset(x, y)
                }

                if (linePoints.size >= 2) {
                    val linePath = Path().apply {
                        moveTo(linePoints.first().x, linePoints.first().y)
                        for (i in 1 until linePoints.size) {
                            val prev = linePoints[i - 1]
                            val curr = linePoints[i]
                            val midX = (prev.x + curr.x) / 2f
                            cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                        }
                    }
                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(linePoints.last().x, topPad + plotHeight)
                        lineTo(linePoints.first().x, topPad + plotHeight)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                trendLineColor.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            startY = topPad,
                            endY = topPad + plotHeight
                        )
                    )
                    drawPath(
                        path = linePath,
                        color = trendLineColor,
                        style = Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Circular markers at each logged day's adherence percentage
                linePoints.forEach { pt ->
                    drawCircle(
                        color = trendLineColor,
                        radius = 5.5.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = nodeCenterColor,
                        radius = 2.8.dp.toPx(),
                        center = pt
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Interactive 7-day X-Axis Selector Pills (48dp min touch target height)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            points.forEach { point ->
                val isSelected = point.dateKey == selectedPoint.dateKey
                Surface(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedDateKey = point.dateKey }
                        .testTag("trend_day_${point.dateKey}")
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = point.shortDayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected || point.isToday) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = point.adherencePercent?.let { "${it.roundToInt()}%" } ?: "–",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                point.totalCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                point.missedCount == 0 -> MaterialTheme.colorScheme.secondary
                                point.takenCount == 0 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Legend Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChartLegendItem(color = takenColor, label = "Taken", isLine = false)
            ChartLegendItem(color = missedColor, label = "Missed", isLine = false)
            ChartLegendItem(color = trendLineColor, label = "Adherence %", isLine = true)
        }
    }
}

@Composable
private fun ChartLegendItem(
    color: Color,
    label: String,
    isLine: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isLine) {
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
