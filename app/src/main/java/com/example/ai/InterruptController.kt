package com.example.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

enum class InterruptDecision {
    NONE,
    ABORT_ONLY,
    ABORT_AND_PIVOT
}

data class TaskExecutionToken(
    val epoch: Long,
    val executionId: Long,
    val taskDescription: String
)

data class InterruptState(
    val currentEpoch: Long = 0L,
    val activeTaskLabel: String? = null,
    val isTaskExecuting: Boolean = false,
    val lastInterruptedTaskLabel: String? = null,
    val pivotCommand: String? = null,
    val lastInterruptReason: String? = null,
    val lastInterruptAtMs: Long = 0L
)

/**
 * Monitors user input (live voice barge-in chunks, committed utterances, text nudges, and
 * manual interrupt/cancel triggers) during task execution.
 *
 * Whenever the user interrupts, cancels, or issues a new command mid-task, [InterruptController]
 * immediately bumps the execution epoch, cancels in-flight coroutine jobs, marks active execution
 * tokens as aborted, and notifies registered abort handlers so remaining sub-steps of the
 * abandoned task are skipped cleanly and the assistant can pivot right away.
 */
object InterruptController {

    private val lock = Any()
    private val currentEpoch = AtomicLong(0L)
    private val nextExecutionId = AtomicLong(0L)

    private val activeJobs = linkedMapOf<Long, Job>()
    private val activeDescriptions = linkedMapOf<Long, String>()
    private val abortedExecutionIds = HashSet<Long>()
    private var lastSubmittedText = ""
    private var lastSubmittedAtMs = 0L

    private val _state = MutableStateFlow(InterruptState())
    val state: StateFlow<InterruptState> = _state.asStateFlow()

    @Volatile
    var isGuidedFlowActiveProvider: (() -> Boolean)? = null

    @Volatile
    private var abortListener: ((reason: String, pivotCommand: String?, hadActiveExecutions: Boolean) -> Unit)? = null

    private val fillerNoiseWords = setOf(
        "uh", "um", "ah", "oh", "hmm", "hm", "mm", "eh", "ha", "the", "a", "an"
    )

    private val confirmationWordsRegex = Regex(
        "^\\s*(?:yes|yeah|yep|yup|sure|okay|ok|alright|please)(?:[,\\s]+(?:please|send(?:\\s+it|\\s+now)?|call(?:\\s+now)?|start(?:\\s+the)?\\s+call|go\\s+ahead|do\\s+it|confirm))?\\s*[.!]?\\s*$|^\\s*(?:send|send\\s+it|send\\s+now|call|call\\s+now|start\\s+call|start\\s+the\\s+call|go\\s+ahead|do\\s+it|please\\s+do|confirm)\\s*[.!]?\\s*$",
        RegexOption.IGNORE_CASE
    )

    private val explicitAbortRegex = Regex(
        "\\b(stop|cancel|leave that|leave it|leave the task|leave this task|leave whatsapp|forget it|forget that|never mind|nevermind|quit|stop that|stop it|stop the task|drop that|drop it|skip that|skip it|don't do it|do not do it|abandon|abort|hold on|wait)\\b",
        RegexOption.IGNORE_CASE
    )

    private val explicitSwitchOrPivotRegex = Regex(
        "\\b(switch to|instead|do this instead|do that instead|open |search |call |send |message |check |play |show |go to |take me |read |add |remind )\\b",
        RegexOption.IGNORE_CASE
    )

    fun setOnAbortListener(listener: ((reason: String, pivotCommand: String?, hadActiveExecutions: Boolean) -> Unit)?) {
        abortListener = listener
    }

    /**
     * Registers a task or sub-task execution under the current epoch so it can be aborted
     * immediately if user input interrupts or pivots.
     */
    fun beginExecution(taskDescription: String, job: Job? = null): TaskExecutionToken {
        val execId = nextExecutionId.incrementAndGet()
        val epochSnapshot: Long
        synchronized(lock) {
            epochSnapshot = currentEpoch.get()
            activeDescriptions[execId] = taskDescription.ifBlank { "Screen task" }
            if (job != null) {
                activeJobs[execId] = job
            }
            _state.update {
                it.copy(
                    currentEpoch = epochSnapshot,
                    activeTaskLabel = activeDescriptions.values.lastOrNull(),
                    isTaskExecuting = true
                )
            }
        }
        return TaskExecutionToken(
            epoch = epochSnapshot,
            executionId = execId,
            taskDescription = taskDescription
        )
    }

    /**
     * Updates the coroutine [Job] bound to an existing [TaskExecutionToken] if it wasn't
     * available at [beginExecution] time.
     */
    fun attachJob(token: TaskExecutionToken, job: Job) {
        var shouldCancelImmediately = false
        synchronized(lock) {
            if (isAbortedLocked(token)) {
                shouldCancelImmediately = true
            } else {
                activeJobs[token.executionId] = job
            }
        }
        if (shouldCancelImmediately && job.isActive) {
            job.cancel(CancellationException("Aborted by user interrupt"))
        }
    }

    fun finishExecution(token: TaskExecutionToken) {
        synchronized(lock) {
            activeJobs.remove(token.executionId)
            activeDescriptions.remove(token.executionId)
            abortedExecutionIds.remove(token.executionId)
            if (abortedExecutionIds.size > 64) {
                abortedExecutionIds.clear()
            }
            _state.update {
                it.copy(
                    activeTaskLabel = activeDescriptions.values.lastOrNull(),
                    isTaskExecuting = activeDescriptions.isNotEmpty()
                )
            }
        }
    }

    fun hasActiveTask(): Boolean = synchronized(lock) {
        activeDescriptions.isNotEmpty()
    }

    fun currentEpochValue(): Long = currentEpoch.get()

    fun isAborted(token: TaskExecutionToken): Boolean = synchronized(lock) {
        isAbortedLocked(token)
    }

    private fun isAbortedLocked(token: TaskExecutionToken): Boolean {
        if (token.epoch != currentEpoch.get()) return true
        if (abortedExecutionIds.contains(token.executionId)) return true
        val job = activeJobs[token.executionId]
        return job != null && job.isCancelled
    }

    @Throws(CancellationException::class)
    fun throwIfAborted(token: TaskExecutionToken) {
        if (isAborted(token)) {
            throw CancellationException("Task '${token.taskDescription}' aborted due to user interrupt")
        }
    }

    /**
     * Launches a coroutine tied cleanly to [InterruptController], automatically registering and
     * unregistering its [Job] and honoring immediate cancellation on interrupt.
     */
    fun launchInterruptibleTask(
        scope: CoroutineScope,
        taskDescription: String,
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.(TaskExecutionToken) -> Unit
    ): Job {
        val token = beginExecution(taskDescription)
        val actualJob = scope.launch(context = context, start = CoroutineStart.LAZY) {
            try {
                if (isAborted(token)) return@launch
                block(token)
            } finally {
                finishExecution(token)
            }
        }
        attachJob(token, actualJob)
        if (!isAborted(token)) {
            actualJob.start()
        }
        return actualJob
    }

    /**
     * Monitors incoming user speech (streaming partial or final settled utterance) and typed nudges.
     * Returns [InterruptDecision.ABORT_ONLY] or [InterruptDecision.ABORT_AND_PIVOT] and aborts
     * active tasks immediately whenever the user cancels, stops, or pivots to a new command.
     */
    fun onUserInputObserved(
        rawInput: String,
        isFinal: Boolean,
        hasPendingConfirmation: Boolean = false
    ): InterruptDecision {
        val clean = rawInput.trim()
        if (clean.isBlank()) return InterruptDecision.NONE

        val lower = clean.lowercase()
        val words = lower.replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return InterruptDecision.NONE
        if (words.size == 1 && words[0] in fillerNoiseWords) return InterruptDecision.NONE
        if (clean.count { it.isLetterOrDigit() } < 2) return InterruptDecision.NONE

        val hasRunningWork = hasActiveTask()
        val hasGuidedFlow = isGuidedFlowActiveProvider?.invoke() == true
        if (!hasRunningWork && !hasGuidedFlow && !hasPendingConfirmation) {
            return InterruptDecision.NONE
        }

        // Do not abort a pending confirmation step when the user is verbally confirming it
        if (hasPendingConfirmation && confirmationWordsRegex.matches(clean)) {
            return InterruptDecision.NONE
        }

        val now = System.currentTimeMillis()
        // Prevent duplicate consecutive abort triggers for exact same live partial snapshot
        if (!isFinal && clean.equals(lastSubmittedText, ignoreCase = true) && now - lastSubmittedAtMs < 600L) {
            return InterruptDecision.NONE
        }

        val abortMatch = explicitAbortRegex.find(clean)
        if (abortMatch != null) {
            lastSubmittedText = clean
            lastSubmittedAtMs = now
            val tailAfterAbort = clean.substring(abortMatch.range.last + 1)
                .replace(Regex("^\\s*(and|then|now|,|\\.)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            val pivotCmd = tailAfterAbort.takeIf { it.length >= 4 && explicitSwitchOrPivotRegex.containsMatchIn(it) }
            abortActiveTask(
                reason = if (pivotCmd != null) "User interrupted and pivoted: $pivotCmd" else "User aborted task: $clean",
                pivotCommand = pivotCmd
            )
            return if (pivotCmd != null) InterruptDecision.ABORT_AND_PIVOT else InterruptDecision.ABORT_ONLY
        }

        // When a sub-task is actively running mid-execution, any clear new command or multi-word
        // pivot utterance immediately interrupts the running sub-task so we don't complete stale steps.
        if (hasRunningWork) {
            val isExplicitActionOrSwitch = explicitSwitchOrPivotRegex.containsMatchIn(clean)
            val isSubstantiveUtterance = (isFinal && (words.size >= 2 || isExplicitActionOrSwitch)) ||
                (!isFinal && isExplicitActionOrSwitch && clean.length >= 9) ||
                (!isFinal && words.size >= 3 && clean.length >= 12)

            if (isSubstantiveUtterance && !confirmationWordsRegex.matches(clean)) {
                lastSubmittedText = clean
                lastSubmittedAtMs = now
                abortActiveTask(
                    reason = "User interrupted active task with new command: $clean",
                    pivotCommand = clean
                )
                return InterruptDecision.ABORT_AND_PIVOT
            }
        } else if (hasGuidedFlow && isFinal && explicitSwitchOrPivotRegex.containsMatchIn(clean)) {
            lastSubmittedText = clean
            lastSubmittedAtMs = now
            abortActiveTask(
                reason = "User pivoted to a new command during guided flow: $clean",
                pivotCommand = clean
            )
            return InterruptDecision.ABORT_AND_PIVOT
        }

        return InterruptDecision.NONE
    }

    /**
     * Triggered when the user taps the manual Interrupt / Cancel button or barge-in control.
     */
    fun triggerManualInterrupt(reason: String = "Manual interrupt requested by user") {
        abortActiveTask(reason = reason, pivotCommand = null)
    }

    /**
     * Immediately increments the task epoch, cancels all active jobs, and notifies listeners
     * so no further sub-steps of the abandoned task can execute.
     */
    fun abortActiveTask(reason: String, pivotCommand: String? = null) {
        val jobsToCancel: List<Job>
        val abortedLabel: String?
        val newEpoch: Long
        val hadActiveExecutions: Boolean

        synchronized(lock) {
            newEpoch = currentEpoch.incrementAndGet()
            hadActiveExecutions = activeDescriptions.isNotEmpty()
            abortedLabel = activeDescriptions.values.lastOrNull()
            abortedExecutionIds.addAll(activeDescriptions.keys)
            jobsToCancel = activeJobs.values.toList()
            activeJobs.clear()
            activeDescriptions.clear()

            _state.update {
                it.copy(
                    currentEpoch = newEpoch,
                    activeTaskLabel = null,
                    isTaskExecuting = false,
                    lastInterruptedTaskLabel = abortedLabel ?: it.lastInterruptedTaskLabel,
                    pivotCommand = pivotCommand,
                    lastInterruptReason = reason,
                    lastInterruptAtMs = System.currentTimeMillis()
                )
            }
        }

        for (job in jobsToCancel) {
            runCatching { job.cancel(CancellationException(reason)) }
        }
        runCatching { abortListener?.invoke(reason, pivotCommand, hadActiveExecutions) }
    }

    fun clearPivotCommand() {
        _state.update { it.copy(pivotCommand = null) }
    }

    fun reset() {
        val jobsToCancel: List<Job>
        synchronized(lock) {
            currentEpoch.incrementAndGet()
            jobsToCancel = activeJobs.values.toList()
            activeJobs.clear()
            activeDescriptions.clear()
            abortedExecutionIds.clear()
            lastSubmittedText = ""
            lastSubmittedAtMs = 0L
            _state.value = InterruptState(currentEpoch = currentEpoch.get())
        }
        for (job in jobsToCancel) {
            runCatching { job.cancel(CancellationException("InterruptController reset")) }
        }
    }
}
