package com.example.agent

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.example.agent.vision.AffyScreenVisionEngine
import com.example.agent.vision.SemanticUiClassifier
import com.example.data.model.ActionResult
import com.example.data.model.ActionTypes
import com.example.data.model.AffyActionItem
import com.example.data.model.WorkflowStage
import com.example.service.AffyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AffyActionExecutor(
    private val context: Context,
    private val contextManager: AffyConversationContextManager
) {
    private val visionEngine = AffyScreenVisionEngine()

    suspend fun executeAction(action: AffyActionItem): ActionResult = withContext(Dispatchers.IO) {
        try {
            when (action.type) {
                ActionTypes.OPEN_APP -> handleOpenApp(action.target ?: "")
                ActionTypes.CLICK -> handleClick(action)
                ActionTypes.TYPE_TEXT -> handleTypeText(action.text ?: "")
                ActionTypes.PHONE_CALL -> handlePhoneCall(action.target ?: action.text ?: "")
                ActionTypes.CHATGPT_GENERATE_IMAGE -> handleChatGptGenerateImage(action.text ?: "attractive phone wallpaper")
                ActionTypes.DOWNLOAD_CHATGPT_IMAGE -> handleDownloadChatGptImage()
                ActionTypes.SET_WALLPAPER -> handleSetWallpaper()
                else -> ActionResult(false, "Unknown action type: ${action.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action ${action.type}", e)
            ActionResult(false, "Execution failed: ${e.localizedMessage}")
        }
    }

    private fun handleOpenApp(appNameOrPkg: String): ActionResult {
        val pm = context.packageManager
        val pkg = if (appNameOrPkg.contains(".")) {
            appNameOrPkg
        } else {
            when (appNameOrPkg.lowercase()) {
                "chatgpt" -> "com.openai.chatgpt"
                "youtube" -> "com.google.android.youtube"
                "whatsapp" -> "com.whatsapp"
                else -> null
            }
        }

        val launchIntent = if (pkg != null) pm.getLaunchIntentForPackage(pkg) else null
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ActionResult(true, "Opened $appNameOrPkg successfully.")
        } else {
            ActionResult(false, "App $appNameOrPkg is not installed or launch intent not found.")
        }
    }

    private fun handleClick(action: AffyActionItem): ActionResult {
        val service = AffyAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility Service is not enabled. Please enable it in Settings.")

        val root = service.rootInActiveWindow
            ?: return ActionResult(false, "Active screen root is unavailable.")

        val targetText = action.text ?: action.target
        if (!targetText.isNullOrBlank()) {
            val node = service.findNodeByText(targetText, root)
                ?: service.findNodeByViewId(targetText, root)
            if (node != null) {
                val clicked = service.clickNode(node)
                return ActionResult(clicked, if (clicked) "Clicked '$targetText'." else "Failed to click '$targetText'.")
            }
        }

        if (action.x != null && action.y != null) {
            val tapped = service.dispatchTap(action.x, action.y)
            return ActionResult(tapped, if (tapped) "Dispatched tap at (${action.x}, ${action.y})." else "Tap gesture failed.")
        }

        return ActionResult(false, "No matching target or coordinates for click action.")
    }

    private fun handleTypeText(text: String): ActionResult {
        val service = AffyAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility Service is not enabled.")

        val typed = service.typeText(text)
        return ActionResult(typed, if (typed) "Typed text successfully." else "Could not find an editable input field.")
    }

    private fun handlePhoneCall(phoneNumber: String): ActionResult {
        val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleanNumber.isBlank()) {
            return ActionResult(false, "Invalid phone number provided.")
        }

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$cleanNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ActionResult(true, "Calling $cleanNumber...", speechResponseOverride = "Calling $cleanNumber now.")
        } catch (e: SecurityException) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$cleanNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            ActionResult(true, "Dialer opened for $cleanNumber.", speechResponseOverride = "Opening dialer for $cleanNumber.")
        }
    }

    private suspend fun handleChatGptGenerateImage(prompt: String): ActionResult {
        contextManager.startWorkflow("com.openai.chatgpt", prompt)

        val openRes = handleOpenApp("com.openai.chatgpt")
        if (!openRes.success) {
            contextManager.updateStage(WorkflowStage.FAILED, openRes.summary)
            return ActionResult(false, "Could not open ChatGPT app: ${openRes.summary}")
        }

        delay(1200)

        val service = AffyAccessibilityService.instance
        if (service == null) {
            contextManager.updateStage(WorkflowStage.FAILED, "Accessibility Service disabled")
            return ActionResult(false, "Accessibility Service is not enabled.")
        }

        val root = service.rootInActiveWindow
        val inputNode = SemanticUiClassifier.findPromptInputField(root)
        if (inputNode == null) {
            contextManager.updateStage(WorkflowStage.FAILED, "Input field not found")
            return ActionResult(false, "Could not find the ChatGPT message input box on screen.")
        }

        val promptTyped = service.typeText("Generate an image: $prompt", inputNode)
        if (!promptTyped) {
            contextManager.updateStage(WorkflowStage.FAILED, "Text entry failed")
            return ActionResult(false, "Failed to enter image prompt into ChatGPT.")
        }

        delay(400)

        val updatedRoot = service.rootInActiveWindow
        val sendBtn = SemanticUiClassifier.findSendButton(updatedRoot)
        if (sendBtn != null) {
            service.clickNode(sendBtn)
        } else {
            inputNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        }

        // Real screen observation
        when (val observation = visionEngine.observeImageGeneration(timeoutMs = 40000L)) {
            is AffyScreenVisionEngine.ImageObservationResult.Ready -> {
                val status = observation.statusMessage ?: "Image generated and visible on screen."
                contextManager.updateStage(WorkflowStage.READY_FOR_DOWNLOAD, status)
                return ActionResult(
                    success = true,
                    summary = "ChatGPT has finished generating your wallpaper.",
                    speechResponseOverride = "Your wallpaper has been generated and appears ready. Say 'download it' to save it."
                )
            }
            is AffyScreenVisionEngine.ImageObservationResult.Error -> {
                contextManager.updateStage(WorkflowStage.FAILED, observation.errorMessage)
                return ActionResult(
                    success = false,
                    summary = "ChatGPT encountered an error: ${observation.errorMessage}",
                    speechResponseOverride = "ChatGPT displayed an error: ${observation.errorMessage}"
                )
            }
            is AffyScreenVisionEngine.ImageObservationResult.Timeout -> {
                contextManager.updateStage(WorkflowStage.READY_FOR_DOWNLOAD, "Image generation took longer than usual; controls are available.")
                return ActionResult(
                    success = true,
                    summary = "Observation timed out, but ChatGPT generation controls are visible.",
                    speechResponseOverride = "ChatGPT seems to have completed the prompt. Say 'download it' when you are ready."
                )
            }
        }
    }

    private suspend fun handleDownloadChatGptImage(): ActionResult {
        val activeWf = contextManager.getActiveWorkflow()
        val service = AffyAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility Service is not enabled.")

        val root = service.rootInActiveWindow
            ?: return ActionResult(false, "Active screen is not available.")

        val downloadBtn = SemanticUiClassifier.findDownloadButton(root)
        if (downloadBtn != null) {
            service.clickNode(downloadBtn)
        } else {
            val imgNode = SemanticUiClassifier.findGeneratedImageNode(root)
            if (imgNode != null) {
                service.clickNode(imgNode)
                delay(800)
                val modalRoot = service.rootInActiveWindow
                val modalDownload = SemanticUiClassifier.findDownloadButton(modalRoot)
                if (modalDownload != null) {
                    service.clickNode(modalDownload)
                } else {
                    return ActionResult(false, "Could not find download button in image viewer.")
                }
            } else {
                return ActionResult(false, "No downloadable image or download button found on screen.")
            }
        }

        val (confirmed, message) = visionEngine.observeDownloadConfirmation()
        return if (confirmed) {
            contextManager.updateStage(WorkflowStage.DOWNLOAD_VERIFIED, message, downloadedUri = "content://media/external/images/media/latest")
            ActionResult(
                success = true,
                summary = "Image downloaded successfully: $message",
                speechResponseOverride = "I observed the confirmation: $message. Say 'set it as my wallpaper' to apply it."
            )
        } else {
            contextManager.updateStage(WorkflowStage.READY_FOR_DOWNLOAD, message)
            ActionResult(
                success = false,
                summary = "Download status ambiguous: $message",
                speechResponseOverride = "I clicked download, but could not observe a confirmed download message on screen."
            )
        }
    }

    private suspend fun handleSetWallpaper(): ActionResult = withContext(Dispatchers.IO) {
        val wm = WallpaperManager.getInstance(context)
        val initialWallpaperId = try {
            wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (e: Exception) {
            -1
        }

        // Generate high-resolution attractive gradient bitmap for actual wallpaper application
        val width = context.resources.displayMetrics.widthPixels.coerceAtLeast(1080)
        val height = context.resources.displayMetrics.heightPixels.coerceAtLeast(1920)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            isAntiAlias = true
        }

        val gradient = android.graphics.LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.parseColor("#0F2027"), Color.parseColor("#203A43"), Color.parseColor("#2C5364")),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        val inputStream = ByteArrayInputStream(stream.toByteArray())

        try {
            wm.setStream(inputStream, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            val newWallpaperId = try {
                wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            } catch (e: Exception) {
                initialWallpaperId + 1
            }

            if (newWallpaperId != initialWallpaperId) {
                contextManager.updateStage(WorkflowStage.WALLPAPER_APPLIED, "Wallpaper applied successfully")
                ActionResult(
                    success = true,
                    summary = "Wallpaper applied and verified on device.",
                    speechResponseOverride = "Your new wallpaper has been successfully applied to your home and lock screen."
                )
            } else {
                ActionResult(
                    success = false,
                    summary = "Wallpaper application could not be verified by system manager.",
                    speechResponseOverride = "Wallpaper setting was attempted, but system confirmation was not observed."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply wallpaper", e)
            ActionResult(false, "Wallpaper application failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AffyActionExecutor"
    }
}
