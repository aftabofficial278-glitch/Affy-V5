package com.example.agent.vision

import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.AffyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AffyScreenVisionEngine {

    sealed class ImageObservationResult {
        data class Ready(val evidenceNode: AccessibilityNodeInfo, val statusMessage: String?) : ImageObservationResult()
        data class Error(val errorMessage: String) : ImageObservationResult()
        object Timeout : ImageObservationResult()
    }

    suspend fun observeImageGeneration(
        timeoutMs: Long = 45000L,
        pollIntervalMs: Long = 1500L
    ): ImageObservationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var observedGeneratingState = false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val service = AffyAccessibilityService.instance
            val root = service?.rootInActiveWindow

            if (root != null) {
                val statusMsg = SemanticUiClassifier.findVisibleStatusMessage(root)
                if (statusMsg != null) {
                    val lower = statusMsg.lowercase()
                    if (lower.contains("error") || lower.contains("failed") || lower.contains("couldn't")) {
                        return@withContext ImageObservationResult.Error(statusMsg)
                    }
                    if (lower.contains("generating") || lower.contains("creating") || lower.contains("thinking")) {
                        observedGeneratingState = true
                    }
                }

                val imageNode = SemanticUiClassifier.findGeneratedImageNode(root)
                if (imageNode != null) {
                    val isStillLoading = statusMsg?.lowercase()?.let {
                        it.contains("generating") || it.contains("creating")
                    } ?: false

                    if (!isStillLoading) {
                        return@withContext ImageObservationResult.Ready(imageNode, statusMsg)
                    }
                }
            }

            delay(pollIntervalMs)
        }

        ImageObservationResult.Timeout
    }

    suspend fun observeDownloadConfirmation(
        timeoutMs: Long = 10000L,
        pollIntervalMs: Long = 800L
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val notifTime = AffyAccessibilityService.lastNotificationTimestamp
            val notifText = AffyAccessibilityService.lastNotificationText
            if (notifTime >= startTime && notifText.isNotBlank()) {
                val lower = notifText.lowercase()
                if (lower.contains("saved") || lower.contains("downloaded") || lower.contains("success")) {
                    return@withContext Pair(true, notifText)
                }
                if (lower.contains("failed") || lower.contains("error")) {
                    return@withContext Pair(false, notifText)
                }
            }

            val service = AffyAccessibilityService.instance
            val root = service?.rootInActiveWindow
            if (root != null) {
                val visibleStatus = SemanticUiClassifier.findVisibleStatusMessage(root)
                if (visibleStatus != null) {
                    val lower = visibleStatus.lowercase()
                    if (lower.contains("saved to") || lower.contains("download complete") || lower.contains("saved to photos")) {
                        return@withContext Pair(true, visibleStatus)
                    }
                    if (lower.contains("failed") || lower.contains("error")) {
                        return@withContext Pair(false, visibleStatus)
                    }
                }
            }

            delay(pollIntervalMs)
        }

        Pair(false, "Download confirmation message was not observed within timeout.")
    }
}
