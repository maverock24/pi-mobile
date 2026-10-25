package com.maverock24.pimobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.maverock24.pimobile.net.Problem
import com.maverock24.pimobile.net.Tailscale
import com.maverock24.pimobile.net.VpnState

/**
 * The app's one way to draw a failure: the short summary first, then a row of
 * small controls, and the technical detail only when the human unfolds it.
 *
 * Folding is not hiding. The detail is one tap away and selectable, because the
 * last several debugging rounds depended on reading the exception, and the whole
 * point of the summary is that it is short enough to read without one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProblemFold(
    problem: Problem,
    modifier: Modifier = Modifier,
    summaryColor: Color = MaterialTheme.colorScheme.error,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    offerTailscale: Boolean = true,
) {
    // Keyed on the words, so a new failure folds the old one back up rather than
    // reusing the state of a message that is no longer on screen.
    var open by rememberSaveable(problem.summary, problem.detail) { mutableStateOf(false) }
    val hasDetail = problem.detail.isNotBlank()
    val showTailscale = offerTailscale && problem.tailnet
    val context = LocalContext.current
    val vpn = if (showTailscale) remember(problem) { Tailscale.state(context) } else VpnState.Off

    Column(modifier = modifier) {
        Text(
            text = problem.summary,
            style = MaterialTheme.typography.bodySmall,
            color = summaryColor,
        )
        if (showTailscale) {
            // Android does not name the app that owns a VPN, so the line states
            // what it can support and lets the human read Tailscale's own screen.
            Text(
                text = vpnLine(vpn),
                style = MaterialTheme.typography.bodySmall,
                color = detailColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val chips = hasDetail || showTailscale ||
            (actionLabel != null && onAction != null) || onDismiss != null
        if (chips) {
            // A flow rather than a row: four chips do not fit one line on a
            // narrow phone, and a clipped "Dismiss" is a dead end.
            FlowRow {
                val colors = ButtonDefaults.textButtonColors(contentColor = summaryColor)
                if (hasDetail) {
                    TextButton(
                        onClick = { open = !open },
                        colors = colors,
                        modifier = Modifier.tactile(),
                    ) {
                        Text(if (open) "Hide details" else "Details", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (showTailscale) {
                    TailscaleChip(context = context, colors = colors)
                }
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, colors = colors, modifier = Modifier.tactile()) {
                        Text(actionLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss, colors = colors, modifier = Modifier.tactile()) {
                        Text("Dismiss", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (open && hasDetail) {
            SelectionContainer {
                Text(
                    text = problem.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = detailColor,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * Opens Tailscale, which is the whole of what this app can do about the tunnel.
 * Tapping it does not connect anything: the human flips the switch in the app
 * that owns the VPN, and the app says so rather than claiming a connection it
 * cannot see.
 */
@Composable
private fun TailscaleChip(
    context: Context,
    colors: ButtonColors,
) {
    // Visibility is granted by the manifest's <queries> entry. Asking once per
    // composition is enough: an install or uninstall while the app runs is rare
    // and the next failure re-reads it.
    val installed = remember(context) { Tailscale.installed(context) }
    TextButton(
        onClick = { if (installed) Tailscale.open(context) else Tailscale.install(context) },
        colors = colors,
        modifier = Modifier.tactile(),
    ) {
        Text(
            text = if (installed) "Open Tailscale" else "Get Tailscale",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The VPN state in the one sentence the platform's APIs support. */
private fun vpnLine(state: VpnState): String = when (state) {
    VpnState.Off -> "No VPN is on."
    VpnState.On -> "A VPN is on. Android does not say which app runs it."
}
