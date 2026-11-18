package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.MedicationHistoryEntity
import com.example.ui.components.EmptyFriendlyState
import com.example.ui.components.WarmSectionCard
import com.example.ui.components.WeeklyAdherenceTrendCard
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class MedicationDateRangePreset(val label: String) {
    TODAY("Today"),
    LAST_7_DAYS("7 Days"),
    LAST_30_DAYS("30 Days"),
    ALL_TIME("All Time"),
    CUSTOM("Custom")
}

enum class MedicationStatusFilter(val label: String) {
    ALL("All"),
    TAKEN("Taken"),
    MISSED("Missed")
}

data class MedicationDateRangeSelection(
    val preset: MedicationDateRangePreset = MedicationDateRangePreset.LAST_7_DAYS,
    val startMillis: Long,
    val endMillis: Long
) {
    fun formattedRangeLabel(): String {
        if (preset == MedicationDateRangePreset.ALL_TIME) return "All recorded days"
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val startStr = fmt.format(Date(startMillis))
        val endStr = fmt.format(Date(endMillis))
        return if (startStr == endStr) startStr else "$startStr – $endStr"
    }

    companion object {
        fun fromPreset(
            preset: MedicationDateRangePreset,
            customUtcStartMillis: Long? = null,
            customUtcEndMillis: Long? = null,
            nowMillis: Long = System.currentTimeMillis()
        ): MedicationDateRangeSelection {
            val endOfToday = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis

            return when (preset) {
                MedicationDateRangePreset.TODAY -> {
                    val startOfToday = startOfLocalDay(nowMillis)
                    MedicationDateRangeSelection(preset, startOfToday, endOfToday)
                }
                MedicationDateRangePreset.LAST_7_DAYS -> {
                    val startCal = Calendar.getInstance().apply {
                        timeInMillis = nowMillis
                        add(Calendar.DAY_OF_YEAR, -6)
                    }
                    MedicationDateRangeSelection(preset, startOfLocalDay(startCal.timeInMillis), endOfToday)
                }
                MedicationDateRangePreset.LAST_30_DAYS -> {
                    val startCal = Calendar.getInstance().apply {
                        timeInMillis = nowMillis
                        add(Calendar.DAY_OF_YEAR, -29)
                    }
                    MedicationDateRangeSelection(preset, startOfLocalDay(startCal.timeInMillis), endOfToday)
                }
                MedicationDateRangePreset.ALL_TIME -> {
                    MedicationDateRangeSelection(preset, 0L, Long.MAX_VALUE)
                }
                MedicationDateRangePreset.CUSTOM -> {
                    val startLocal = if (customUtcStartMillis != null) {
                        utcPickerDayToLocalBoundary(customUtcStartMillis, endOfDay = false)
                    } else {
                        startOfLocalDay(nowMillis)
                    }
                    val endLocal = if (customUtcEndMillis != null) {
                        utcPickerDayToLocalBoundary(customUtcEndMillis, endOfDay = true)
                    } else if (customUtcStartMillis != null) {
                        utcPickerDayToLocalBoundary(customUtcStartMillis, endOfDay = true)
                    } else {
                        endOfToday
                    }
                    MedicationDateRangeSelection(
                        preset = preset,
                        startMillis = minOf(startLocal, endLocal),
                        endMillis = maxOf(startLocal, endLocal)
                    )
                }
            }
        }

        private fun startOfLocalDay(epochMillis: Long): Long {
            return Calendar.getInstance().apply {
                timeInMillis = epochMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun utcPickerDayToLocalBoundary(utcMillis: Long, endOfDay: Boolean): Long {
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = utcMillis
            }
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                if (endOfDay) {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                } else {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }.timeInMillis
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationHistoryScreen(
    historyEntries: List<MedicationHistoryEntity>,
    dateRange: MedicationDateRangeSelection,
    onSelectDateRange: (MedicationDateRangeSelection) -> Unit,
    onMarkEntryTaken: (String) -> Unit,
    onRefreshMissedCheck: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    weeklyHistoryEntries: List<MedicationHistoryEntity> = historyEntries
) {
    var statusFilter by remember { mutableStateOf(MedicationStatusFilter.ALL) }
    var showCustomRangeDialog by remember { mutableStateOf(false) }

    val takenCount = remember(historyEntries) {
        historyEntries.count { it.isTaken }
    }
    val missedCount = remember(historyEntries) {
        historyEntries.count { it.isMissed }
    }
    val totalCount = historyEntries.size
    val adherencePercent = remember(takenCount, totalCount) {
        if (totalCount == 0) 100 else ((takenCount * 100f) / totalCount).toInt()
    }

    val displayedEntries = remember(historyEntries, statusFilter) {
        when (statusFilter) {
            MedicationStatusFilter.ALL -> historyEntries
            MedicationStatusFilter.TAKEN -> historyEntries.filter { it.isTaken }
            MedicationStatusFilter.MISSED -> historyEntries.filter { it.isMissed }
        }
    }

    val groupedByDay = remember(displayedEntries) {
        displayedEntries.groupBy { it.dateKey }
    }

    if (showCustomRangeDialog) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showCustomRangeDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        onSelectDateRange(
                            MedicationDateRangeSelection.fromPreset(
                                preset = MedicationDateRangePreset.CUSTOM,
                                customUtcStartMillis = pickerState.selectedStartDateMillis,
                                customUtcEndMillis = pickerState.selectedEndDateMillis
                            )
                        )
                        showCustomRangeDialog = false
                    },
                    enabled = pickerState.selectedStartDateMillis != null,
                    modifier = Modifier.testTag("confirm_custom_date_range_button")
                ) {
                    Text("Apply Range")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomRangeDialog = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DateRangePicker(
                state = pickerState,
                title = {
                    Text(
                        text = "Select History Date Range",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp)
                    )
                },
                showModeToggle = false,
                modifier = Modifier.height(440.dp)
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Screen Header Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("medication_history_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Safety & Medicines"
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Medication History",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = dateRange.formattedRangeLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onRefreshMissedCheck,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("refresh_medication_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh missed and taken doses",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("medication_history_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Adherence Summary Card with warm organic gradient surface
            item(key = "history_summary") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.surface,
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                    )
                                )
                            )
                            .padding(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Dose Log Summary",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Stored safely on your device",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (totalCount > 0) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(50.dp)
                                    ) {
                                        Text(
                                            text = "$adherencePercent% Taken",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                HistoryStatPill(
                                    label = "Taken",
                                    count = takenCount,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                HistoryStatPill(
                                    label = "Missed",
                                    count = missedCount,
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                HistoryStatPill(
                                    label = "Total",
                                    count = totalCount,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Past-Week Adherence Trend Visualization
            item(key = "history_weekly_trend") {
                WeeklyAdherenceTrendCard(
                    historyEntries = weeklyHistoryEntries,
                    modifier = Modifier.widthIn(max = 640.dp)
                )
            }

            // Date Range & Status Filters
            item(key = "history_filters") {
                WarmSectionCard(modifier = Modifier.widthIn(max = 640.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Date Range",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MedicationDateRangePreset.entries.forEach { preset ->
                            val tag = when (preset) {
                                MedicationDateRangePreset.TODAY -> "date_filter_chip_today"
                                MedicationDateRangePreset.LAST_7_DAYS -> "date_filter_chip_7_days"
                                MedicationDateRangePreset.LAST_30_DAYS -> "date_filter_chip_30_days"
                                MedicationDateRangePreset.ALL_TIME -> "date_filter_chip_all"
                                MedicationDateRangePreset.CUSTOM -> "date_filter_chip_custom"
                            }
                            FilterChip(
                                selected = dateRange.preset == preset,
                                onClick = {
                                    if (preset == MedicationDateRangePreset.CUSTOM) {
                                        showCustomRangeDialog = true
                                    } else {
                                        onSelectDateRange(MedicationDateRangeSelection.fromPreset(preset))
                                    }
                                },
                                leadingIcon = if (preset == MedicationDateRangePreset.CUSTOM) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.CalendarMonth,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else {
                                    null
                                },
                                label = { Text(preset.label) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag(tag)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MedicationStatusFilter.entries.forEach { filter ->
                            val statusTag = when (filter) {
                                MedicationStatusFilter.ALL -> "status_filter_chip_all"
                                MedicationStatusFilter.TAKEN -> "status_filter_chip_taken"
                                MedicationStatusFilter.MISSED -> "status_filter_chip_missed"
                            }
                            FilterChip(
                                selected = statusFilter == filter,
                                onClick = { statusFilter = filter },
                                colors = when (filter) {
                                    MedicationStatusFilter.TAKEN -> FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    MedicationStatusFilter.MISSED -> FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    MedicationStatusFilter.ALL -> FilterChipDefaults.filterChipColors()
                                },
                                label = { Text(filter.label) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag(statusTag)
                            )
                        }
                    }
                }
            }

            if (displayedEntries.isEmpty()) {
                item(key = "history_empty") {
                    WarmSectionCard(modifier = Modifier.widthIn(max = 640.dp)) {
                        EmptyFriendlyState(
                            icon = Icons.Filled.Medication,
                            title = "No medication history in this range",
                            tip = "Try widening the date range above, or mark a daily medicine Taken when reminded."
                        )
                    }
                }
            } else {
                groupedByDay.forEach { (dateKey, dayEntries) ->
                    val headerText = dayEntries.first().formattedDayHeader()
                    item(key = "day_header_$dateKey") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .padding(top = 6.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = headerText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${dayEntries.size} ${if (dayEntries.size == 1) "dose" else "doses"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(
                        items = dayEntries,
                        key = { "hist_${it.id}" }
                    ) { entry ->
                        MedicationHistoryRowCard(
                            entry = entry,
                            onMarkTaken = { onMarkEntryTaken(entry.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryStatPill(
    label: String,
    count: Int,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor
            )
        }
    }
}

@Composable
private fun MedicationHistoryRowCard(
    entry: MedicationHistoryEntity,
    onMarkTaken: () -> Unit
) {
    val isTaken = entry.isTaken
    val borderColor = if (isTaken) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.error
    }
    val badgeBg = if (isTaken) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val badgeFg = if (isTaken) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    WarmSectionCard(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .testTag("history_card_${entry.id}"),
        borderColor = borderColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isTaken) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    contentDescription = if (isTaken) "Taken" else "Missed",
                    tint = badgeFg,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.medicationName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${entry.scheduleSlot} • ${entry.dosage.ifBlank { "Scheduled dose" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isTaken) {
                            "Taken at ${entry.formattedEventTime()}"
                        } else {
                            "Missed • Scheduled ${entry.formattedScheduledTime()}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (!isTaken) {
                Button(
                    onClick = onMarkTaken,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("history_mark_taken_${entry.id}")
                ) {
                    Text("Mark Taken")
                }
            }
        }
    }
}
