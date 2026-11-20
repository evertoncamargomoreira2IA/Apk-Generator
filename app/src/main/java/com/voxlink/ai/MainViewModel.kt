package com.voxlink.ai

import android.app.Application
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxlink.ai.data.Net
import com.voxlink.ai.data.SettingsStore
import com.voxlink.ai.model.AppSettings
import com.voxlink.ai.model.Entry
import com.voxlink.ai.model.Pinyin
import com.voxlink.ai.model.Lang
import com.voxlink.ai.model.RecognitionEngine
import com.voxlink.ai.model.SAMPLE_PHRASES
import com.voxlink.ai.model.TranslationMode
import com.voxlink.ai.model.WhisperModel
import com.voxlink.ai.speech.WhisperModels
import com.voxlink.ai.speech.WhisperSpeechInput
import com.voxlink.ai.speech.SpeechEngine
import com.voxlink.ai.speech.SpeechInput
import com.voxlink.ai.translate.Glossary
import com.voxlink.ai.translate.LanguageDetector
import com.voxlink.ai.translate.OfflineTranslator
import com.voxlink.ai.translate.OnlineTranslator
import com.voxlink.ai.tts.AndroidVoiceEngine
import com.voxlink.ai.tts.AudioRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Conversation manager: listen → detect language → translate → queue natural voice → listen again.
 * Half-duplex: the microphone is paused while the translation is spoken, so the app never
 * re-translates its own voice.
 */
class MainViewModel(app: Application) : AndroidViewModel(app), SpeechInput.Callbacks {

    private val ctx = app.applicationContext
    private val store = SettingsStore(ctx)

    var settings by mutableStateOf(store.load()); private set

    // ---- UI state ----
    var sessionActive by mutableStateOf(false); private set
    var paused by mutableStateOf(false); private set
    var listening by mutableStateOf(false); private set
    var listenLang by mutableStateOf(settings.me); private set
    var status by mutableStateOf("Pronto para ouvir"); private set
    var partial by mutableStateOf(""); private set
    /** Sentences already recognised but waiting for the speaker to finish. */
    var pendingText by mutableStateOf(""); private set
    var rms by mutableFloatStateOf(0f); private set
    var lastOriginal by mutableStateOf(""); private set
    var lastFrom by mutableStateOf<Lang?>(null); private set
    var lastTranslation by mutableStateOf(""); private set
    var lastOriginalPinyin by mutableStateOf<String?>(null); private set
    var lastTranslationPinyin by mutableStateOf<String?>(null); private set
    var lastTo by mutableStateOf<Lang?>(null); private set
    var lastProvider by mutableStateOf(""); private set
    var translating by mutableStateOf(false); private set
    var speaking by mutableStateOf(false); private set
    var muted by mutableStateOf(false); private set
    var androidVoices by mutableStateOf<List<Voice>>(emptyList()); private set
    var androidEngines by mutableStateOf<List<TextToSpeech.EngineInfo>>(emptyList()); private set
    var toast by mutableStateOf<String?>(null)
    /** True while the offline Whisper engine (automatic language) is in use. */
    var autoDetect by mutableStateOf(false); private set
    /** Whisper model download progress (0-100) or null. */
    var whisperProgress by mutableStateOf<Int?>(null); private set
    var whisperReadyTick by mutableStateOf(0); private set
    val history = mutableStateListOf<Entry>()

    // ---- pipeline components (each behind a provider interface) ----
    private val speech = SpeechInput(ctx, this)
    private val whisper = WhisperSpeechInput(ctx, this)
    private val router = AudioRouter(ctx)
    private val detector = LanguageDetector()
    private val online = OnlineTranslator()
    private val offline = OfflineTranslator()
    private var glossary = Glossary(settings.glossary)
    var androidTts = AndroidVoiceEngine(ctx, settings.androidEngine); private set

    private val processLock = Mutex()
    private var restartJob: Job? = null
    private var playJob: Job? = null
    private val playQueue = ArrayDeque<Pair<String, Lang>>()
    private var recLang: Lang = settings.me
    private var lastFinalText = ""
    private var lastFinalAt = 0L
    private var lastSpokenText = ""
    private var lastSpokenAt = 0L
    private var consecutiveErrors = 0
    private var pendingHeard: Lang = settings.me
    private var pendingDetected: Lang? = null
    private var flushJob: Job? = null

    private var micPausedForTts = false
    private var activeSpeech: SpeechEngine = speech

    private fun cancelSpeech() {
        speech.cancel()
        whisper.cancel()
    }

    fun whisperReady(m: WhisperModel = settings.whisperModel): Boolean =
        com.voxlink.ai.speech.WhisperLib.loaded && WhisperModels.ready(ctx, m)

    /**
     * Engine choice: Whisper when selected, or automatically when there is no internet
     * (if the model is downloaded). Otherwise the free Google recogniser.
     */
    private fun chooseEngine(): RecognitionEngine {
        val wantWhisper = settings.recognitionEngine == RecognitionEngine.WHISPER || !Net.online(ctx)
        return if (wantWhisper && whisperReady()) RecognitionEngine.WHISPER else RecognitionEngine.ANDROID
    }

    init {
        if (settings.saveHistory) history.addAll(store.loadHistory())
        loadVoices()
    }

    fun other(l: Lang): Lang = if (l == settings.me) settings.other else settings.me

    // ================= Session control =================

    fun startSession() {
        if (settings.recognitionEngine == RecognitionEngine.WHISPER && !whisperReady()) {
            toast = "Baixe o modelo Whisper offline nas configurações. Usando o reconhecimento do Google por enquanto."
        }
        if (chooseEngine() == RecognitionEngine.ANDROID && !speech.available) {
            toast = "Reconhecimento de voz indisponível. Instale/atualize o app Google."
            status = "Reconhecimento de voz indisponível"
            return
        }
        sessionActive = true
        paused = false
        consecutiveErrors = 0
        if (listenLang != settings.me && listenLang != settings.other) listenLang = settings.me
        startListening()
    }

    fun stopSession() {
        if (pendingText.isNotBlank()) flushNow()
        sessionActive = false
        paused = false
        restartJob?.cancel()
        cancelSpeech()
        listening = false
        partial = ""
        rms = 0f
        stopPlayback(resume = false)
        status = "Parado"
    }

    fun togglePause() {
        if (!sessionActive) return
        paused = !paused
        if (paused) {
            restartJob?.cancel()
            cancelSpeech()
            listening = false
            rms = 0f
            status = "Escuta pausada"
        } else {
            startListening()
        }
    }

    fun onBackground() {
        if (sessionActive && !paused) togglePause()
    }

    /** Force which language is expected next (tap on a language chip). */
    fun listenFor(l: Lang) {
        listenLang = l
        if (sessionActive && !paused && !micPausedForTts) {
            restartJob?.cancel()
            cancelSpeech()
            startListening()
        }
    }

    private fun startListening() {
        if (!sessionActive || paused || micPausedForTts) return
        recLang = listenLang
        val silence = (settings.endPause * 1000).toLong()
        val engine = chooseEngine()
        val next: SpeechEngine = if (engine == RecognitionEngine.WHISPER) whisper else speech
        if (next !== activeSpeech) activeSpeech.cancel()
        activeSpeech = next
        autoDetect = engine == RecognitionEngine.WHISPER
        speech.silenceMs = silence
        speech.preferOffline = !Net.online(ctx)
        whisper.silenceMs = silence
        whisper.model = settings.whisperModel
        whisper.candidates = (listOf(settings.me, settings.other) + Lang.entries).distinct()
        activeSpeech.start(recLang, listOf(other(recLang)))
        listening = true
        status = if (autoDetect) "Ouvindo · 🌐 idioma automático (offline)" else "Ouvindo ${recLang.flag} ${recLang.label}…"
    }

    private fun restartSoon(ms: Long = 250) {
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            delay(ms)
            startListening()
        }
    }

    // ================= Recognizer callbacks =================

    override fun onReady() {
        if (listening) status = if (autoDetect) "Ouvindo · 🌐 idioma automático — fale naturalmente"
        else "Ouvindo ${recLang.flag} ${recLang.label}… fale naturalmente"
    }

    override fun onSpeechStart() {
        flushJob?.cancel() // speaker is continuing: keep waiting
        status = "Captando fala…"
    }

    override fun onRms(db: Float) {
        rms = ((db + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onPartial(text: String) {
        flushJob?.cancel() // speaker is continuing: keep waiting
        partial = text
    }

    override fun onFinal(text: String, detectedLanguage: String?) {
        listening = false
        rms = 0f
        consecutiveErrors = 0
        val t = text.trim()
        partial = ""
        if (t.isNotEmpty()) {
            val det = Lang.fromCode(detectedLanguage)
            // A different language means a different speaker: translate what we had first.
            if (pendingText.isNotBlank() && det != null && pendingDetected != null && det != pendingDetected) flushNow()
            if (pendingText.isBlank()) {
                pendingHeard = recLang
                pendingDetected = det
            }
            pendingText = joinSentences(pendingText, t)
            // Whisper already waited for the pause itself; the Google recogniser cuts on short pauses.
            scheduleFlush(if (autoDetect) 250L else (settings.endPause * 1000).toLong())
        }
        restartSoon(100)
    }

    /** Wait for a real pause before translating, so long sentences are not cut in the middle. */
    private fun scheduleFlush(ms: Long = (settings.endPause * 1000).toLong()) {
        flushJob?.cancel()
        flushJob = viewModelScope.launch {
            delay(ms)
            flushNow()
        }
    }

    /** Translate everything recognised so far right now (also: tap on the original-speech card). */
    fun flushNow() {
        flushJob?.cancel()
        val text = pendingText.trim()
        if (text.isEmpty()) return
        pendingText = ""
        val heard = pendingHeard
        val det = pendingDetected
        viewModelScope.launch { processLock.withLock { process(text, heard, det) } }
    }

    private fun joinSentences(a: String, b: String): String {
        if (a.isBlank()) return b
        val cjk = Pinyin.hasHan(a.takeLast(1)) || Pinyin.hasHan(b.take(1))
        val end = a.trimEnd().lastOrNull()
        val sep = when {
            cjk -> if (end != null && end in "。！？，,.!?") "" else "，"
            end != null && end in ".!?,;:" -> " "
            else -> ", "
        }
        return a.trimEnd() + sep + b.trimStart()
    }

    override fun onError(code: Int) {
        listening = false
        rms = 0f
        if (!sessionActive || paused) return
        when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                stopSession()
                toast = "Permita o acesso ao microfone."
                return
            }
            12, 13, 14 -> {
                speech.advanced = false
                restartSoon(200)
                return
            }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                partial = ""
                if (pendingText.isNotBlank() && flushJob?.isActive != true) scheduleFlush(400)
                restartSoon(150)
                return
            }
            SpeechInput.WHISPER_NO_MODEL, SpeechInput.WHISPER_ERROR -> {
                toast = "Whisper offline indisponível (${SpeechInput.errorText(code)}). Usando o Google."
                update { it.copy(recognitionEngine = RecognitionEngine.ANDROID) }
                restartSoon(300)
                return
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                speech.destroy()
                restartSoon(600)
            }
            else -> {
                if (code == SpeechRecognizer.ERROR_CLIENT && consecutiveErrors >= 2) speech.advanced = false
                status = SpeechInput.errorText(code)
                restartSoon(if (code == SpeechRecognizer.ERROR_NETWORK) 1500 else 500)
            }
        }
        consecutiveErrors++
        if (consecutiveErrors > 10) {
            stopSession()
            status = SpeechInput.errorText(code)
            toast = "Reconhecimento de voz falhou repetidamente. Verifique internet e o app Google."
        }
    }

    // ================= Translation =================

    private suspend fun process(text: String, heard: Lang, recDetected: Lang?) {
        val now = System.currentTimeMillis()
        if (text.equals(lastFinalText, ignoreCase = true) && now - lastFinalAt < 5000) return
        lastFinalText = text
        lastFinalAt = now

        val s = settings
        val src = detector.detect(text, heard, recDetected, setOf(s.me, s.other))
        val tgt = when (src) {
            s.me -> s.other
            s.other -> s.me
            else -> if (heard == s.me) s.other else s.me
        }
        lastOriginal = text
        lastFrom = src
        lastOriginalPinyin = null

        translating = true
        status = "Traduzindo ${src.flag} → ${tgt.flag}…"
        val prepared = if (s.technicalMode) glossary.apply(text, src, tgt) else text
        val result = translate(prepared, src, tgt)
        translating = false

        if (result == null) {
            lastTranslation = "Tradução indisponível — verifique a internet ou baixe os pacotes offline nas configurações."
            lastTo = tgt
            lastProvider = "erro"
            status = "Falha na tradução"
            return
        }
        val out = result.text
        val provider = result.provider
        val srcPy = if (src == Lang.ZH) (result.sourceRoman?.takeIf { prepared == text } ?: Pinyin.of(text)) else null
        val dstPy = if (tgt == Lang.ZH) (result.targetRoman ?: Pinyin.of(out)) else null
        lastTranslation = out
        lastTranslationPinyin = dstPy
        lastOriginalPinyin = srcPy
        lastTo = tgt
        lastProvider = provider
        history.add(0, Entry(now, text, src, out, tgt, now, provider, srcPy, dstPy))
        if (history.size > 300) history.removeRange(300, history.size)
        if (s.saveHistory) store.saveHistory(history)

        // The other person will answer in the language we just produced.
        if (s.autoSwitch && sessionActive) listenLang = tgt

        if (s.autoSpeak && !muted) {
            enqueueSpeech(out, tgt)
        } else {
            status = "Tradução pronta"
            if (s.autoSwitch && sessionActive && !paused && listenLang != recLang) {
                restartJob?.cancel()
                cancelSpeech()
                restartSoon(150)
            }
        }
    }

    private data class TrResult(val text: String, val provider: String, val sourceRoman: String? = null, val targetRoman: String? = null)

    private suspend fun translate(text: String, from: Lang, to: Lang): TrResult? {
        if (from == to) return TrResult(text, "—")
        if (settings.translationMode == TranslationMode.ONLINE_THEN_OFFLINE && Net.online(ctx)) {
            try {
                val t = withTimeout(9000) { online.translateFull(text, from, to) }
                return TrResult(t.text, "Online", t.sourceRoman, t.targetRoman)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
            }
        }
        return try {
            TrResult(withTimeout(90_000) { offline.translate(text, from, to) }, "Offline")
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            null
        }
    }

    fun downloadWhisper(m: WhisperModel = settings.whisperModel) {
        if (whisperProgress != null) return
        if (!com.voxlink.ai.speech.WhisperLib.loaded) {
            toast = "Motor Whisper não disponível neste aparelho (precisa de processador 64-bit)."
            return
        }
        viewModelScope.launch {
            whisperProgress = 0
            try {
                WhisperModels.download(ctx, m) { whisperProgress = it }
                whisperReadyTick++
                toast = "Modelo Whisper pronto — reconhecimento offline ativado"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                toast = "Falha ao baixar o modelo: ${e.message}"
            } finally {
                whisperProgress = null
            }
        }
    }

    fun deleteWhisper(m: WhisperModel) {
        WhisperModels.file(ctx, m).delete()
        whisperReadyTick++
    }

    fun downloadOfflinePacks() {
        viewModelScope.launch {
            toast = "Baixando pacotes offline (≈30 MB por idioma)…"
            try {
                val langs = Lang.entries
                for (a in langs) for (b in langs) if (a != b && a.ordinal < b.ordinal) offline.download(a, b)
                toast = "Pacotes offline prontos: PT · EN · 中文"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                toast = "Falha ao baixar pacotes: ${e.message}"
            }
        }
    }

    // ================= Audio playback manager =================

    private fun enqueueSpeech(text: String, lang: Lang, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && text == lastSpokenText && now - lastSpokenAt < 4000) return
        lastSpokenText = text
        lastSpokenAt = now
        playQueue.addLast(text to lang)
        if (playJob?.isActive == true) return
        playJob = viewModelScope.launch {
            speaking = true
            try {
                while (playQueue.isNotEmpty()) {
                    val (t, l) = playQueue.removeFirst()
                    val device = outputFor(l)
                    // Translation going to MY headphones: the mic can keep listening (full duplex).
                    // Going to the loudspeaker: pause the mic so we never translate our own voice.
                    if (!router.isHeadphones(device) && !micPausedForTts) {
                        micPausedForTts = true
                        restartJob?.cancel()
                        flushJob?.cancel()
                        cancelSpeech()
                        listening = false
                        rms = 0f
                    }
                    status = "Falando ${l.flag} ${l.label}" + if (router.isHeadphones(device)) " no fone 🎧" else "…"
                    speakOne(t, l, device)
                }
            } finally {
                speaking = false
            }
            if (micPausedForTts) {
                micPausedForTts = false
                if (pendingText.isNotBlank()) scheduleFlush(300)
                if (sessionActive && !paused) restartSoon(350) else status = "Tradução pronta"
            } else if (!sessionActive) status = "Tradução pronta"
        }
    }

    /** With headphones and split audio on: my language → headphones, the other's → loudspeaker. */
    private fun outputFor(l: Lang): android.media.AudioDeviceInfo? {
        if (!settings.splitAudio) return null
        val phones = router.headphones() ?: return null
        return if (l == settings.me) phones else router.speaker()
    }

    private suspend fun speakOne(text: String, lang: Lang, device: android.media.AudioDeviceInfo?) {
        val s = settings
        try {
            androidTts.speak(text, lang, s, device)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (device != null) runCatching { androidTts.speak(text, lang, s, null) }
            else toast = "Erro na voz: ${e.message}"
        }
    }

    fun stopPlayback(resume: Boolean = true) {
        playQueue.clear()
        playJob?.cancel()
        playJob = null
        androidTts.stop()
        speaking = false
        val wasPaused = micPausedForTts
        micPausedForTts = false
        if (resume && wasPaused && sessionActive && !paused) restartSoon(300)
    }

    fun replay() {
        val t = lastTranslation
        val l = lastTo
        if (t.isBlank() || l == null || lastProvider == "erro") {
            toast = "Nenhuma tradução para repetir."
            return
        }
        enqueueSpeech(t, l, force = true)
    }

    fun replayEntry(e: Entry) = enqueueSpeech(e.translation, e.to, force = true)

    fun toggleMute() {
        muted = !muted
        if (muted) stopPlayback()
        toast = if (muted) "Voz da tradução silenciada" else "Voz da tradução ativada"
    }

    fun testVoice(lang: Lang) = enqueueSpeech(SAMPLE_PHRASES.getValue(lang), lang, force = true)

    // ================= Settings & history =================

    fun update(transform: (AppSettings) -> AppSettings) {
        val old = settings
        val s = transform(old)
        if (s.me == s.other) return
        settings = s
        store.save(s)
        if (old.glossary != s.glossary) glossary = Glossary(s.glossary)
        if (old.androidEngine != s.androidEngine) {
            androidTts.shutdown()
            androidTts = AndroidVoiceEngine(ctx, s.androidEngine)
            loadVoices()
        }
        if (old.me != s.me || old.other != s.other) {
            if (listenLang != s.me && listenLang != s.other) listenFor(s.me)
        }
        if (old.recognitionEngine != s.recognitionEngine || old.whisperModel != s.whisperModel) {
            if (sessionActive && !paused && !micPausedForTts) {
                restartJob?.cancel()
                cancelSpeech()
                restartSoon(400)
            }
        }
        if (old.saveHistory != s.saveHistory) {
            if (s.saveHistory) store.saveHistory(history) else store.clearHistory()
        }
    }

    fun swapPair() = update { it.copy(me = it.other, other = it.me) }

    fun clearHistory() {
        history.clear()
        store.clearHistory()
        toast = "Histórico apagado"
    }

    fun exportText(): String = history.reversed().joinToString("\n\n") {
        "${it.from.flag} ${it.original}\n${it.to.flag} ${it.translation}"
    }

    fun loadVoices() {
        viewModelScope.launch {
            androidVoices = androidTts.voices()
            androidEngines = androidTts.engines()
        }
    }

    val glossarySize: Int get() = glossary.size

    override fun onCleared() {
        speech.destroy()
        whisper.destroy()
        androidTts.shutdown()
        offline.close()
    }
}
