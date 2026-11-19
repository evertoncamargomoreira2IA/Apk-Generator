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
import com.voxlink.ai.model.Lang
import com.voxlink.ai.model.SAMPLE_PHRASES
import com.voxlink.ai.model.TranslationMode
import com.voxlink.ai.model.VoiceProvider
import com.voxlink.ai.speech.SpeechInput
import com.voxlink.ai.translate.Glossary
import com.voxlink.ai.translate.LanguageDetector
import com.voxlink.ai.translate.OfflineTranslator
import com.voxlink.ai.translate.OnlineTranslator
import com.voxlink.ai.tts.AndroidVoiceEngine
import com.voxlink.ai.tts.AzureVoiceEngine
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
    var rms by mutableFloatStateOf(0f); private set
    var lastOriginal by mutableStateOf(""); private set
    var lastFrom by mutableStateOf<Lang?>(null); private set
    var lastTranslation by mutableStateOf(""); private set
    var lastTo by mutableStateOf<Lang?>(null); private set
    var lastProvider by mutableStateOf(""); private set
    var translating by mutableStateOf(false); private set
    var speaking by mutableStateOf(false); private set
    var muted by mutableStateOf(false); private set
    var androidVoices by mutableStateOf<List<Voice>>(emptyList()); private set
    var androidEngines by mutableStateOf<List<TextToSpeech.EngineInfo>>(emptyList()); private set
    var toast by mutableStateOf<String?>(null)
    val history = mutableStateListOf<Entry>()

    // ---- pipeline components (each behind a provider interface) ----
    private val speech = SpeechInput(ctx, this)
    private val detector = LanguageDetector()
    private val online = OnlineTranslator()
    private val offline = OfflineTranslator()
    private var glossary = Glossary(settings.glossary)
    var androidTts = AndroidVoiceEngine(ctx, settings.androidEngine); private set
    private val azureTts = AzureVoiceEngine(ctx)

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

    init {
        if (settings.saveHistory) history.addAll(store.loadHistory())
        loadVoices()
    }

    fun other(l: Lang): Lang = if (l == settings.me) settings.other else settings.me

    // ================= Session control =================

    fun startSession() {
        if (!speech.available) {
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
        sessionActive = false
        paused = false
        restartJob?.cancel()
        speech.cancel()
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
            speech.cancel()
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
        if (sessionActive && !paused && !speaking) {
            restartJob?.cancel()
            speech.cancel()
            startListening()
        }
    }

    private fun startListening() {
        if (!sessionActive || paused || speaking) return
        recLang = listenLang
        speech.start(recLang, listOf(other(recLang)))
        listening = true
        status = "Ouvindo ${recLang.flag} ${recLang.label}…"
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
        if (listening) status = "Ouvindo ${recLang.flag} ${recLang.label}… fale naturalmente"
    }

    override fun onSpeechStart() {
        status = "Captando fala…"
    }

    override fun onRms(db: Float) {
        rms = ((db + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onPartial(text: String) {
        partial = text
    }

    override fun onFinal(text: String, detectedLanguage: String?) {
        listening = false
        rms = 0f
        consecutiveErrors = 0
        val heard = recLang
        val t = text.trim()
        partial = ""
        if (t.isNotEmpty()) {
            viewModelScope.launch { processLock.withLock { process(t, heard, Lang.fromCode(detectedLanguage)) } }
        }
        restartSoon(150)
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
                restartSoon(150)
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
        val (out, provider) = result
        lastTranslation = out
        lastTo = tgt
        lastProvider = provider
        history.add(0, Entry(now, text, src, out, tgt, now, provider))
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
                speech.cancel()
                restartSoon(150)
            }
        }
    }

    private suspend fun translate(text: String, from: Lang, to: Lang): Pair<String, String>? {
        if (from == to) return text to "—"
        if (settings.translationMode == TranslationMode.ONLINE_THEN_OFFLINE && Net.online(ctx)) {
            try {
                return withTimeout(9000) { online.translate(text, from, to) } to "Online"
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
            }
        }
        return try {
            withTimeout(90_000) { offline.translate(text, from, to) } to "Offline"
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            null
        }
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
            restartJob?.cancel()
            speech.cancel() // half-duplex: don't listen to our own voice
            listening = false
            rms = 0f
            try {
                while (playQueue.isNotEmpty()) {
                    val (t, l) = playQueue.removeFirst()
                    status = "Falando ${l.flag} ${l.label}…"
                    speakOne(t, l)
                }
            } finally {
                speaking = false
            }
            if (sessionActive && !paused) restartSoon(350) else status = "Tradução pronta"
        }
    }

    private suspend fun speakOne(text: String, lang: Lang) {
        val s = settings
        val useAzure = s.voiceProvider == VoiceProvider.AZURE && s.azureKey.isNotBlank() && Net.online(ctx)
        try {
            if (useAzure) azureTts.speak(text, lang, s) else androidTts.speak(text, lang, s)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (useAzure) {
                toast = "Azure indisponível (${e.message}). Usando voz do Android."
                runCatching { androidTts.speak(text, lang, s) }
            } else {
                toast = "Erro na voz: ${e.message}"
            }
        }
    }

    fun stopPlayback(resume: Boolean = true) {
        playQueue.clear()
        playJob?.cancel()
        playJob = null
        androidTts.stop()
        azureTts.stop()
        speaking = false
        if (resume && sessionActive && !paused) restartSoon(300)
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
        androidTts.shutdown()
        azureTts.shutdown()
        offline.close()
    }
}
