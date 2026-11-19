package com.voxlink.ai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voxlink.ai.model.Lang

/**
 * Streaming speech recognition (partial + final results) on top of Android's SpeechRecognizer.
 * One recognition = one utterance; the conversation manager restarts it for continuous listening.
 */
class SpeechInput(private val ctx: Context, private val cb: Callbacks) {

    interface Callbacks {
        fun onReady()
        fun onSpeechStart()
        fun onRms(db: Float)
        fun onPartial(text: String)
        fun onFinal(text: String, detectedLanguage: String?)
        fun onError(code: Int)
    }

    private var rec: SpeechRecognizer? = null
    private var detected: String? = null

    /** Advanced extras (Android 13/14 language detection, formatting). Disabled automatically if unsupported. */
    var advanced = true

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(lang: Lang, alternatives: List<Lang>) {
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener) }
        }
        detected = null
        val all = ArrayList((listOf(lang) + alternatives).distinct().map { it.bcp47 })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.bcp47)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang.bcp47)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", all.drop(1).toTypedArray())
            if (advanced && Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
            if (advanced && Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, all)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_HIGH_PRECISION)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, all)
            }
        }
        rec?.startListening(i)
    }

    fun cancel() { runCatching { rec?.cancel() } }

    fun destroy() {
        runCatching { rec?.destroy() }
        rec = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = cb.onReady()
        override fun onBeginningOfSpeech() = cb.onSpeechStart()
        override fun onRmsChanged(rmsdB: Float) = cb.onRms(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) = cb.onError(error)
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) cb.onPartial(t)
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            cb.onFinal(t, detected)
        }

        override fun onLanguageDetection(results: Bundle) {
            if (Build.VERSION.SDK_INT >= 34) {
                detected = results.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: detected
            }
        }
    }

    companion object {
        fun errorText(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Erro de rede no reconhecimento"
            SpeechRecognizer.ERROR_AUDIO -> "Erro de áudio do microfone"
            SpeechRecognizer.ERROR_SERVER -> "Erro no servidor de reconhecimento"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sem permissão de microfone"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconhecedor ocupado"
            12, 13 -> "Idioma não disponível no reconhecedor"
            else -> "Erro de reconhecimento ($code)"
        }
    }
}
