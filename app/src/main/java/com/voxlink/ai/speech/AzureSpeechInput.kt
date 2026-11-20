package com.voxlink.ai.speech

import android.os.Handler
import android.os.Looper
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageConfig
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageResult
import com.microsoft.cognitiveservices.speech.CancellationErrorCode
import com.microsoft.cognitiveservices.speech.CancellationReason
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.voxlink.ai.model.Lang
import java.net.URI
import com.microsoft.cognitiveservices.speech.SpeechRecognizer as AzureRecognizer

/**
 * Azure Speech continuous recognition with automatic language identification (PT-BR / EN / 中文).
 * Nobody needs to pick the language: every sentence is identified on its own, even if the
 * speakers switch languages in the middle of the conversation.
 */
class AzureSpeechInput(private val cb: SpeechInput.Callbacks) : SpeechEngine {

    private val main = Handler(Looper.getMainLooper())
    private var rec: AzureRecognizer? = null
    private var audio: AudioConfig? = null
    @Volatile private var running = false

    var key: String = ""
    var region: String = "brazilsouth"
    var candidates: List<Lang> = Lang.entries
    var silenceMs: Long = 1500L

    override fun start(lang: Lang, alternatives: List<Lang>) {
        if (running) return
        running = true
        try {
            val r = key.trim()
            val reg = region.trim().ifEmpty { "brazilsouth" }
            val cfg = SpeechConfig.fromEndpoint(URI("wss://$reg.stt.speech.microsoft.com/speech/universal/v2"), r)
            cfg.setProperty("SpeechServiceConnection_LanguageIdMode", "Continuous")
            cfg.setProperty("Speech_SegmentationSilenceTimeoutMs", silenceMs.coerceIn(300L, 3000L).toString())
            cfg.setProperty("SpeechServiceResponse_PostProcessingOption", "TrueText")
            val auto = AutoDetectSourceLanguageConfig.fromLanguages(candidates.map { it.bcp47 })
            val a = AudioConfig.fromDefaultMicrophoneInput()
            val recognizer = AzureRecognizer(cfg, auto, a)

            recognizer.sessionStarted.addEventListener { _, _ -> main.post { cb.onReady() } }
            recognizer.speechStartDetected.addEventListener { _, _ -> main.post { cb.onSpeechStart() } }
            recognizer.recognizing.addEventListener { _, e ->
                val t = e.result.text
                if (!t.isNullOrBlank()) main.post {
                    cb.onRms(7f)
                    cb.onPartial(t)
                }
            }
            recognizer.recognized.addEventListener { _, e ->
                if (e.result.reason == ResultReason.RecognizedSpeech && !e.result.text.isNullOrBlank()) {
                    val t = e.result.text
                    val l = runCatching { AutoDetectSourceLanguageResult.fromResult(e.result).language }.getOrNull()
                    main.post { cb.onFinal(t, l) }
                }
            }
            recognizer.canceled.addEventListener { _, e ->
                if (e.reason == CancellationReason.Error) {
                    running = false
                    val code = if (e.errorCode == CancellationErrorCode.AuthenticationFailure ||
                        e.errorCode == CancellationErrorCode.Forbidden
                    ) SpeechInput.AZURE_AUTH_ERROR else SpeechInput.AZURE_ERROR
                    main.post { cb.onError(code) }
                }
            }
            recognizer.startContinuousRecognitionAsync()
            rec = recognizer
            audio = a
        } catch (e: Throwable) {
            running = false
            main.post { cb.onError(SpeechInput.AZURE_ERROR) }
        }
    }

    override fun cancel() {
        running = false
        val r = rec ?: return
        val a = audio
        rec = null
        audio = null
        Thread {
            runCatching { r.stopContinuousRecognitionAsync().get() }
            runCatching { r.close() }
            runCatching { a?.close() }
        }.start()
    }

    override fun destroy() = cancel()
}
