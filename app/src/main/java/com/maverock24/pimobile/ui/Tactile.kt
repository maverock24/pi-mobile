package com.maverock24.pimobile.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Haptic feedback through the platform's action-oriented constants.
 *
 * These constants describe what an interaction means rather than which effect
 * plays, which is what Android asks apps to use: the feel stays consistent
 * across devices, the platform substitutes a fallback on hardware that cannot
 * do more, and no VIBRATE permission is needed. The same API also follows the
 * user's system-wide haptic feedback setting, so a phone with touch feedback
 * switched off stays silent without this app checking anything.
 */
object Haptics {

    /** Touch down on a control. Since API 5. */
    fun press(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Lift off after a press that was not cancelled. Since API 27; silent below that. */
    fun release(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        }
    }

    /** A request the bridge accepted. Since API 30, a virtual key press below it. */
    fun confirm(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    /**
     * A request the user started that failed. API 30 only: below it there is no
     * reject constant, and playing something stronger instead would read as a
     * different meaning, so nothing is played.
     */
    fun reject(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
    }
}

/**
 * Makes a control feel physical: it shrinks a little while it is held and comes
 * back on release. With [haptics] it also ticks on press and again on release,
 * which is the pair Android documents for buttons.
 *
 * With [depth] the control is a key on a flat surface rather than a shape on it:
 * a block in the control's own [shape], filled with [edge], is drawn behind the
 * face and offset down by that much, and the face sinks onto it while it is held.
 * The block is drawn rather than elevated on purpose, because a Material shadow
 * is invisible here: this scheme's surface is the same colour as its background.
 *
 * The depth comes out of the face, not the row, but only where the caller has
 * pinned the height: with a `heightIn(min = ...)` the control keeps that height
 * and the face gives the depth up, so the rows it sits in do not move. A control
 * left to size itself has nothing to give it up from and grows by the depth to
 * make room for the block, which is why every caller that asks for a key passes
 * a minimum height.
 *
 * The press is read from the pointer stream rather than from the control's
 * interaction source, so this drops onto any control without changing its
 * onClick or its ripple. A press that turns into a scroll or a drag is
 * cancelled and the release tick is dropped with it. The touch area is the whole
 * control, block included, and not only the face.
 */
@Composable
fun Modifier.tactile(
    haptics: Boolean = false,
    enabled: Boolean = true,
    pressScale: Float = 0.97f,
    depth: Dp = 0.dp,
    shape: Shape = RoundedCornerShape(percent = 50),
    edge: Color = MaterialTheme.colorScheme.outline,
): Modifier {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    // One animation carries both the shrink and the sink, so the two cannot
    // drift apart: down in 90ms, back in 160ms, which is what this did before.
    val press = animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = if (pressed) {
            tween(durationMillis = 90, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 160, easing = FastOutLinearInEasing)
        },
        label = "tactilePress",
    )
    val keyed = depth > 0.dp

    var modifier: Modifier = this
    if (keyed) {
        modifier = modifier.drawWithContent {
            drawContent()
            // Read inside the drawing: the animation then invalidates drawing
            // only, which is what the controls inside a lazy list need.
            val depthPx = depth.toPx()
            val face = Size(size.width, size.height - depthPx)
            if (face.height <= 0f) return@drawWithContent
            val progress = press.value
            // The bottom of the face once it has shrunk and sunk, which is where
            // the block stops: the translation below puts the face's own bottom
            // edge exactly here, so the two edges never part.
            val covered = size.height - depthPx + depthPx * progress
            if (covered >= size.height) return@drawWithContent
            val factor = 1f + (pressScale - 1f) * progress
            clipRect(top = covered) {
                // The block gets the face's own shape and the face's own shrink
                // about its centre, so the two silhouettes stay identical and
                // the unscaled block cannot poke out at a rounded corner.
                translate(top = depthPx) {
                    scale(factor, factor, pivot = Offset(size.width / 2f, face.height / 2f)) {
                        val outline = shape.createOutline(face, layoutDirection, this@drawWithContent)
                        when (outline) {
                            is Outline.Rectangle -> drawRect(edge, outline.rect.topLeft, outline.rect.size)
                            is Outline.Rounded -> drawRoundRect(
                                color = edge,
                                topLeft = Offset(outline.roundRect.left, outline.roundRect.top),
                                size = Size(outline.roundRect.width, outline.roundRect.height),
                                cornerRadius = outline.roundRect.topLeftCornerRadius,
                            )
                            is Outline.Generic -> drawPath(outline.path, edge)
                        }
                    }
                }
            }
        }
    }
    // Ahead of the padding so the whole control answers a touch and not only the
    // face: the strip the block occupies is part of the key. Flat controls skip
    // the padding entirely rather than carrying one that does nothing.
    modifier = modifier.pointerInput(enabled, haptics) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            if (haptics) Haptics.press(view)
            val up = waitForUpOrCancellation()
            pressed = false
            if (up != null && haptics) Haptics.release(view)
        }
    }
    if (keyed) {
        // The face keeps its caller's height minus the depth, and the depth is
        // the block's, so a control with a minimum height does not grow.
        modifier = modifier.padding(bottom = depth)
    }
    return modifier.graphicsLayer {
        val progress = press.value
        val factor = 1f + (pressScale - 1f) * progress
        scaleX = factor
        scaleY = factor
        // Sink by the depth plus whatever the shrink pulled up from the bottom
        // edge, so the face lands on the block instead of a hair above it.
        // Without a block there is nothing to land on.
        translationY = if (keyed) {
            progress * (depth.toPx() + (1f - pressScale) * size.height / 2f)
        } else {
            0f
        }
    }
}
