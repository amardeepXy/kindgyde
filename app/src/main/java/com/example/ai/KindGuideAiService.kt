package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.ConversationMessage
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class AiGuideStep(
    val stepTitle: String,
    val stepNumber: Int,
    val totalSteps: Int,
    val instruction: String,
    val targetButtonHint: String,
    val reassuranceNote: String,
    val suggestedAppName: String,
    val isCorrection: Boolean = false,
    val wrongClickedLabel: String = "",
    val targetXPercent: Int = -1,
    val targetYPercent: Int = -1,
    val guidanceMode: String = "HIGHLIGHT_BUTTON", // HIGHLIGHT_BUTTON, SWIPE_HINT, SEARCH_HINT, CONFIRM_SEND
    val requiresConfirmation: Boolean = false
)

data class AssistiveScreenAction(
    val actionType: String, // OPEN_APP, WEB_SEARCH, TAP_ELEMENT, SWIPE_SCREEN, TYPE_TEXT, SYSTEM_NAV
    val targetLabel: String = "",
    val swipeDirection: String = "", // LEFT, RIGHT, UP, DOWN
    val textToType: String = "",
    val submitAfterType: Boolean = false,
    val expectedAppName: String = "",
    val systemNavAction: String = "", // BACK, HOME
    val spokenExplanation: String = "",
    val isFinalSendOrCommit: Boolean = false,
    val userConfirmed: Boolean = false
)

data class VerifiedActionOutcome(
    val success: Boolean,
    val awaitingUserConfirmation: Boolean = false,
    val awaitingUserTapOrHelp: Boolean = false,
    val wasAborted: Boolean = false,
    val actionSummary: String,
    val verifiedAppTitle: String,
    val isChatConversationOpen: Boolean = false,
    val visibleElementsSummary: String,
    val spokenConfirmations: List<String> = emptyList()
)

data class AiCrudCommand(
    val operation: String,
    val entity: String,
    val title: String,
    val subtitle: String,
    val extra: String
)

data class AiCompanionResult(
    val reply: String,
    val guideStep: AiGuideStep?,
    val crudCommand: AiCrudCommand?
)

data class ScamCheckResult(
    val verdict: String,
    val headline: String,
    val explanation: String,
    val recommendedAction: String
)

data class DailyEngagementContent(
    val storyTitle: String,
    val storyBody: String,
    val reminisceQuestion: String,
    val triviaQuestion: String,
    val triviaOptions: List<String>,
    val triviaCorrectIndex: Int,
    val triviaFact: String
)

open class KindGuideAiService {

    private val scamSchema = Schema.obj(
        properties = mapOf(
            "verdict" to Schema.enumeration(listOf("Safe", "Be Careful", "Likely Scam")),
            "headline" to Schema.string("Short, calm 1-line assessment."),
            "explanation" to Schema.string("Plain-language explanation without technical jargon."),
            "recommendedAction" to Schema.string("Clear, safe next step for the senior.")
        )
    )

    private val engagementSchema = Schema.obj(
        properties = mapOf(
            "storyTitle" to Schema.string("Title of a warm, nostalgic 1-minute audio story."),
            "storyBody" to Schema.string("Heartwarming short story (80-120 words) easy to listen to."),
            "reminisceQuestion" to Schema.string("Gentle conversation prompt to help the senior reflect or share."),
            "triviaQuestion" to Schema.string("Fun, accessible daily trivia question."),
            "triviaOptions" to Schema.array(Schema.string(), "Exactly 3 short answer choices."),
            "triviaCorrectIndex" to Schema.integer("0-based index of the correct trivia option."),
            "triviaFact" to Schema.string("Short cheerful explanation of the answer.")
        )
    )

    private val streamingCompanionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.55f
            },
            systemInstruction = content {
                text(
                    """
                    You are KindGuide, a warm, caring, and intelligent AI companion for seniors.

                    STRICT CONVERSATION RULES:
                    1. DO NOT mention, repeat, or remind the user about their tasks, medications, or calendar events UNLESS the user explicitly asks about their tasks, what they have left to do, their medicines/pills, or their schedule, OR asks you to add/complete/update/delete one!
                       - For greetings ("Hey", "Hello"), small talk ("I am fine, what about you?"), or general questions (like exercises, health tips, recipes, stories, or advice), reply directly to their topic WITHOUT mentioning their tasks or medications.
                    2. RICH MARKDOWN & VISUALLY ATTRACTIVE FORMATTING:
                       - Whenever the user asks for a list, exercises, wellness tips, recipes, ideas, or explanations, format your reply with clean, visually attractive Markdown:
                         * Use a warm section heading with an emoji, e.g. `### 🌅 Gentle Morning Exercises`
                         * Format list items as numbered (`1. **Title** — Description`) or bulleted (`- **Title:** Description`) lines with **bold titles** so each item stands out clearly.
                         * Include a short, encouraging tip at the end as a blockquote callout, e.g. `> 💡 **Gentle Tip:** Move at your own comfortable pace and breathe steadily.`
                       - For short casual greetings ("Hey", "How are you?"), keep your reply brief and conversational (1–2 sentences).
                    3. When the user DOES explicitly ask about their medications or calendar events/appointments, give a warm, concise 1–2 sentence overview (highlighting whether any medicine is due or how many events are coming up), because the app automatically displays rich interactive Medication & Calendar Event cards right below your message. When asked about general daily tasks, list their open tasks in clean Markdown.
                    4. When the user asks how to do something on their smartphone (e.g., "How do I send a photo?") or shares a screenshot, give ONLY ONE small, clear step at a time and wait for them to finish before giving the next step.

                    OPTIONAL ACTION METADATA:
                    - Write your Markdown reply first.
                    - ONLY IF this turn requires (a) showing a step-by-step phone guide card OR (b) creating/completing/updating/deleting a task, medication, calendar event, or voice note, append a metadata block at the very end on a new line in this exact format:
                    <<<META
                    {"isStepGuide":false,"stepTitle":"","stepNumber":1,"totalSteps":3,"singleStepInstruction":"","targetButtonHint":"","reassuranceNote":"","suggestedAppName":"","crudOperation":"create","crudEntity":"task","crudTitle":"Water plants","crudSubtitle":"5:00 PM","crudExtra":"Daily"}
                    >>>
                    - For normal chat, greetings, or general questions, DO NOT output <<<META at all.
                    """.trimIndent()
                )
            }
        )
    }

    private val scamModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = scamSchema
                temperature = 0.2f
            },
            systemInstruction = content {
                text(
                    "You are a calm digital safety helper protecting senior citizens from SMS, email, and phone scams. Explain risks gently in plain everyday language."
                )
            }
        )
    }

    private val engagementModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = engagementSchema
                temperature = 0.8f
            }
        )
    }

    open suspend fun streamCompanionTurn(
        userMessage: String,
        historyMessages: List<ConversationMessage>,
        screenContextSummary: String,
        screenshot: Bitmap?,
        circleContextSummary: String,
        onPartialReply: (String) -> Unit
    ): AiCompanionResult {
        val recentHistoryText = historyMessages
            .takeLast(8)
            .joinToString("\n") { msg ->
                val speaker = if (msg.role == "model") "KindGuide" else "Senior"
                "$speaker: ${msg.text.take(260)}"
            }

        val promptText = buildString {
            appendLine("BACKGROUND REFERENCE (ONLY mention tasks or medications if the user explicitly asks about them or asks to modify them):")
            appendLine(circleContextSummary)
            if (recentHistoryText.isNotBlank()) {
                appendLine()
                appendLine("RECENT CHAT HISTORY:")
                appendLine(recentHistoryText)
            }
            if (screenContextSummary.isNotBlank()) {
                appendLine()
                appendLine("CURRENT SCREEN CONTEXT:")
                appendLine(screenContextSummary.take(2500))
            }
            appendLine()
            appendLine("SENIOR'S NEW MESSAGE:")
            appendLine(userMessage)
        }

        val inputContent = content {
            if (screenshot != null) {
                image(scaleBitmapIfNeeded(screenshot))
            }
            text(promptText)
        }

        val rawBuilder = StringBuilder()
        var displayedLength = 0

        return try {
            withTimeout(AI_TIMEOUT_MS) {
                streamingCompanionModel.generateContentStream(inputContent).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        rawBuilder.append(piece)
                        val targetVisible = rawBuilder.toString()
                            .substringBefore("<<<META")
                            .substringBefore("<<<")
                            .trimStart()

                        // Progressively reveal buffered chunks so streaming is always visibly smooth
                        while (displayedLength < targetVisible.length) {
                            val stepChars = minOf(16, targetVisible.length - displayedLength)
                            var nextEnd = displayedLength + stepChars
                            while (nextEnd < targetVisible.length && !targetVisible[nextEnd].isWhitespace()) {
                                nextEnd++
                            }
                            displayedLength = nextEnd
                            onPartialReply(targetVisible.substring(0, displayedLength))
                            delay(14L)
                        }
                    }
                }
            }
            val fullRaw = rawBuilder.toString()
            if (fullRaw.isBlank()) {
                throw IllegalStateException("Empty stream response")
            }
            val parsed = parseStreamedResponse(fullRaw, userMessage)
            onPartialReply(parsed.reply)
            delay(40L)
            parsed
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.e(TAG, "Companion stream timed out", e)
            val fallback = buildContextAwareFallback(userMessage, circleContextSummary)
            simulateWordStream(fallback.reply, onPartialReply)
            fallback
        } catch (e: Exception) {
            Log.e(TAG, "Companion stream error, using context-aware fallback", e)
            val fallback = buildContextAwareFallback(userMessage, circleContextSummary)
            simulateWordStream(fallback.reply, onPartialReply)
            fallback
        }
    }

    private suspend fun simulateWordStream(text: String, onPartialReply: (String) -> Unit) {
        val words = text.split(" ")
        val sb = StringBuilder()
        for (w in words) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(w)
            onPartialReply(sb.toString())
            delay(28L)
        }
    }

    private fun parseStreamedResponse(
        fullRaw: String,
        userMessage: String
    ): AiCompanionResult {
        val visibleReply = fullRaw
            .substringBefore("<<<META")
            .substringBefore("<<<")
            .trim()
            .ifBlank { "I'm right here with you." }

        val metaBlock = if (fullRaw.contains("<<<META")) {
            fullRaw.substringAfter("<<<META")
                .substringBefore(">>>")
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        } else {
            ""
        }

        var step: AiGuideStep? = null
        var crud: AiCrudCommand? = null

        if (metaBlock.startsWith("{")) {
            runCatching {
                val json = JSONObject(metaBlock)
                val isStepGuide = json.optBoolean("isStepGuide", false)
                val instruction = json.optString("singleStepInstruction").trim()
                if (isStepGuide && instruction.isNotBlank()) {
                    step = AiGuideStep(
                        stepTitle = json.optString("stepTitle").ifBlank { "Phone Guide" },
                        stepNumber = json.optInt("stepNumber", 1).coerceAtLeast(1),
                        totalSteps = json.optInt("totalSteps", 1).coerceAtLeast(1),
                        instruction = instruction,
                        targetButtonHint = json.optString("targetButtonHint"),
                        reassuranceNote = json.optString("reassuranceNote"),
                        suggestedAppName = json.optString("suggestedAppName"),
                        isCorrection = json.optBoolean("isCorrection", false),
                        targetXPercent = json.optInt("targetXPercent", -1),
                        targetYPercent = json.optInt("targetYPercent", -1)
                    )
                }
                val crudOp = json.optString("crudOperation", "none")
                val crudEntity = json.optString("crudEntity", "none")
                if (crudOp != "none" && crudEntity != "none") {
                    crud = AiCrudCommand(
                        operation = crudOp,
                        entity = crudEntity,
                        title = json.optString("crudTitle"),
                        subtitle = json.optString("crudSubtitle"),
                        extra = json.optString("crudExtra")
                    )
                }
            }
        }

        if (crud == null) {
            crud = extractFallbackCrudCommand(userMessage)
        }

        return AiCompanionResult(
            reply = visibleReply,
            guideStep = step,
            crudCommand = crud
        )
    }

    private fun extractFallbackCrudCommand(userMessage: String): AiCrudCommand? {
        val lower = userMessage.trim().lowercase()
        if (lower.startsWith("add a task") || lower.startsWith("add task") || lower.contains("remind me to ")) {
            val rawTask = userMessage
                .replace(Regex("(?i)^(please\\s+)?(add a task to|add task to|add a task|add task|remind me to)\\s*"), "")
                .trim()
            val timeMatch = Regex("(?i)\\b(at\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)?|today|tomorrow|morning|evening|afternoon|tonight)\\b")
                .find(rawTask)
            val dueTime = timeMatch?.value?.replace(Regex("(?i)^at\\s+"), "")?.trim() ?: "Today"
            val title = if (timeMatch != null) {
                rawTask.removeRange(timeMatch.range).trim().trimEnd(',', '.', '-')
            } else {
                rawTask
            }.ifBlank { rawTask }

            if (title.isNotBlank()) {
                return AiCrudCommand(
                    operation = "create",
                    entity = "task",
                    title = title.replaceFirstChar { it.uppercase() },
                    subtitle = dueTime,
                    extra = "Daily"
                )
            }
        }
        return null
    }

    private fun buildContextAwareFallback(
        userMessage: String,
        circleContextSummary: String
    ): AiCompanionResult {
        val lower = userMessage.trim().lowercase()
        val lines = circleContextSummary.lines()
        val seniorName = lines.firstOrNull { it.startsWith("Senior Name:") }
            ?.substringAfter("Senior Name:")?.trim().orEmpty().ifBlank { "Friend" }
        val openTasksLine = lines.firstOrNull { it.startsWith("Open Tasks:") }
            ?.substringAfter("Open Tasks:")?.trim().orEmpty()
        val medsLine = lines.firstOrNull { it.startsWith("Medications Due Today:") }
            ?.substringAfter("Medications Due Today:")?.trim().orEmpty()

        val crudCmd = extractFallbackCrudCommand(userMessage)
        if (crudCmd != null) {
            return AiCompanionResult(
                reply = "### ✅ Task Added\nI've added **${crudCmd.title}** (${crudCmd.subtitle}) to your daily tasks.",
                guideStep = null,
                crudCommand = crudCmd
            )
        }

        if (lower.contains("exercise") || lower.contains("stretch") || lower.contains("workout")) {
            val exerciseMarkdown = """
                ### 🌅 Gentle Morning Exercises
                Here are **4 safe, low-impact exercises** you can do each morning, $seniorName:

                1. **Neck Tilts** — Sit tall and gently tilt your head toward one shoulder, hold for 3 seconds, then switch sides.
                2. **Shoulder Rolls** — Lift your shoulders up toward your ears and roll them slowly back and down 5 times.
                3. **Seated Leg Lifts** — While sitting in a sturdy chair, straighten one leg out, hold for 3 seconds, and lower it slowly.
                4. **Ankle Circles** — Lift one foot slightly off the floor and rotate your ankle 5 times in each direction.

                > 💡 **Safety Tip:** Keep a sturdy chair nearby for balance and breathe naturally throughout each movement.
            """.trimIndent()
            return AiCompanionResult(reply = exerciseMarkdown, guideStep = null, crudCommand = null)
        }

        val asksMeds = lower.contains("med") || lower.contains("pill") || lower.contains("tablet")
        val asksCalendar = lower.contains("calendar") || lower.contains("event") || lower.contains("appointment")
        if (asksMeds || asksCalendar) {
            val reply = when {
                asksMeds && asksCalendar ->
                    "### 📅 Medications & Calendar Events\nHere are your active medication reminders and upcoming calendar events, **$seniorName**. You can tap **Took It** or **Ask AI** right on the cards below."
                asksCalendar ->
                    "### 📅 Upcoming Calendar Events\nHere are your upcoming calendar events, **$seniorName**. Tap any event card below to view your calendar or ask me to help prepare."
                medsLine.isBlank() || medsLine.contains("None", ignoreCase = true) || medsLine.contains("All taken", ignoreCase = true) ->
                    "### 💊 Medication Status\nYou're all caught up on today's medications, **$seniorName**! You can check or update any reminder in the interactive cards below."
                else ->
                    "### 💊 Your Medication Reminders\nHere are your active medication reminders, **$seniorName**. Tap **Took It** right on the card below when you've taken a dose."
            }
            return AiCompanionResult(reply = reply, guideStep = null, crudCommand = null)
        }

        if (lower.contains("task") || lower.contains("left to do") || lower.contains("to do") || lower.contains("schedule")) {
            val taskReply = if (openTasksLine.isBlank() || openTasksLine.equals("None", ignoreCase = true)) {
                "### ✨ All Caught Up\nYou don't have any open tasks left for today, **$seniorName**!"
            } else {
                val items = openTasksLine.split(";").map { it.trim() }.filter { it.isNotBlank() }
                buildString {
                    appendLine("### 📋 Your Open Tasks")
                    items.forEachIndexed { idx, item ->
                        appendLine("${idx + 1}. **$item**")
                    }
                }.trim()
            }
            return AiCompanionResult(reply = taskReply, guideStep = null, crudCommand = null)
        }

        val conversationalReply = when {
            lower.contains("how are you") || lower.contains("what about you") ->
                "I'm doing wonderfully, thank you for asking, **$seniorName**! How is your day going so far?"
            lower in listOf("hey", "hi", "hello", "good morning", "good afternoon", "good evening") ->
                "Hello **$seniorName**! Good to hear from you. How can I help you today?"
            else ->
                "I'm right here with you, **$seniorName**. Tell me what you'd like to talk about or if you'd like help with anything on your phone."
        }
        return AiCompanionResult(
            reply = conversationalReply,
            guideStep = null,
            crudCommand = null
        )
    }

    open suspend fun checkForScam(
        messageText: String,
        screenshot: Bitmap?,
        onPartialUpdate: (String) -> Unit = {}
    ): ScamCheckResult {
        val input = content {
            if (screenshot != null) {
                image(scaleBitmapIfNeeded(screenshot))
            }
            text(
                "Check whether this message or screen looks safe or like a scam for a senior citizen:\n${messageText.ifBlank { "(See attached screenshot)" }}"
            )
        }

        val rawBuilder = StringBuilder()
        var lastPreview = ""
        try {
            withTimeout(AI_TIMEOUT_MS) {
                scamModel.generateContentStream(input).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        rawBuilder.append(piece)
                        val preview = buildScamStreamPreview(rawBuilder.toString())
                        if (preview.isNotBlank() && preview != lastPreview) {
                            lastPreview = preview
                            onPartialUpdate(preview)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Scam check timed out", e)
            throw IllegalStateException("Safety check timed out. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Scam check failed", e)
            throw e
        }

        val responseText = rawBuilder.toString().trim()
        if (responseText.isBlank()) {
            throw IllegalStateException("Scam check returned an empty response.")
        }

        val json = JSONObject(responseText)
        return ScamCheckResult(
            verdict = json.optString("verdict", "Be Careful"),
            headline = json.optString("headline", "Let's double-check this message."),
            explanation = json.optString("explanation", ""),
            recommendedAction = json.optString("recommendedAction", "Ask a family member before tapping any links.")
        )
    }

    open suspend fun generateDailyEngagement(
        themeHint: String,
        onPartialUpdate: (title: String, body: String) -> Unit = { _, _ -> }
    ): DailyEngagementContent {
        val rawBuilder = StringBuilder()
        var lastTitle = ""
        var lastBody = ""
        try {
            withTimeout(AI_TIMEOUT_MS) {
                engagementModel.generateContentStream(
                    "Create a warm, uplifting short audio story and an accessible trivia question inspired by: $themeHint"
                ).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        rawBuilder.append(piece)
                        val raw = rawBuilder.toString()
                        val title = extractPartialJsonStringField(raw, "storyTitle")
                        val body = extractPartialJsonStringField(raw, "storyBody")
                        if ((title.isNotBlank() || body.isNotBlank()) && (title != lastTitle || body != lastBody)) {
                            lastTitle = title
                            lastBody = body
                            onPartialUpdate(title, body)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Engagement generation timed out", e)
            throw IllegalStateException("Story generation timed out. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Engagement generation failed", e)
            throw e
        }

        val responseText = rawBuilder.toString().trim()
        if (responseText.isBlank()) {
            throw IllegalStateException("Story generation returned an empty response.")
        }

        val json = JSONObject(responseText)
        val optionsArr = json.optJSONArray("triviaOptions")
        val options = buildList {
            if (optionsArr != null) {
                for (i in 0 until optionsArr.length()) {
                    add(optionsArr.optString(i))
                }
            }
        }.ifEmpty { listOf("Morning", "Afternoon", "Evening") }

        return DailyEngagementContent(
            storyTitle = json.optString("storyTitle", "Sunday Afternoon Tea"),
            storyBody = json.optString("storyBody", ""),
            reminisceQuestion = json.optString("reminisceQuestion", "What is a favorite memory from your neighborhood?"),
            triviaQuestion = json.optString("triviaQuestion", ""),
            triviaOptions = options.take(3),
            triviaCorrectIndex = json.optInt("triviaCorrectIndex", 0).coerceIn(0, options.size - 1),
            triviaFact = json.optString("triviaFact", "")
        )
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDim = 1024
        val width = bitmap.width
        val height = bitmap.height
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        if (width <= maxDim && height <= maxDim) return safeBitmap
        val ratio = maxDim.toFloat() / maxOf(width, height).toFloat()
        return Bitmap.createScaledBitmap(
            safeBitmap,
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    internal companion object {
        const val TAG = "KindGuideAiService"
        const val MODEL_NAME = "gemini-3.8-flash"
        const val AI_TIMEOUT_MS = 60_000L

        internal fun buildScamStreamPreview(partialJson: String): String {
            val verdict = extractPartialJsonStringField(partialJson, "verdict")
            val headline = extractPartialJsonStringField(partialJson, "headline")
            val explanation = extractPartialJsonStringField(partialJson, "explanation")
            val action = extractPartialJsonStringField(partialJson, "recommendedAction")
            return buildString {
                if (verdict.isNotBlank()) appendLine(verdict)
                if (headline.isNotBlank()) appendLine(headline)
                if (explanation.isNotBlank()) appendLine(explanation)
                if (action.isNotBlank()) append("Action: $action")
            }.trim()
        }

        internal fun extractPartialJsonStringField(partialJson: String, fieldName: String): String {
            val keyToken = "\"$fieldName\""
            val keyIdx = partialJson.indexOf(keyToken)
            if (keyIdx < 0) return ""
            var i = keyIdx + keyToken.length
            while (i < partialJson.length && (partialJson[i].isWhitespace() || partialJson[i] == ':')) {
                i++
            }
            if (i >= partialJson.length || partialJson[i] != '"') return ""
            i++
            val sb = StringBuilder()
            while (i < partialJson.length) {
                val c = partialJson[i]
                if (c == '\\' && i + 1 < partialJson.length) {
                    val next = partialJson[i + 1]
                    when (next) {
                        'n' -> sb.append('\n')
                        't' -> sb.append(' ')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append(next)
                    }
                    i += 2
                    continue
                }
                if (c == '"') break
                sb.append(c)
                i++
            }
            return sb.toString().trim()
        }
    }
}
