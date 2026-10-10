package com.maverock24.pimobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maverock24.pimobile.net.Pairing
import com.maverock24.pimobile.ui.ChatScreen
import com.maverock24.pimobile.ui.ChatViewModel
import com.maverock24.pimobile.ui.PiRemoteTheme
import com.maverock24.pimobile.ui.SettingsScreen
import com.maverock24.pimobile.ui.UpdateStatus
import com.maverock24.pimobile.update.UpdateChecker
import com.maverock24.pimobile.voice.Dictation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * A scanned pairing link arrives as an intent, usually before there is any
     * composition to hand it to, so it is parked here until the UI picks it up.
     */
    private val pendingPairLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePairingIntent(intent)
        if (BuildConfig.DEBUG) {
            // Log (do not crash) any accidental blocking network call on the UI thread.
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }
        setContent {
            val vm: ChatViewModel = viewModel()
            PiRemoteTheme(mode = vm.appearance, theme = vm.theme) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                var showSettings by rememberSaveable { mutableStateOf(false) }
                var listening by remember { mutableStateOf(false) }
                var partialText by remember { mutableStateOf("") }
                var notice by remember { mutableStateOf<String?>(null) }
                // A dismissed update banner stays dismissed until the next check,
                // so the Install action cannot be reached by a tap meant to close it.
                var updateDismissed by rememberSaveable { mutableStateOf(false) }
                var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Checking) }

                // One check and one install, in one place. The chat banner and the
                // settings section both read the status this writes, so the two
                // never disagree about whether a newer build exists.
                val checkForUpdates: () -> Unit = {
                    scope.launch {
                        updateStatus = UpdateStatus.Checking
                        // A fresh check is a fresh chance to offer the update.
                        updateDismissed = false
                        updateStatus = runCatching { UpdateChecker.check(BuildConfig.VERSION_CODE) }.fold(
                            onSuccess = { info ->
                                if (info != null) {
                                    UpdateStatus.Available(info)
                                } else {
                                    // A null result means the installed build is the
                                    // newest one, not that the check failed.
                                    UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
                                }
                            },
                            onFailure = { error ->
                                UpdateStatus.Failed(error.message ?: error.javaClass.simpleName)
                            },
                        )
                    }
                }

                val installUpdate: (UpdateChecker.Info) -> Unit = { info ->
                    scope.launch {
                        runCatching { UpdateChecker.download(context, info) }
                            .onSuccess { file ->
                                if (UpdateChecker.needsInstallPermission(context)) {
                                    UpdateChecker.requestInstallPermission(context)
                                    notice = "Allow installs for Pi Remote, then install again"
                                } else {
                                    notice = UpdateChecker.install(context, file) ?: "Installer launched"
                                }
                            }
                            .onFailure { error ->
                                updateStatus = UpdateStatus.Failed(error.message ?: "Update failed")
                            }
                    }
                }

                val dictation = remember {
                    Dictation(
                        context = context,
                        onPartial = { partialText = it },
                        onFinal = { text ->
                            partialText = ""
                            vm.appendToDraft(text)
                        },
                        onListeningChanged = { listening = it },
                        onError = { notice = it },
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        dictation.start()
                    } else {
                        notice = "Microphone permission is required for dictation"
                    }
                }

                LaunchedEffect(Unit) {
                    vm.connect()
                    checkForUpdates()
                }

                // QR pairing: the camera hands the app pi-remote://pair?…, we spend the
                // one-time code and adopt the endpoint and token that come back. Nothing
                // else is stored on the phone, and the token never travelled in the QR.
                val pairLink by pendingPairLink
                LaunchedEffect(pairLink) {
                    val link = pairLink ?: return@LaunchedEffect
                    pendingPairLink.value = null // a code is spent once, not per recomposition
                    val invite = Pairing.parse(link)
                    if (invite == null) {
                        notice = "That link is not a pairing code"
                        return@LaunchedEffect
                    }
                    notice = "Pairing…"
                    runCatching { Pairing.exchange(invite, android.os.Build.MODEL ?: "phone") }
                        .onSuccess { paired ->
                            vm.applyPairing(paired.baseUrl, paired.token)
                            vm.connect()
                            notice = "Paired as ${paired.device}"
                            showSettings = false // pairing is setup work; go back to the chat
                        }
                        .onFailure { notice = "Pairing failed: ${it.message}" }
                }

                DisposableEffect(Unit) {
                    onDispose { dictation.destroy() }
                }

                // Returning from the background: the event stream is usually dead
                // by then, so re-establish it and refresh any open question.
                DisposableEffect(Unit) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            vm.ensureConnected()
                        }
                    }
                    this@MainActivity.lifecycle.addObserver(observer)
                    onDispose { this@MainActivity.lifecycle.removeObserver(observer) }
                }

                // The settings screen shows the bridge token: keep it out of
                // screenshots, screen recordings and the recents thumbnail.
                DisposableEffect(showSettings) {
                    val window = this@MainActivity.window
                    if (showSettings) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }

                if (showSettings) {
                    SettingsScreen(
                        initialBaseUrl = vm.baseUrl,
                        initialToken = vm.token,
                        statusLine = vm.statusLine,
                        versionLabel = "Pi Remote ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        sessionLabel = vm.attachedLabel.ifBlank { vm.sessionTitle },
                        appearance = vm.appearance,
                        onAppearanceChange = vm::updateAppearance,
                        theme = vm.theme,
                        onThemeChange = vm::updateTheme,
                        updateStatus = updateStatus,
                        onCheckUpdates = checkForUpdates,
                        onInstallUpdate = installUpdate,
                        onPairLink = { pendingPairLink.value = it },
                        onSave = { url, token ->
                            vm.saveSettings(url, token)
                            showSettings = false
                        },
                        onTest = vm::testConnection,
                        lastCrash = vm.lastCrash,
                        quarantined = vm.quarantinedData,
                        onClearCrashState = vm::clearCrashState,
                        onBack = { showSettings = false },
                    )
                } else {
                    ChatScreen(
                        vm = vm,
                        listening = listening,
                        partialText = partialText,
                        notice = (updateStatus as? UpdateStatus.Available)
                            ?.takeUnless { updateDismissed }
                            ?.let { "Update ${it.info.versionName} ready — tap to install" },
                        bootNotice = null,
                        onToggleMic = {
                            if (listening) {
                                dictation.stop()
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    runCatching { dictation.start() }
                                        .onFailure { notice = "Could not start dictation: ${it.javaClass.simpleName}" }
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onOpenSettings = { showSettings = true },
                        // Install is the banner's action, never its dismiss.
                        onInstallUpdate = {
                            (updateStatus as? UpdateStatus.Available)?.let { installUpdate(it.info) }
                        },
                        onDismissNotice = { updateDismissed = true },
                        onDismissBootNotice = {},
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    /** Only a pi-remote://pair link counts as an invite; everything else is ignored. */
    private fun handlePairingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = intent.dataString ?: return
        if (Pairing.parse(link) != null) {
            pendingPairLink.value = link
        }
    }
}
