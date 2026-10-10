package com.maverock24.pimobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
                var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Checking) }

                // Declared before its own body so the retry action carried by the
                // install message can call back into it. Assigned before either
                // lambda can run, so the lateinit is set the moment it is read.
                lateinit var installUpdate: (UpdateChecker.Info) -> Unit

                // One check and one install, in one place. The settings section
                // reads the status this writes and the chat's Install entry is
                // raised from the same check, so the two never disagree about
                // whether a newer build exists.
                val checkForUpdates: () -> Unit = {
                    scope.launch {
                        updateStatus = UpdateStatus.Checking
                        updateStatus = runCatching { UpdateChecker.check(BuildConfig.VERSION_CODE) }.fold(
                            onSuccess = { info ->
                                if (info != null) {
                                    // Offering the update is a message with its own
                                    // Install action, so the dismiss and the install
                                    // are two controls and closing it never installs.
                                    vm.notifyActionable("Update ${info.versionName} ready", "Install") {
                                        installUpdate(info)
                                    }
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

                installUpdate = { info ->
                    scope.launch {
                        runCatching { UpdateChecker.download(context, info) }
                            .onSuccess { file ->
                                if (UpdateChecker.needsInstallPermission(context)) {
                                    UpdateChecker.requestInstallPermission(context)
                                    // The retry is the action on the message that
                                    // explains why the first attempt stopped.
                                    vm.notifyActionable(
                                        "Allow installs for Pi Remote, then install again",
                                        "Install",
                                    ) { installUpdate(info) }
                                } else {
                                    // A null result means the installer opened. A
                                    // message means it did not, and a failure that
                                    // clears itself after two seconds is a failure
                                    // the user may never read.
                                    val failure = UpdateChecker.install(context, file)
                                    if (failure == null) {
                                        vm.notifyConfirmation("Installer launched")
                                    } else {
                                        vm.notifyError(failure)
                                    }
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
                        onError = { vm.notifyError(it) },
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        dictation.start()
                    } else {
                        vm.notifyError("Microphone permission is required for dictation")
                    }
                }

                LaunchedEffect(Unit) {
                    vm.connect()
                    checkForUpdates()
                }

                // The channel is created at launch and posting is gated on the
                // permission, so ask once, when the app has something to post:
                // the flag keeps the prompt from returning after the user has
                // already answered it. Nothing to ask below API 33, where the
                // permission does not exist.
                var notificationPermissionAsked by rememberSaveable { mutableStateOf(false) }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT < 33) return@LaunchedEffect
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted && !notificationPermissionAsked) {
                        notificationPermissionAsked = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
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
                        vm.notifyError("That link is not a pairing code")
                        return@LaunchedEffect
                    }
                    vm.notifyConfirmation("Pairing…")
                    runCatching { Pairing.exchange(invite, android.os.Build.MODEL ?: "phone") }
                        .onSuccess { paired ->
                            vm.applyPairing(paired.baseUrl, paired.token)
                            vm.connect()
                            vm.notifyConfirmation("Paired as ${paired.device}")
                            showSettings = false // pairing is setup work; go back to the chat
                        }
                        .onFailure { vm.notifyError("Pairing failed: ${it.message}") }
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

                // One holder keyed by destination. Showing Settings disposes the
                // chat, and Compose throws a disposed composition's state away with
                // it; the holder sets that state aside under its key and hands it
                // back when the chat returns, so a trip to Settings is no longer a
                // reset.
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(if (showSettings) "settings" else "chat") {
                    if (showSettings) {
                        // Back leaves settings rather than the app. The screen is a
                        // destination and the chat is the app's body, so the gesture
                        // that closes a layer should land on the chat, not finish.
                        BackHandler(enabled = showSettings) { showSettings = false }
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
                                            .onFailure { vm.notifyError("Could not start dictation: ${it.javaClass.simpleName}") }
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            onOpenSettings = { showSettings = true },
                        )
                    }
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
