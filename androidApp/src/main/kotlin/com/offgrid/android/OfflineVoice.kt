package com.offgrid.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Main-thread only. Never fall back to a recognition service that may upload audio. */
class OfflineVoice(private val context: Context, private val message: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    init {
        tts = TextToSpeech(context) { code ->
            ready = code == TextToSpeech.SUCCESS
        }
    }
    fun listen(onText: (String) -> Unit, onDone: () -> Unit) {
        if(Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            message("Offline dictation is unavailable on this device. It requires Android 12+ and an installed on-device speech service.")
            onDone(); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    message(when(error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech recognized. Try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for dictation."
                        else -> "Offline dictation unavailable (code $error). Check the downloaded speech language in device settings."
                    }); onDone()
                }
                override fun onResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
                    onDone()
                }
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }
    }
    fun speak(text: String) {
        val engine = tts
        if(!ready || engine == null) { message("Speech engine is not ready. Try again shortly."); return }
        val voice = engine.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        if(voice == null) { message("Install an offline text-to-speech voice for your language in Android settings."); return }
        engine.voice = voice
        engine.stop()
        text.chunked(TextToSpeech.getMaxSpeechInputLength() - 1).forEachIndexed { index, part ->
            engine.speak(part, TextToSpeech.QUEUE_ADD, null, "offgrid-$index")
        }
    }
    fun stop() { recognizer?.cancel(); tts?.stop() }
    fun close() { recognizer?.destroy(); recognizer = null; tts?.shutdown(); tts = null }
}
