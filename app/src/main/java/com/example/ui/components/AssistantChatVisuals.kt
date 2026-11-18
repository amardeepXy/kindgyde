package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.GuideSessionCoordinator
import com.example.data.model.CalendarEvent
import com.example.data.model.ConversationMessage
import com.example.data.model.MedicationItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ConversationThreadSummary(
    val threadId: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val messageCount: Int,
    val lastUpdatedMillis: Long
)

fun buildConversationSummaries(
    allMessages: List<ConversationMessage>
): List<ConversationThreadSummary> {
    if (allMessages.isEmpty()) return emptyList()

    val grouped = allMessages.groupBy { msg ->
        msg.threadId.ifBlank { GuideSessionCoordinator.LEGACY_THREAD_ID }
    }

    return grouped.entries.mapNotNull { (threadId, msgs) ->
        if (msgs.isEmpty()) return@mapNotNull null
        val sortedMsgs = msgs.sortedBy { it.createdAt?.toDate()?.time ?: 0L }
        val firstUserMsg = sortedMsgs.firstOrNull { it.role == "user" }
        val lastMsg = sortedMsgs.last()
        val lastMillis = lastMsg.createdAt?.toDate()?.time ?: 0L

        val rawTitle = when {
            threadId == GuideSessionCoordinator.LEGACY_THREAD_ID && firstUserMsg == null -> "Earlier Conversation"
            firstUserMsg != null -> cleanSnippet(firstUserMsg.text)
            else -> cleanSnippet(sortedMsgs.first().text)
        }.ifBlank { "AI Assistant Chat" }

        val title = if (rawTitle.length > 48) rawTitle.take(45).trimEnd() + "..." else rawTitle
        val rawPreview = cleanSnippet(lastMsg.text).ifBlank { "..." }
        val preview = if (rawPreview.length > 82) rawPreview.take(79).trimEnd() + "..." else rawPreview

        ConversationThreadSummary(
            threadId = threadId,
            title = title,
            preview = preview,
            timeLabel = formatThreadTime(lastMillis),
            messageCount = sortedMsgs.size,
            lastUpdatedMillis = lastMillis
        )
    }.sortedByDescending { it.lastUpdatedMillis }
}

private fun cleanSnippet(raw: String): String {
    return raw
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^>\\s*", RegexOption.MULTILINE), "")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun formatThreadTime(millis: Long): String {
    if (millis <= 0L) return "Recent"
    val nowCal = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "h:mm a" else "MMM d"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListBottomSheet(
    summaries: List<ConversationThreadSummary>,
    activeConversationId: String,
    activeThreadMessageCount: Int,
    onSelectConversation: (String) -> Unit,
    onStartNewConversation: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Conversations",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Switch between your chats or start fresh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        onStartNewConversation()
                        onDismiss()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("sheet_new_conversation_button"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddComment,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("New Chat", maxLines = 1)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 440.dp)
                    .testTag("conversations_sheet_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                if (activeThreadMessageCount == 0) {
                    item(key = "current_fresh_draft") {
                        ConversationThreadRowCard(
                            title = "New Conversation",
                            preview = "Fresh chat started — ask KindGuide anything or use Live.",
                            timeLabel = "Now",
                            badgeText = "Active",
                            isSelected = true,
                            onClick = onDismiss,
                            modifier = Modifier.testTag("conversation_item_active_draft")
                        )
                    }
                }

                if (summaries.isEmpty()) {
                    item(key = "empty_history_notice") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "No previous chats yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Send a message or start a Live session and your conversation threads will appear here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = summaries,
                        key = { it.threadId }
                    ) { thread ->
                        val isSelected = thread.threadId == activeConversationId
                        ConversationThreadRowCard(
                            title = thread.title,
                            preview = thread.preview,
                            timeLabel = thread.timeLabel,
                            badgeText = if (isSelected) {
                                "Active • ${thread.messageCount} msgs"
                            } else {
                                "${thread.messageCount} msg${if (thread.messageCount == 1) "" else "s"}"
                            },
                            isSelected = isSelected,
                            onClick = {
                                onSelectConversation(thread.threadId)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("conversation_item_${thread.threadId}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationThreadRowCard(
    title: String,
    preview: String,
    timeLabel: String,
    badgeText: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Forum,
                    contentDescription = null,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Surface(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

/**
 * Interactive Visual Summary Card embedded inside the chat history list.
 * Displays upcoming medication reminders (with live Took It / Taken toggle buttons)
 * and upcoming calendar events (with interactive Ask AI & Open Calendar actions)
 * instead of plain text summaries.
 */
@Composable
fun InteractiveCareSummaryCard(
    medications: List<MedicationItem>,
    events: List<CalendarEvent>,
    onToggleMedication: (medId: String, taken: Boolean) -> Unit,
    onOpenMedsTab: () -> Unit,
    onOpenCalendarTab: () -> Unit,
    onAskAboutItem: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    val pendingCount = medications.count { !it.takenToday }

    WarmSectionCard(
        modifier = modifier.testTag("care_visual_summary_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.EventNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Reminders & Schedule",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SummaryStatusBadge(
                            text = if (medications.isEmpty()) {
                                "No meds"
                            } else if (pendingCount > 0) {
                                "$pendingCount med due"
                            } else {
                                "Meds done"
                            },
                            highlighted = pendingCount > 0
                        )
                        SummaryStatusBadge(
                            text = "${events.size} event${if (events.size == 1) "" else "s"}",
                            highlighted = false
                        )
                    }
                }
            }

            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("toggle_care_summary_button")
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse summary" else "Expand summary"
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // --- Medication Reminders Section ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Medication Reminders",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = onOpenMedsTab,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("summary_all_meds_button")
                    ) {
                        Text("All Meds")
                    }
                }

                if (medications.isEmpty()) {
                    EmptyInteractiveRowCard(
                        icon = Icons.Filled.Medication,
                        message = "No medication reminders added yet.",
                        actionLabel = "Add Med",
                        onClick = onOpenMedsTab,
                        modifier = Modifier.testTag("summary_empty_meds_card")
                    )
                } else {
                    val prioritizedMeds = medications.sortedBy { it.takenToday }.take(4)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        prioritizedMeds.forEach { med ->
                            ReminderCard(
                                model = ReminderCardModel.Medication(
                                    item = med,
                                    onToggleTaken = { onToggleMedication(med.id, !med.takenToday) }
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

                // --- Upcoming Calendar Events Section ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Upcoming Calendar Events",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    TextButton(
                        onClick = onOpenCalendarTab,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("summary_all_events_button")
                    ) {
                        Text("Calendar")
                    }
                }

                if (events.isEmpty()) {
                    EmptyInteractiveRowCard(
                        icon = Icons.Filled.CalendarMonth,
                        message = "No upcoming calendar events scheduled.",
                        actionLabel = "Add Event",
                        onClick = onOpenCalendarTab,
                        modifier = Modifier.testTag("summary_empty_events_card")
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        events.take(4).forEach { event ->
                            ReminderCard(
                                model = ReminderCardModel.Event(
                                    item = event,
                                    onOpenCalendar = onOpenCalendarTab,
                                    onAskAi = {
                                        onAskAboutItem(
                                            "Help me prepare for my upcoming event: ${event.title} on ${event.dateLabel}" +
                                                if (event.timeLabel.isNotBlank()) " at ${event.timeLabel}" else ""
                                        )
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Unified interactive ReminderCard composable representing medication reminders
 * and calendar events visually inside the AI chat history instead of plain text.
 */
sealed interface ReminderCardModel {
    data class Medication(
        val item: MedicationItem,
        val onToggleTaken: () -> Unit
    ) : ReminderCardModel

    data class Event(
        val item: CalendarEvent,
        val onOpenCalendar: () -> Unit,
        val onAskAi: () -> Unit
    ) : ReminderCardModel
}

@Composable
fun ReminderCard(
    model: ReminderCardModel,
    modifier: Modifier = Modifier
) {
    when (model) {
        is ReminderCardModel.Medication -> {
            InteractiveMedicationReminderCard(
                med = model.item,
                onToggleTaken = model.onToggleTaken,
                modifier = modifier
            )
        }
        is ReminderCardModel.Event -> {
            InteractiveCalendarEventCard(
                event = model.item,
                onOpenCalendar = model.onOpenCalendar,
                onAskAi = model.onAskAi,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun SummaryStatusBadge(
    text: String,
    highlighted: Boolean
) {
    Surface(
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        },
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun InteractiveMedicationReminderCard(
    med: MedicationItem,
    onToggleTaken: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isTaken = med.takenToday
    Surface(
        color = if (isTaken) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isTaken) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("summary_med_card_${med.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isTaken) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isTaken) Icons.Filled.CheckCircle else Icons.Filled.Medication,
                    contentDescription = null,
                    tint = if (isTaken) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = med.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (med.dosage.isNotBlank()) {
                        Text(
                            text = med.dosage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = med.scheduleSlot,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = if (isTaken) "Taken today" else "Reminder due",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isTaken) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            if (isTaken) {
                FilledTonalButton(
                    onClick = onToggleTaken,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("summary_med_toggle_${med.id}"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Mark ${med.name} not taken",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Taken", maxLines = 1)
                }
            } else {
                Button(
                    onClick = onToggleTaken,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("summary_med_toggle_${med.id}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("Took It", maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun InteractiveCalendarEventCard(
    event: CalendarEvent,
    onOpenCalendar: () -> Unit,
    onAskAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenCalendar)
            .testTag("summary_event_card_${event.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CalendarMonth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = event.dateLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (event.timeLabel.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = event.timeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (event.location.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Place,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = event.location,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (event.notes.isNotBlank()) {
                        Text(
                            text = event.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onAskAi,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("summary_event_ask_${event.id}"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Ask KindGuide about ${event.title}",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Ask AI", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EmptyInteractiveRowCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(actionLabel, maxLines = 1)
            }
        }
    }
}

/**
 * Determines whether an assistant message in chat history should attach interactive
 * Medication Reminder Cards and/or Calendar Event Cards directly inside the reply bubble.
 */
@Composable
fun InlineChatScheduleCards(
    assistantText: String,
    precedingUserText: String,
    medications: List<MedicationItem>,
    events: List<CalendarEvent>,
    onToggleMedication: (medId: String, taken: Boolean) -> Unit,
    onOpenCalendarTab: () -> Unit,
    onAskAboutItem: (String) -> Unit
) {
    val combined = "${precedingUserText.lowercase()} ${assistantText.lowercase()}"
    val asksAboutMeds = listOf("med", "pill", "tablet", "medicine", "medication", "dose", "prescription")
        .any { combined.contains(it) }
    val asksAboutEvents = listOf("calendar", "event", "appointment", "doctor", "schedule", "upcoming", "left to do")
        .any { combined.contains(it) }

    val showMeds = asksAboutMeds && medications.isNotEmpty()
    val showEvents = asksAboutEvents && events.isNotEmpty()
    if (!showMeds && !showEvents) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showMeds) {
            Text(
                text = "Medication Reminders",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            medications.sortedBy { it.takenToday }.take(3).forEach { med ->
                ReminderCard(
                    model = ReminderCardModel.Medication(
                        item = med,
                        onToggleTaken = { onToggleMedication(med.id, !med.takenToday) }
                    )
                )
            }
        }
        if (showEvents) {
            Text(
                text = "Upcoming Calendar Events",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            events.take(3).forEach { ev ->
                ReminderCard(
                    model = ReminderCardModel.Event(
                        item = ev,
                        onOpenCalendar = onOpenCalendarTab,
                        onAskAi = {
                            onAskAboutItem(
                                "Help me prepare for ${ev.title} (${ev.dateLabel} ${ev.timeLabel})".trim()
                            )
                        }
                    )
                )
            }
        }
    }
}
