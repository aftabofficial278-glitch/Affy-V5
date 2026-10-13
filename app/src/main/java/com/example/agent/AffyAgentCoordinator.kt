package com.example.agent

import android.content.Context
import com.example.data.model.ActionResult
import com.example.data.model.ActionTypes
import com.example.data.model.AffyActionItem
import com.example.data.model.PendingContactDisambiguation
import com.example.data.model.WorkflowStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class AffyAgentCoordinator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    val contextManager = AffyConversationContextManager()
    val actionExecutor = AffyActionExecutor(context, contextManager)
    val contactResolver = ContactResolver(context)
    val planGenerator = DynamicPlanGenerator()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(
        listOf(
            LogEntry(text = "AFFY 2.5 initialized and ready for autonomous commands.", isUser = false)
        )
    )
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun processUserInput(input: String) {
        val clean = input.trim()
        if (clean.isBlank()) return

        addLog(clean, isUser = true)

        scope.launch(Dispatchers.IO) {
            _status.value = "Thinking..."

            // Check 1: Disambiguation follow-up (e.g. "first", "mobile", "1700 wale")
            val pendingDisambig = contextManager.getPendingDisambiguation()
            if (pendingDisambig != null) {
                val matched = contactResolver.selectByDisambiguationRule(pendingDisambig.candidates, clean)
                if (matched != null) {
                    contextManager.clearPendingDisambiguation()
                    val (candidate, numberOption) = matched
                    addLog("Selected ${candidate.displayName} (${numberOption.label}): ${numberOption.displayNumber}", isUser = false)
                    _status.value = "Calling ${candidate.displayName}..."
                    val callAction = AffyActionItem(
                        type = ActionTypes.PHONE_CALL,
                        target = numberOption.normalizedNumber
                    )
                    val result = actionExecutor.executeAction(callAction)
                    addLog(result.speechResponseOverride ?: result.summary, isUser = false)
                    _status.value = "Idle"
                    return@launch
                }
            }

            // Check 2: Download follow-up
            if (contextManager.isDownloadFollowUp(clean)) {
                val wf = contextManager.getActiveWorkflow()
                if (wf != null && (wf.stage == WorkflowStage.READY_FOR_DOWNLOAD || wf.stage == WorkflowStage.GENERATING)) {
                    _status.value = "Downloading image..."
                    addLog("Resuming task: Downloading generated wallpaper from ChatGPT...", isUser = false)
                    val result = actionExecutor.executeAction(
                        AffyActionItem(type = ActionTypes.DOWNLOAD_CHATGPT_IMAGE, target = "com.openai.chatgpt")
                    )
                    addLog(result.speechResponseOverride ?: result.summary, isUser = false)
                    _status.value = "Idle"
                    return@launch
                }
            }

            // Check 3: Wallpaper follow-up
            if (contextManager.isWallpaperFollowUp(clean)) {
                val wf = contextManager.getActiveWorkflow()
                if (wf != null) {
                    _status.value = "Applying wallpaper..."
                    addLog("Resuming task: Applying verified image to system wallpaper...", isUser = false)
                    val result = actionExecutor.executeAction(
                        AffyActionItem(type = ActionTypes.SET_WALLPAPER)
                    )
                    addLog(result.speechResponseOverride ?: result.summary, isUser = false)
                    _status.value = "Idle"
                    return@launch
                }
            }

            // Check 4: Direct Phone Call Intent
            val lower = clean.lowercase()
            if (lower.startsWith("call ") || lower.contains(" ko call karo")) {
                val targetName = when {
                    lower.startsWith("call ") -> clean.substringAfter("call ").trim()
                    lower.contains(" ko call karo") -> clean.substringBefore(" ko call karo").trim()
                    else -> clean
                }
                handleCallRequest(targetName)
                _status.value = "Idle"
                return@launch
            }

            // Check 5: General Plan Execution
            val task = planGenerator.createPlanFromPrompt(clean)
            for (step in task.steps) {
                _status.value = "Executing: ${step.description}"
                addLog("Step: ${step.description}", isUser = false)
                val result = actionExecutor.executeAction(step.action)
                addLog(result.speechResponseOverride ?: result.summary, isUser = false)
                if (!result.success) break
            }

            _status.value = "Idle"
        }
    }

    private suspend fun handleCallRequest(name: String) {
        when (val result = contactResolver.resolveContact(name)) {
            is ContactResolver.ResolveResult.SingleMatch -> {
                addLog("Found contact: ${result.name} (${result.label}: ${result.number})", isUser = false)
                _status.value = "Calling ${result.name}..."
                val callAction = AffyActionItem(type = ActionTypes.PHONE_CALL, target = result.number)
                val res = actionExecutor.executeAction(callAction)
                addLog(res.speechResponseOverride ?: res.summary, isUser = false)
            }
            is ContactResolver.ResolveResult.MultipleMatches -> {
                val disambig = PendingContactDisambiguation(
                    requestedName = name,
                    candidates = result.candidates
                )
                contextManager.setPendingDisambiguation(disambig)
                val summaryLines = mutableListOf<String>()
                summaryLines.add("Multiple options found for '$name':")
                result.candidates.forEachIndexed { cIndex, cand ->
                    cand.numbers.forEachIndexed { nIndex, num ->
                        summaryLines.add("${cIndex + 1}.${nIndex + 1} ${cand.displayName} - ${num.label} (${num.displayNumber})")
                    }
                }
                summaryLines.add("Please say 'first', 'second', 'Mobile', or the last digits to choose.")
                val message = summaryLines.joinToString("\n")
                addLog(message, isUser = false)
            }
            is ContactResolver.ResolveResult.PermissionDenied -> {
                addLog("Contacts permission is required to search and call contacts. Please grant it in Settings.", isUser = false)
            }
            is ContactResolver.ResolveResult.NotFound -> {
                addLog("Could not find any contact matching '$name' in your address book.", isUser = false)
            }
        }
    }

    private fun addLog(text: String, isUser: Boolean) {
        _logs.value = _logs.value + LogEntry(text = text, isUser = isUser)
    }
}
