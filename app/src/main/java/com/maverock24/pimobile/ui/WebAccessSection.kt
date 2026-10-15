package com.maverock24.pimobile.ui

import android.content.Intent
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
    var expanded by rememberSaveable { mutableStateOf(false) }

    SettingsSection(
        title = "Web access",
        subtitle = when (val current = status) {
            WebAccessStatus.Off -> "Off"
            WebAccessStatus.Starting -> "Starting"
            is WebAccessStatus.On -> "On · ${current.url}"
            is WebAccessStatus.Failed -> current.message
        },
        icon = Icons.Filled.Share,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(
            text = "Serve this page to another computer on the same Wi-Fi. The server runs " +
                "only while you ask it to, and stops itself once the browser has been gone " +
                "for a while, so no listener is left when nobody is using it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val current = status) {
            is WebAccessStatus.On -> {
                SectionLabel("Open this address on the other computer")
                SelectionContainer {
                    Text(current.url, style = MaterialTheme.typography.bodyLarge)
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
                },
                modifier = size.tactile(haptics = true, depth = AnswerStyle.keyDepth),
            ) {
                Text("Start web access", style = label)
            }
        }
    }
}
