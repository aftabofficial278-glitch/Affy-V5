package com.example.agent

import com.example.data.model.ActiveTaskWorkflowContext
import com.example.data.model.PendingContactDisambiguation
import com.example.data.model.WorkflowStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AffyConversationContextManager {

    private val _activeWorkflow = MutableStateFlow<ActiveTaskWorkflowContext?>(null)
    val activeWorkflow: StateFlow<ActiveTaskWorkflowContext?> = _activeWorkflow.asStateFlow()

    private val _pendingDisambiguation = MutableStateFlow<PendingContactDisambiguation?>(null)
    val pendingDisambiguation: StateFlow<PendingContactDisambiguation?> = _pendingDisambiguation.asStateFlow()

    fun startWorkflow(appName: String, prompt: String) {
        _activeWorkflow.value = ActiveTaskWorkflowContext(
            activeApp = appName,
            promptText = prompt,
            stage = WorkflowStage.GENERATING,
            startedAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun updateStage(newStage: WorkflowStage, statusMessage: String? = null, downloadedUri: String? = null) {
        val curr = _activeWorkflow.value ?: return
        _activeWorkflow.value = curr.copy(
            stage = newStage,
            lastObservedStatus = statusMessage ?: curr.lastObservedStatus,
            verifiedDownloadedUri = downloadedUri ?: curr.verifiedDownloadedUri,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun getActiveWorkflow(): ActiveTaskWorkflowContext? {
        val wf = _activeWorkflow.value ?: return null
        if (wf.isExpired()) {
            clearWorkflow()
            return null
        }
        return wf
    }

    fun clearWorkflow() {
        _activeWorkflow.value = null
    }

    fun setPendingDisambiguation(disambiguation: PendingContactDisambiguation) {
        _pendingDisambiguation.value = disambiguation
    }

    fun getPendingDisambiguation(): PendingContactDisambiguation? {
        val disambig = _pendingDisambiguation.value ?: return null
        if (disambig.isExpired()) {
            clearPendingDisambiguation()
            return null
        }
        return disambig
    }

    fun clearPendingDisambiguation() {
        _pendingDisambiguation.value = null
    }

    fun isDownloadFollowUp(text: String): Boolean {
        val clean = text.trim().lowercase()
        val keywords = listOf("download it", "download karo", "save it", "save kar do", "download", "save karo")
        return keywords.any { clean == it || clean.startsWith(it) }
    }

    fun isWallpaperFollowUp(text: String): Boolean {
        val clean = text.trim().lowercase()
        val keywords = listOf(
            "set it as wallpaper", "set as wallpaper", "wallpaper bana do",
            "wallpaper laga do", "set as my wallpaper", "apply wallpaper", "wallpaper set karo"
        )
        return keywords.any { clean == it || clean.startsWith(it) }
    }
}
