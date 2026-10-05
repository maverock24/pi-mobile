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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

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
 * The press is read from the pointer stream rather than from the control's
 * interaction source, so this drops onto any control without changing its
 * onClick or its ripple. A press that turns into a scroll or a drag is
 * cancelled and the release tick is dropped with it.
 */
@Composable
fun Modifier.tactile(
    haptics: Boolean = false,
    enabled: Boolean = true,
    pressScale: Float = 0.97f,
): Modifier {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        animationSpec = if (pressed) {
            tween(durationMillis = 90, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 160, easing = FastOutLinearInEasing)
        },
        label = "tactilePress",
    )

    return this
        .graphicsLayer {
            // Read the animation inside the layer: it then invalidates drawing
            // only, which is what the controls inside a lazy list need.
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(enabled, haptics) {
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
}
