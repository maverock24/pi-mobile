package com.maverock24.pimobile.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Dictation built on the platform speech recogniser. While the user keeps the
 * mic on we restart listening after every result, so long prompts can be spoken
 * in several breaths; each final result is handed to [onFinal] to append.
 */
class Dictation(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var consecutiveErrors = 0

    val isRecognitionAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    val isListening: Boolean
        get() = listening

    fun start() {
        if (!isRecognitionAvailable) {
            onError("Speech recognition is not available on this device")
            return
        }
        listening = true
        consecutiveErrors = 0
        onListeningChanged(true)
        main.post { beginSession() }
    }

    fun stop() {
        listening = false
        onListeningChanged(false)
        main.post {
            recognizer?.stopListening()
        }
    }

    fun destroy() {
        listening = false
        main.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun beginSession() {
        if (!listening) return
        val existing = recognizer ?: runCatching {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
        }.getOrElse {
            listening = false
            onListeningChanged(false)
            onError("Speech recogniser unavailable: ${it.javaClass.simpleName}")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }
        runCatching { existing.startListening(intent) }
            .onFailure { onError("Could not start listening: ${it.message}") }
    }

    private fun restartSoon(delayMs: Long = 350L) {
        if (!listening) return
        main.postDelayed({ if (listening) beginSession() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Recognition ended; we restart from onResults/onError.
        }

        override fun onError(error: Int) {
            if (!listening) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveErrors = 0
                    restartSoon()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartSoon(800L)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    listening = false
                    onListeningChanged(false)
                    onError("Microphone permission is missing")
                }
                else -> {
                    consecutiveErrors += 1
                    if (consecutiveErrors >= 3) {
                        listening = false
                        onListeningChanged(false)
                        onError("Speech recognition stopped (error $error)")
                    } else {
                        restartSoon(600L)
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (text.isNotEmpty()) onFinal(text)
            restartSoon()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
