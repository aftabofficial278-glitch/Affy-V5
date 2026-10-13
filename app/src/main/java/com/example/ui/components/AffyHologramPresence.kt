package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.AffyState
import com.example.ui.theme.CosmicBlueAccent
import com.example.ui.theme.CosmicGoldDeep
import com.example.ui.theme.CosmicGoldLight
import com.example.ui.theme.CosmicGoldPrimary
import com.example.ui.theme.CosmicGoldWarm
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanCore
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.NeonViolet
import kotlin.math.min
import kotlin.math.sin

private data class SubtleStarSpec(
    val xNorm: Float,
    val yNorm: Float,
    val sizeDp: Float,
    val baseAlpha: Float,
    val phaseOffset: Float
)

/**
 * Deep, Cinematic, and Restrained Space Backdrop for AFFY 2.5.
 *
 * Designed to frame the large 3D Golden Cosmic Sphere without visual clutter:
 * - Rich obsidian-space radial depth with subtle warm golden atmospheric falloff behind the sphere.
 * - Free of noisy orbit lines, random cyan streaks, or radial spikes.
 * - Minimal, ultra-fine distant starlight pinpricks for quiet depth.
 */
@Composable
fun AffyHologramPresence(
    state: AffyState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "restrained_cosmic_bg")
    val liveAmp = amplitude.coerceIn(0f, 1f)

    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bg_shimmer"
    )

    val subtleAccent by animateColorAsState(
        targetValue = when (state) {
            AffyState.IDLE -> CosmicGoldDeep
            AffyState.CONNECTING -> CyanCore
            AffyState.LISTENING -> CosmicGoldWarm
            AffyState.THINKING -> NeonViolet
            AffyState.SPEAKING -> CosmicGoldPrimary
            AffyState.EXECUTING -> EmeraldActive
            AffyState.VERIFYING -> CyanCore
            AffyState.ERROR -> CrimsonError
        },
        animationSpec = tween(600),
        label = "bg_subtle_accent"
    )

    // 22 restrained, distant pinprick stars in the outer periphery only
    val distantStars = remember {
        List(22) { idx ->
            val x = ((idx * 0.618034f + 0.07f) % 0.92f) + 0.04f
            val y = ((idx * 0.381966f + (idx / 22f)) % 0.90f) + 0.05f
            SubtleStarSpec(
                xNorm = x,
                yNorm = y,
                sizeDp = 0.65f + (idx % 3) * 0.35f,
                baseAlpha = 0.07f + (idx % 4) * 0.045f,
                phaseOffset = idx * 0.7f
            )
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val minDim = min(w, h)
            val orbCenter = Offset(w * 0.5f, if (h > w * 1.25f) h * 0.45f else h * 0.5f)

            // 1. Deep, dark obsidian-space base canvas
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF020306),
                        Color(0xFF05060B),
                        Color(0xFF070605),
                        Color(0xFF040509),
                        Color(0xFF010204)
                    )
                ),
                size = size
            )

            // 2. Soft, restrained volumetric golden-amber atmospheric backlight behind the 3D sphere
            val glowRadius = minDim * (0.64f + liveAmp * 0.06f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CosmicGoldPrimary.copy(alpha = 0.11f + liveAmp * 0.05f),
                        subtleAccent.copy(alpha = 0.06f + liveAmp * 0.03f),
                        CosmicGoldDeep.copy(alpha = 0.03f),
                        Color.Transparent
                    ),
                    center = orbCenter,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = orbCenter
            )

            // 3. Minimal, quiet distant star pinpricks (avoiding the central sphere region)
            val exclusionRadiusSq = (minDim * 0.42f) * (minDim * 0.42f)
            distantStars.forEach { star ->
                val sx = w * star.xNorm
                val sy = h * star.yNorm
                val dx = sx - orbCenter.x
                val dy = sy - orbCenter.y
                if (dx * dx + dy * dy > exclusionRadiusSq) {
                    val twinkle = 0.65f + 0.35f * sin(shimmerPhase + star.phaseOffset)
                    val alpha = (star.baseAlpha * twinkle).coerceIn(0.03f, 0.24f)
                    drawCircle(
                        color = CosmicGoldLight.copy(alpha = alpha),
                        radius = star.sizeDp.dp.toPx(),
                        center = Offset(sx, sy)
                    )
                }
            }
        }
    }
}
