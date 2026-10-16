package com.maverock24.pimobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import com.maverock24.pimobile.web.WebAccess
import com.maverock24.pimobile.web.WebAccessService
import com.maverock24.pimobile.web.WebAccessStatus
import com.maverock24.pimobile.web.WebApprovalGate

/**
 * The Settings section that turns the web server on and off.
 *
 * The address and the fingerprint are only shown while the server is up, because
 * they are only useful then: the address can change with the Wi-Fi lease, and the
 * fingerprint belongs to the certificate this run is serving.
 */
@Composable
internal fun WebAccessSection() {
    val context = LocalContext.current
    val status by WebAccess.status.collectAsState()
    val pending by WebApprovalGate.shared.pending.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }

    SettingsSection(
        title = "Web access",
        subtitle = when {
            pending != null -> "A browser is asking to connect"
            else -> when (val current = status) {
                WebAccessStatus.Off -> "Off"
                WebAccessStatus.Starting -> "Starting"
                is WebAccessStatus.On -> current.addresses.firstOrNull()
                    ?.let { "On · ${it.url(WebAccess.PORT)}" }
                    ?: "On · no address right now"
                is WebAccessStatus.Failed -> current.message
            }
        },
        icon = Icons.Filled.Share,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(
            text = "Serve this page to another computer on the same Wi-Fi, including the " +
                "phone's own hotspot. The server runs only while you ask it to, and stops " +
                "itself once the browser has been gone for a while, so no listener is left " +
                "when nobody is using it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val current = status) {
            is WebAccessStatus.On -> {
                if (current.addresses.isEmpty()) {
                    Text(
                        text = "The phone has no address the other computer can reach right " +
                            "now. Turn the hotspot or Wi-Fi back on and the address will " +
                            "reappear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    SectionLabel("Open one of these addresses on the other computer")
                    current.addresses.forEach { candidate ->
                        SelectionContainer {
                            Text(
                                candidate.url(WebAccess.PORT),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Text(
                            text = candidate.transport
                                ?.let { "$it on ${candidate.interfaceName}" }
                                ?: candidate.interfaceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "The hotspot's interface name differs by phone, so if one " +
                            "address does not answer, try the next.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SectionLabel("Certificate fingerprint (SHA-256)")
                SelectionContainer {
                    Text(
                        text = current.fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = "The browser will warn about a self-signed certificate. Before you " +
                        "continue, check that the fingerprint it shows is this one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is WebAccessStatus.Failed -> Text(
                text = current.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
        pending?.let { request ->
            SectionLabel("A browser is asking to connect")
            Text(
                text = "From ${request.sourceAddress}. Request ${request.shortId}. " +
                    "Allow it only if you opened this page yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                Button(
                    onClick = { WebApprovalGate.shared.approve(request.id) },
                    modifier = Modifier.heightIn(min = AnswerStyle.buttonHeight),
                ) {
                    Text("Allow", style = MaterialTheme.typography.bodyLarge)
                }
                OutlinedButton(
                    onClick = { WebApprovalGate.shared.deny(request.id) },
                    modifier = Modifier.heightIn(min = AnswerStyle.buttonHeight),
                ) {
                    Text("Deny", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        val label = MaterialTheme.typography.bodyLarge
        val size = Modifier.heightIn(min = AnswerStyle.buttonHeight)
        if (status is WebAccessStatus.On || status is WebAccessStatus.Starting) {
            OutlinedButton(
                onClick = {
                    // The stop goes through the service rather than `stopService`,
                    // so the listening socket is closed on a path that also tells
                    // the UI it is off.
                    context.startService(
                        Intent(context, WebAccessService::class.java)
                            .setAction(WebAccessService.ACTION_STOP),
                    )
                },
                modifier = size.tactile(haptics = true, depth = AnswerStyle.keyDepth),
            ) {
                Text("Stop web access", style = label)
            }
        } else {
            Button(
                onClick = {
                    // Started from this visible screen, which is what allows a
                    // foreground service to start at all on Android 12 and later.
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, WebAccessService::class.java),
                    )
                    requestDozeExemption(context)
                },
                modifier = size.tactile(haptics = true, depth = AnswerStyle.keyDepth),
            ) {
                Text("Start web access", style = label)
            }
        }
    }
}

/**
 * Asks the human to exempt the app from battery optimization, which design
 * section 9 item 7 chose so the server keeps answering while the phone sleeps in
 * a pocket.
 *
 * The ask is best effort. A device may not have the Settings screen and the human
 * may decline, so the server is started either way: the exemption improves the
 * asleep case and a refusal only costs that, never the ability to serve.
 */
private fun requestDozeExemption(context: Context) {
    val power = context.getSystemService(PowerManager::class.java) ?: return
    if (power.isIgnoringBatteryOptimizations(context.packageName)) return
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(request) }
}
