package com.maverock24.pimobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.maverock24.pimobile.ui.ChatScreen
import com.maverock24.pimobile.ui.ChatViewModel
import com.maverock24.pimobile.ui.PiRemoteTheme
import com.maverock24.pimobile.ui.SettingsScreen
import com.maverock24.pimobile.update.UpdateChecker
import com.maverock24.pimobile.voice.Dictation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PiRemoteTheme {
                val context = LocalContext.current
                val vm: ChatViewModel = viewModel()
                val scope = rememberCoroutineScope()

                var showSettings by rememberSaveable { mutableStateOf(false) }
                var listening by remember { mutableStateOf(false) }
                var partialText by remember { mutableStateOf("") }
                var notice by remember { mutableStateOf<String?>(null) }
                var pendingUpdate by remember { mutableStateOf<UpdateChecker.Info?>(null) }

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
                    val update = runCatching { UpdateChecker.check(BuildConfig.VERSION_CODE) }.getOrNull()
                    if (update != null) {
                        pendingUpdate = update
                    }
                }

                DisposableEffect(Unit) {
                    onDispose { dictation.destroy() }
                }

                if (showSettings) {
                    SettingsScreen(
                        initialBaseUrl = vm.baseUrl,
                        initialToken = vm.token,
                        statusLine = vm.statusLine,
                        versionLabel = "Pi Remote ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        onSave = { url, token ->
                            vm.saveSettings(url, token)
                            showSettings = false
                        },
                        onTest = vm::testConnection,
                        onBack = { showSettings = false },
                    )
                } else {
                    ChatScreen(
                        vm = vm,
                        listening = listening,
                        partialText = partialText,
                        notice = pendingUpdate?.let { "Update ${it.versionName} ready — tap to install" },
                        onToggleMic = {
                            if (listening) {
                                dictation.stop()
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    dictation.start()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onOpenSettings = { showSettings = true },
                        onCheckUpdates = {
                            scope.launch {
                                val update = runCatching { UpdateChecker.check(BuildConfig.VERSION_CODE) }.getOrNull()
                                if (update == null) {
                                    notice = "Already on the newest build"
                                } else {
                                    pendingUpdate = update
                                    notice = "Downloading ${update.versionName}…"
                                    runCatching { UpdateChecker.download(context, update) }
                                        .onSuccess { file ->
                                            if (UpdateChecker.needsInstallPermission(context)) {
                                                UpdateChecker.requestInstallPermission(context)
                                                notice = "Allow installs for Pi Remote, then tap the banner again"
                                                pendingUpdate = null
                                            } else {
                                                UpdateChecker.install(context, file)
                                                notice = "Installer launched"
                                            }
                                        }
                                        .onFailure { notice = "Update failed: ${it.message}" }
                                }
                            }
                        },
                        onDismissNotice = {
                            if (pendingUpdate != null) {
                                val update = pendingUpdate
                                pendingUpdate = null
                                if (update != null) {
                                    scope.launch {
                                        runCatching { UpdateChecker.download(context, update) }
                                            .onSuccess { file ->
                                                if (UpdateChecker.needsInstallPermission(context)) {
                                                    UpdateChecker.requestInstallPermission(context)
                                                    notice = "Allow installs for Pi Remote, then check for updates again"
                                                } else {
                                                    UpdateChecker.install(context, file)
                                                }
                                            }
                                            .onFailure { notice = "Update failed: ${it.message}" }
                                    }
                                }
                            } else {
                                notice = null
                            }
                        },
                    )
                }
            }
        }
    }
}
