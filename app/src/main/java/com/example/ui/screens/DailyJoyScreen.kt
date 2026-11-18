package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ai.GuideSessionCoordinator
import com.example.ui.components.WarmSectionCard
import com.example.ui.state.EngagementUiState
import kotlinx.coroutines.delay

private data class MemoryTile(
    val id: Int,
    val symbol: String,
    val label: String,
    val isFaceUp: Boolean = false,
    val isMatched: Boolean = false
)

@Composable
fun DailyJoyScreen(
    engagementState: EngagementUiState,
    brainGamesWon: Int,
    onGenerateStory: (String) -> Unit,
    onShareMemoryPrompt: (String) -> Unit,
    onBrainGameWin: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTriviaIndex by remember(engagementState) { mutableStateOf<Int?>(null) }
    val speakingMessageId by GuideSessionCoordinator.speakingMessageId.collectAsStateWithLifecycle()
    val isReadingStory = speakingMessageId == "daily_story"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_audio_story),
                contentDescription = "Community audio stories and gentle brain games",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop
            )

            // 1. Community Audio Story & Reminisce Companion (Clean Ivory Card without nested boxes)
            WarmSectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AutoStories,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Audio Stories & Trivia", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(10.dp))

                when (engagementState) {
                    is EngagementUiState.Idle -> {
                        Button(
                            onClick = { onGenerateStory("sunlit porches, family recipes, and classic music") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("generate_story_button")
                        ) {
                            Icon(Icons.Filled.AutoStories, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Create Today's Story", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    is EngagementUiState.Loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp))
                            Text("Preparing your story...", style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    is EngagementUiState.Streaming -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = if (isReadingStory) "Streaming & speaking..." else "Writing your story...",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (engagementState.partialTitle.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = engagementState.partialTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (engagementState.partialStory.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = engagementState.partialStory,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        if (isReadingStory) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { GuideSessionCoordinator.stopTtsOnly() }) {
                                Icon(Icons.Filled.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Stop Audio")
                            }
                        }
                    }

                    is EngagementUiState.Error -> {
                        Text(
                            text = engagementState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onGenerateStory("friendly neighborhood stories") }) {
                            Text("Try Again")
                        }
                    }

                    is EngagementUiState.Ready -> {
                        val content = engagementState.content
                        Text(
                            text = content.storyTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = content.storyBody,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isReadingStory) {
                                        GuideSessionCoordinator.stopTtsOnly()
                                    } else {
                                        GuideSessionCoordinator.speakText(
                                            text = "${content.storyTitle}. ${content.storyBody}. ${content.reminisceQuestion}",
                                            messageId = "daily_story"
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("listen_story_button")
                            ) {
                                Icon(
                                    imageVector = if (isReadingStory) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (isReadingStory) "Stop" else "Listen")
                            }
                            OutlinedButton(
                                onClick = { onGenerateStory("music, gardens, travel, and family celebrations") },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("New Story")
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "Memory Prompt",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(content.reminisceQuestion, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                onShareMemoryPrompt("Story prompt: ${content.reminisceQuestion} — Let's chat about this!")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.RecordVoiceOver, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Share with Family")
                        }

                        if (content.triviaQuestion.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))

                            Text("Daily Trivia", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(content.triviaQuestion, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(10.dp))
                            content.triviaOptions.forEachIndexed { idx, option ->
                                val isSelected = selectedTriviaIndex == idx
                                val isCorrect = idx == content.triviaCorrectIndex
                                OutlinedButton(
                                    onClick = { selectedTriviaIndex = idx },
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = when {
                                            selectedTriviaIndex == null -> MaterialTheme.colorScheme.outline
                                            isCorrect -> MaterialTheme.colorScheme.secondary
                                            isSelected -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.outline
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .height(48.dp)
                                ) {
                                    Text(option, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            if (selectedTriviaIndex != null) {
                                Spacer(Modifier.height(8.dp))
                                val gotIt = selectedTriviaIndex == content.triviaCorrectIndex
                                Text(
                                    text = (if (gotIt) "Correct! " else "Good try! ") + content.triviaFact,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (gotIt) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // 2. Interactive 4x3 Memory Match Cognitive Game
            MemoryMatchGameCard(
                gamesWonCount = brainGamesWon,
                onGameWon = onBrainGameWin
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MemoryMatchGameCard(
    gamesWonCount: Int,
    onGameWon: () -> Unit
) {
    val pairCatalog = remember {
        listOf(
            "🌸" to "Flower",
            "☀️" to "Sun",
            "🎵" to "Music",
            "🏡" to "Home",
            "🍰" to "Cake",
            "🐦" to "Bird"
        )
    }

    fun buildShuffledDeck(): List<MemoryTile> {
        return pairCatalog
            .flatMapIndexed { idx, (sym, lbl) ->
                listOf(
                    MemoryTile(id = idx * 2, symbol = sym, label = lbl),
                    MemoryTile(id = idx * 2 + 1, symbol = sym, label = lbl)
                )
            }
            .shuffled()
    }

    var tiles by remember { mutableStateOf(buildShuffledDeck()) }
    var firstPickIndex by remember { mutableStateOf<Int?>(null) }
    var secondPickIndex by remember { mutableStateOf<Int?>(null) }
    var moves by remember { mutableIntStateOf(0) }
    var wonCurrentRound by remember { mutableStateOf(false) }

    LaunchedEffect(firstPickIndex, secondPickIndex) {
        val i1 = firstPickIndex
        val i2 = secondPickIndex
        if (i1 != null && i2 != null) {
            moves += 1
            if (tiles[i1].label == tiles[i2].label) {
                tiles = tiles.mapIndexed { idx, t ->
                    if (idx == i1 || idx == i2) t.copy(isMatched = true, isFaceUp = true) else t
                }
                firstPickIndex = null
                secondPickIndex = null
                if (tiles.all { it.isMatched } && !wonCurrentRound) {
                    wonCurrentRound = true
                    onGameWon()
                }
            } else {
                delay(750L)
                tiles = tiles.mapIndexed { idx, t ->
                    if (idx == i1 || idx == i2) t.copy(isFaceUp = false) else t
                }
                firstPickIndex = null
                secondPickIndex = null
            }
        }
    }

    WarmSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Memory Match", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Moves: $moves • Won: $gamesWonCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    tiles = buildShuffledDeck()
                    firstPickIndex = null
                    secondPickIndex = null
                    moves = 0
                    wonCurrentRound = false
                }
            ) {
                Text("Reset")
            }
        }

        if (wonCurrentRound) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Matched all 6 pairs in $moves moves!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (col in 0 until 3) {
                        val idx = row * 3 + col
                        val tile = tiles[idx]
                        val revealed = tile.isFaceUp || tile.isMatched
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(80.dp)
                                .clickable(enabled = !revealed && secondPickIndex == null) {
                                    tiles = tiles.mapIndexed { i, t ->
                                        if (i == idx) t.copy(isFaceUp = true) else t
                                    }
                                    if (firstPickIndex == null) {
                                        firstPickIndex = idx
                                    } else {
                                        secondPickIndex = idx
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    tile.isMatched -> MaterialTheme.colorScheme.secondaryContainer
                                    revealed -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surface
                                }
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (tile.isMatched) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (revealed) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(text = tile.symbol, fontSize = 24.sp)
                                        Text(text = tile.label, style = MaterialTheme.typography.labelMedium)
                                    }
                                } else {
                                    Text(
                                        text = "?",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
