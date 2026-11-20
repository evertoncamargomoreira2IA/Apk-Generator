package com.voxlink.ai

import android.app.Application
import android.media.AudioDeviceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
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
import com.voxlink.ai.data.Exporter
import com.voxlink.ai.data.Net
import com.voxlink.ai.data.SettingsStore
import com.voxlink.ai.model.AppSettings
import com.voxlink.ai.model.Entry
import com.voxlink.ai.model.Lang
import com.voxlink.ai.model.Pinyin
import com.voxlink.ai.model.SAMPLE_PHRASES
import com.voxlink.ai.model.TranslationMode
import com.voxlink.ai.speech.SpeechInput
import com.voxlink.ai.sync.Peer
import com.voxlink.ai.sync.SyncManager
import com.voxlink.ai.sync.SyncState
import com.voxlink.ai.sync.SyncTransport
import com.voxlink.ai.translate.Glossary
import com.voxlink.ai.translate.LanguageDetector
import com.voxlink.ai.translate.OfflineTranslator
import com.voxlink.ai.translate.OnlineTranslator
import com.voxlink.ai.tts.AndroidVoiceEngine
import com.voxlink.ai.tts.AudioRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Conversation manager.
 *  • Online (default, fastest): Google recogniser + online translation + pinyin, languages alternate by turns.
 *  • Offline: fixed direction chosen by the user (e.g. Português → 中文, tap "Trocar"), on-device recogniser,
 *    ML Kit translation and the voices downloaded on the phone. No AI model, no internet.
 *  • Two phones (sync): each phone listens only to its owner and sends the translation to the other phone,
 *    which speaks it. Wi-Fi local / hotspot, Wi-Fi Direct or Bluetooth.
 */
class MainViewModel(app: Application) : AndroidViewModel(app), SpeechInput.Callbacks, SyncManager.Listener {

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
    var pendingText by mutableStateOf(""); private set
    var rms by mutableFloatStateOf(0f); private set
    var lastOriginal by mutableStateOf(""); private set
    var lastFrom by mutableStateOf<Lang?>(null); private set
    var lastTranslation by mutableStateOf(""); private set
    var lastTo by mutableStateOf<Lang?>(null); private set
    var lastOriginalPinyin by mutableStateOf<String?>(null); private set
    var lastTranslationPinyin by mutableStateOf<String?>(null); private set
    var lastProvider by mutableStateOf(""); private set
    var translating by mutableStateOf(false); private set
    var speaking by mutableStateOf(false); private set
    var muted by mutableStateOf(false); private set
    var offlineNow by mutableStateOf(false); private set
    /** Translation shown live while the person is still speaking. */
    var liveTranslation by mutableStateOf(""); private set
    /** Time from end of speech to translation ready, e.g. "0,4 s". */
    var lastLatency by mutableStateOf(""); private set
    /** True while someone is speaking (drives the blinking language-coloured indicator). */
    var voiceActive by mutableStateOf(false); private set

    // ---- lecture ("Palestra") mode ----
    var lectureActive by mutableStateOf(false); private set
    var lectureSpeaker by mutableStateOf(settings.other); private set
    var lectureTarget by mutableStateOf(settings.me); private set
    var lectureStart by mutableStateOf(0L); private set
    private val lectureEndQueue = mutableListOf<Pair<String, Lang>>()
    private var voiceOffJob: Job? = null
    var androidVoices by mutableStateOf<List<Voice>>(emptyList()); private set
    var androidEngines by mutableStateOf<List<TextToSpeech.EngineInfo>>(emptyList()); private set
    var toast by mutableStateOf<String?>(null)
    val history = mutableStateListOf<Entry>()

    // ---- sync state ----
    var syncState by mutableStateOf(SyncState.IDLE); private set
    var syncInfo by mutableStateOf(""); private set
    var syncPeers by mutableStateOf<List<Peer>>(emptyList()); private set
    var partnerName by mutableStateOf<String?>(null); private set
    var partnerLang by mutableStateOf<Lang?>(null); private set
    var remotePartial by mutableStateOf(""); private set
    val syncConnected: Boolean get() = syncState == SyncState.CONNECTED

    // ---- pipeline components ----
    private val speech = SpeechInput(ctx, this)
    private val detector = LanguageDetector()
    private val online = OnlineTranslator()
    private val offline = OfflineTranslator()
    private var glossary = Glossary(settings.glossary)
    var androidTts = AndroidVoiceEngine(ctx, settings.androidEngine); private set
    private val router = AudioRouter(ctx)
    private val sync = SyncManager(ctx, this)

    private val processLock = Mutex()
    private var restartJob: Job? = null
    private var playJob: Job? = null
    private var flushJob: Job? = null
    private val playQueue = ArrayDeque<Pair<String, Lang>>()
    private var recLang: Lang = settings.me
    private var pendingHeard: Lang = settings.me
    private var lastFinalText = ""
    private var lastFinalAt = 0L
    private var lastSpokenText = ""
    private var lastSpokenAt = 0L
    private var lastPartialSent = 0L
    private var consecutiveErrors = 0
    private var micPausedForTts = false
    private var liveJob: Job? = null
    private var flushAt = 0L
    private val onDeviceFailed = mutableSetOf<Lang>()
    private val previewCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 60
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch { refreshOffline() } }
        override fun onLost(network: Network) { viewModelScope.launch { refreshOffline() } }
    }

    init {
        if (settings.saveHistory) history.addAll(store.loadHistory())
        sync.myName = settings.deviceName.ifBlank { Build.MODEL ?: "VoxLink" }
        refreshOffline()
        runCatching {
            (ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(netCallback)
        }
        loadVoices()
        autoPrepareOffline()
    }

    /** On Wi-Fi, quietly download the on-device translation packs for the selected pair (fast + offline). */
    private fun autoPrepareOffline() {
        viewModelScope.launch {
            val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@launch
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return@launch
            val a = settings.me
            val b = settings.other
            if (offline.isReady(a, b)) return@launch
            runCatching { offline.download(a, b) }
        }
    }

    fun other(l: Lang): Lang = if (l == settings.me) settings.other else settings.me

    private fun refreshOffline() {
        val was = offlineNow
        offlineNow = settings.forceOffline || !Net.online(ctx)
        if (was != offlineNow) {
            toast = if (offlineNow) "Modo offline: ${listenLang.label} → ${other(listenLang).label}. Use \"Trocar\" para inverter."
            else "Internet de volta — modo online"
            if (sessionActive && !paused && !micPausedForTts) {
                restartJob?.cancel()
                speech.cancel()
                restartSoon(300)
            }
        }
    }

    /** In offline and two-phone modes the language is fixed (no detection, no automatic switching). */
    private val fixedLanguage: Boolean get() = offlineNow || syncConnected || lectureActive

    // ================= Lecture mode =================

    /** Starts (or re-targets) the lecture: one speaker talks, the app translates every pause. */
    fun startLecture(speaker: Lang, target: Lang) {
        lectureSpeaker = speaker
        lectureTarget = if (target == speaker) other(speaker) else target
        if (!lectureActive) {
            lectureActive = true
            lectureStart = System.currentTimeMillis()
            lectureEndQueue.clear()
        }
        listenLang = speaker
        if (sessionActive && !paused && !micPausedForTts) {
            restartJob?.cancel()
            speech.cancel()
            restartSoon(200)
        }
    }

    fun setLectureSpeaker(l: Lang) = startLecture(l, if (l == lectureTarget) other(l) else lectureTarget)
    fun setLectureTarget(l: Lang) = startLecture(if (l == lectureSpeaker) other(l) else lectureSpeaker, l)
    fun swapLecture() = startLecture(lectureTarget, lectureSpeaker)

    /** Leaves lecture mode. In "read at the end" mode, reads the whole translation now. */
    fun stopLecture() {
        if (!lectureActive) return
        if (pendingText.isNotBlank()) flushNow()
        lectureActive = false
        stopSession()
        if (settings.lectureVoice == "AT_END" && lectureEndQueue.isNotEmpty() && !muted) {
            val items = lectureEndQueue.toList()
            lectureEndQueue.clear()
            items.forEach { (t, l) -> enqueueSpeech(t, l, force = true) }
        }
    }

    /** Lecture entries in chronological order. */
    fun lectureEntries(): List<Entry> = history.filter { it.time >= lectureStart }.reversed()

    private fun markVoice() {
        voiceActive = true
        voiceOffJob?.cancel()
        voiceOffJob = viewModelScope.launch {
            delay(700)
            voiceActive = false
        }
    }

    // ================= Session control =================

    fun startSession() {
        refreshOffline()
        if (!speech.available) {
            toast = "Reconhecimento de voz indisponível. Instale/atualize o app Google."
            status = "Reconhecimento de voz indisponível"
            return
        }
        sessionActive = true
        paused = false
        consecutiveErrors = 0
        if (syncConnected) listenLang = settings.me
        if (listenLang != settings.me && listenLang != settings.other) listenLang = settings.me
        startListening()
    }

    fun stopSession() {
        if (pendingText.isNotBlank()) flushNow()
        sessionActive = false
        paused = false
        voiceActive = false
        liveJob?.cancel()
        liveTranslation = ""
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

    /** Choose which language is spoken next (chip tap / face-to-face half / offline direction). */
    fun listenFor(l: Lang) {
        if (lectureActive) {
            setLectureSpeaker(l)
            return
        }
        if (syncConnected && l != settings.me) {
            toast = "Com dois celulares, cada um ouve só o seu dono (${settings.me.label})."
            return
        }
        listenLang = l
        if (sessionActive && !paused && !micPausedForTts) {
            restartJob?.cancel()
            speech.cancel()
            startListening()
        }
    }

    /** Offline: invert the direction (Português → 中文  ⇄  中文 → Português). */
    fun swapDirection() {
        val next = other(listenLang)
        listenFor(next)
        toast = "Agora: ${next.flag} ${next.label} → ${other(next).flag} ${other(next).label}"
    }

    private fun startListening() {
        if (!sessionActive || paused || micPausedForTts) return
        if (syncConnected) listenLang = settings.me
        if (lectureActive) listenLang = lectureSpeaker
        recLang = listenLang
        speech.silenceMs = (settings.endPause * 1000).toLong()
        speech.preferOffline = offlineNow || (settings.onDeviceRecognition && recLang !in onDeviceFailed)
        speech.start(recLang, if (fixedLanguage) emptyList() else listOf(other(recLang)))
        listening = true
        status = when {
            syncConnected -> "Ouvindo você (${recLang.flag}) → enviando para ${partnerName ?: "o outro celular"}"
            offlineNow -> "Offline · ${recLang.flag} ${recLang.label} → ${other(recLang).flag} ${other(recLang).label}"
            else -> "Ouvindo ${recLang.flag} ${recLang.label}…"
        }
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
        if (listening && !syncConnected && !offlineNow) status = "Ouvindo ${recLang.flag} ${recLang.label}… fale naturalmente"
    }

    override fun onSpeechStart() {
        markVoice()
        flushJob?.cancel()
        status = "Captando fala…"
    }

    override fun onRms(db: Float) {
        rms = ((db + 2f) / 12f).coerceIn(0f, 1f)
        if (rms > 0.55f && listening) markVoice()
    }

    override fun onPartial(text: String) {
        markVoice()
        flushJob?.cancel()
        partial = text
        scheduleLivePreview()
        if (syncConnected) {
            val now = System.currentTimeMillis()
            if (now - lastPartialSent > 350) {
                lastPartialSent = now
                sync.send(JSONObject().put("t", "partial").put("text", joinSentences(pendingText, text)).put("from", recLang.code))
            }
        }
    }

    override fun onFinal(text: String, detectedLanguage: String?) {
        listening = false
        rms = 0f
        consecutiveErrors = 0
        val t = text.trim()
        partial = ""
        if (t.isNotEmpty()) {
            if (pendingText.isBlank()) pendingHeard = recLang
            pendingText = joinSentences(pendingText, t)
            // A sentence that ends with punctuation is finished: translate almost immediately.
            val pause = (settings.endPause * 1000).toLong()
            val endsSentence = t.trimEnd().lastOrNull()?.let { it in ".?!。？！" } == true
            scheduleFlush(if (endsSentence) minOf(pause, 450L) else pause)
            scheduleLivePreview(0)
        }
        restartSoon(60)
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
        liveJob?.cancel()
        flushAt = System.currentTimeMillis()
        val text = pendingText.trim()
        if (text.isEmpty()) return
        pendingText = ""
        val heard = pendingHeard
        viewModelScope.launch { processLock.withLock { process(text, heard) } }
    }

    /** Live subtitles: translate what is being said (on-device, ~0.1 s) while the person talks. */
    private fun scheduleLivePreview(debounce: Long = 280) {
        if (!settings.liveSubtitles) return
        val text = joinSentences(pendingText, partial).trim()
        if (text.length < 2) return
        val src = recLang
        val tgt = if (syncConnected) (partnerLang ?: settings.other) else other(src)
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            delay(debounce)
            val key = "${src.code}|${tgt.code}|$text"
            val out = previewCache[key] ?: run {
                val r = if (offline.isReady(src, tgt)) runCatching { offline.translate(text, src, tgt) }.getOrNull()
                else if (!offlineNow) runCatching { withTimeout(2500) { online.translate(text, src, tgt) } }.getOrNull()
                else null
                r?.also { previewCache[key] = it }
            }
            if (out != null && (pendingText.isNotBlank() || partial.isNotBlank())) liveTranslation = out
        }
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
                if (!offlineNow && settings.onDeviceRecognition && recLang !in onDeviceFailed) {
                    // On-device pack missing: use the online recogniser now and download the pack for next time.
                    onDeviceFailed += recLang
                    speech.downloadOfflinePack(recLang)
                    toast = "Baixando reconhecimento no aparelho para ${recLang.label}. Usando o online por enquanto."
                } else if (offlineNow) {
                    toast = "Reconhecimento offline de ${recLang.label} não instalado. Vá em ⚙ → Preparar modo offline."
                    speech.downloadOfflinePack(recLang)
                }
                speech.advanced = false
                restartSoon(800)
            }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                partial = ""
                if (pendingText.isNotBlank() && flushJob?.isActive != true) scheduleFlush(400)
                restartSoon(150)
                return
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                speech.destroy()
                restartSoon(600)
            }
            else -> {
                if (code == SpeechRecognizer.ERROR_CLIENT && consecutiveErrors >= 2) speech.advanced = false
                if (code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) refreshOffline()
                status = SpeechInput.errorText(code)
                restartSoon(if (code == SpeechRecognizer.ERROR_NETWORK) 1500 else 500)
            }
        }
        consecutiveErrors++
        if (consecutiveErrors > 10) {
            stopSession()
            status = SpeechInput.errorText(code)
            toast = "Reconhecimento de voz falhou repetidamente. Verifique o app Google e os pacotes offline."
        }
    }

    // ================= Translation =================

    private suspend fun process(text: String, heard: Lang, typed: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!typed && text.equals(lastFinalText, ignoreCase = true) && now - lastFinalAt < 5000) return
        lastFinalText = text
        lastFinalAt = now

        val s = settings
        val src = if (fixedLanguage || typed) heard else detector.detect(text, heard, null, setOf(s.me, s.other))
        val tgt = when {
            syncConnected -> partnerLang ?: s.other
            lectureActive -> lectureTarget
            src == s.me -> s.other
            src == s.other -> s.me
            heard == s.me -> s.other
            else -> s.me
        }.let { if (it == src) other(src) else it }

        lastOriginal = text
        lastFrom = src
        lastOriginalPinyin = null
        remotePartial = ""

        translating = true
        status = "Traduzindo ${src.flag} → ${tgt.flag}…"
        val prepared = if (s.technicalMode) glossary.apply(text, src, tgt) else text
        val t0 = System.currentTimeMillis()
        val result = store.memoryLookup(text, src, tgt)?.let { TrResult(it, "Memória ✎") } ?: translate(prepared, src, tgt)
        translating = false
        liveTranslation = ""
        val ms = System.currentTimeMillis() - (if (flushAt in 1..t0) flushAt else t0)
        lastLatency = String.format(java.util.Locale("pt", "BR"), "%.1f s", ms / 1000f)

        if (result == null) {
            lastTranslation = if (offlineNow) "Tradução offline indisponível — ⚙ → Preparar modo offline (baixar pacotes)."
            else "Tradução indisponível — verifique a internet."
            lastTo = tgt
            lastProvider = "erro"
            status = "Falha na tradução"
            return
        }
        val out = result.text
        val srcPy = if (src == Lang.ZH) (result.sourceRoman?.takeIf { prepared == text } ?: Pinyin.of(text)) else null
        val dstPy = if (tgt == Lang.ZH) (result.targetRoman ?: Pinyin.of(out)) else null
        lastTranslation = out
        lastTranslationPinyin = dstPy
        lastOriginalPinyin = srcPy
        lastTo = tgt
        lastProvider = result.provider
        addHistory(Entry(now, text, src, out, tgt, now, result.provider, srcPy, dstPy))

        if (syncConnected) {
            // Two phones: the other phone speaks the translation to its owner.
            sync.send(
                JSONObject().put("t", "seg").put("id", now)
                    .put("text", text).put("from", src.code)
                    .put("translation", out).put("to", tgt.code)
                    .put("op", srcPy ?: "").put("tp", dstPy ?: "")
            )
            status = "Enviado para ${partnerName ?: "o outro celular"} ✓"
            return
        }

        if (s.autoSwitch && sessionActive && !fixedLanguage) listenLang = tgt

        if (lectureActive) {
            when (s.lectureVoice) {
                "SILENT" -> status = "Legenda pronta"
                "AT_END" -> { lectureEndQueue += out to tgt; status = "Guardado para ler no final" }
                else -> if (!muted) enqueueSpeech(out, tgt)
            }
            return
        }

        if (s.autoSpeak && !muted) {
            enqueueSpeech(out, tgt)
        } else {
            status = "Tradução pronta"
            if (sessionActive && !paused && listenLang != recLang) {
                restartJob?.cancel()
                speech.cancel()
                restartSoon(150)
            }
        }
    }

    private fun addHistory(e: Entry) {
        history.add(0, e)
        if (history.size > 300) history.removeRange(300, history.size)
        if (settings.saveHistory) store.saveHistory(history)
    }

    private data class TrResult(val text: String, val provider: String, val sourceRoman: String? = null, val targetRoman: String? = null)

    /**
     * Fast translation: on-device (ML Kit, ~0.1 s) and online run in parallel. The online result (better
     * quality + pinyin) is used only if it arrives in time; otherwise the on-device one, so it is never slow.
     */
    private suspend fun translate(text: String, from: Lang, to: Lang): TrResult? = coroutineScope {
        if (from == to) return@coroutineScope TrResult(text, "—")
        // Sentences corrected by the user are reused exactly (translation memory).
        store.memoryLookup(text, from, to)?.let { return@coroutineScope TrResult(it, "Memória ✎") }
        val localReady = offline.isReady(from, to)
        val useOnline = !offlineNow && settings.translationMode == TranslationMode.ONLINE_THEN_OFFLINE
        val onlineJob = if (useOnline) async {
            runCatching { withTimeout(8000) { online.translateFull(text, from, to) } }.getOrNull()
        } else null
        val localJob = if (localReady) async { runCatching { offline.translate(text, from, to) }.getOrNull() } else null

        val wait = if (settings.speedMode) 700L else 4000L
        val on = when {
            onlineJob == null -> null
            localJob == null -> onlineJob.await()
            else -> withTimeoutOrNull(wait) { onlineJob.await() }
        }
        if (on != null) {
            localJob?.cancel()
            return@coroutineScope TrResult(on.text, "Online", on.sourceRoman, on.targetRoman)
        }
        onlineJob?.cancel()
        localJob?.await()?.let { return@coroutineScope TrResult(it, "No aparelho") }
        if (!localReady) {
            // Packs not downloaded yet: download now (needs internet once), then translate.
            runCatching { withTimeout(90_000) { offline.translate(text, from, to) } }.getOrNull()
                ?.let { return@coroutineScope TrResult(it, "No aparelho") }
        }
        null
    }

    /** Downloads everything the offline mode needs: translation packs + offline recognition packs. */
    fun prepareOffline() {
        viewModelScope.launch {
            toast = "Baixando pacotes offline de tradução (≈30 MB por idioma)…"
            try {
                val langs = listOf(settings.me, settings.other).distinct()
                for (a in Lang.entries) for (b in Lang.entries) {
                    if (a != b && a.ordinal < b.ordinal && (a in langs || b in langs)) offline.download(a, b)
                }
                val rec = langs.map { speech.downloadOfflinePack(it) }
                toast = if (rec.all { it }) "Pacotes offline prontos. O reconhecimento offline está sendo instalado pelo Android."
                else "Tradução offline pronta. Instale o reconhecimento offline em Configurações do Android → Idioma → Reconhecimento de fala."
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
                    // To MY headphones: keep listening (full duplex). To the loudspeaker: pause the mic.
                    // Default: the mic never listens while the app speaks. Only with headphones AND the option on.
                    val duplex = router.isHeadphones(device) && settings.listenWhileSpeaking
                    if (!duplex && !micPausedForTts) {
                        voiceActive = false
                        micPausedForTts = true
                        restartJob?.cancel()
                        flushJob?.cancel()
                        speech.cancel()
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
    private fun outputFor(l: Lang): AudioDeviceInfo? {
        if (!settings.splitAudio) return null
        val phones = router.headphones() ?: return null
        return if (l == settings.me) phones else router.speaker()
    }

    private suspend fun speakOne(text: String, lang: Lang, device: AudioDeviceInfo?) {
        try {
            androidTts.speak(text, lang, settings, device)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (device != null) runCatching { androidTts.speak(text, lang, settings, null) }
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

    /**
     * Typed input (keyboard or quick phrase): translated, shown with pinyin and spoken —
     * or sent to the other phone when the two phones are connected.
     */
    fun sendTyped(text: String, from: Lang) {
        val t = text.trim()
        if (t.isEmpty()) return
        flushAt = System.currentTimeMillis()
        viewModelScope.launch { processLock.withLock { process(t, from, typed = true) } }
    }

    // ================= Teach the app: corrections =================

    /** Number of corrected sentences remembered. */
    var memoryCount by mutableStateOf(store.memorySize()); private set

    /**
     * The user fixed a translation: update the history, remember the whole sentence for next time
     * and (optionally) add the technical term to the active glossary.
     */
    fun correctEntry(e: Entry, corrected: String, termFrom: String, termTo: String) {
        val fixed = corrected.trim()
        if (fixed.isNotEmpty() && fixed != e.translation) {
            store.memorySave(e.original, e.from, e.to, fixed)
            memoryCount = store.memorySize()
            val py = if (e.to == Lang.ZH) Pinyin.of(fixed) else null
            val idx = history.indexOfFirst { it.id == e.id }
            val updated = e.copy(translation = fixed, translationPinyin = py, provider = "Corrigido ✎")
            if (idx >= 0) history[idx] = updated
            if (lastOriginal == e.original) {
                lastTranslation = fixed
                lastTranslationPinyin = py
                lastProvider = "Corrigido ✎"
            }
            if (settings.saveHistory) store.saveHistory(history)
            if (syncConnected) {
                sync.send(
                    JSONObject().put("t", "seg").put("id", System.currentTimeMillis())
                        .put("text", e.original).put("from", e.from.code)
                        .put("translation", fixed).put("to", e.to.code)
                        .put("op", e.originalPinyin ?: "").put("tp", py ?: "")
                )
            }
        }
        if (termFrom.isNotBlank() && termTo.isNotBlank()) addGlossaryTerm(e.from, termFrom.trim(), e.to, termTo.trim())
        toast = when {
            termFrom.isNotBlank() && termTo.isNotBlank() -> "Aprendido ✓ frase + termo no glossário \"${settings.activeGlossary}\""
            fixed != e.translation -> "Aprendido ✓ — esta frase será traduzida assim da próxima vez"
            else -> "Nada alterado"
        }
    }

    /** Adds "pt | en | zh" line with the two known languages to the active glossary. */
    fun addGlossaryTerm(a: Lang, termA: String, b: Lang, termB: String) {
        val cols = Lang.entries.map { l -> when (l) { a -> termA; b -> termB; else -> "" } }
        val line = cols.joinToString(" | ")
        val name = settings.activeGlossary
        update { st ->
            val cur = st.glossaries[name] ?: ""
            st.copy(glossaries = st.glossaries + (name to (cur.trimEnd() + "\n" + line + "\n")))
        }
    }

    fun clearMemory() {
        store.memoryClear()
        memoryCount = 0
        toast = "Memória de correções apagada"
    }

    // ================= Glossaries by area =================

    fun selectGlossary(name: String) {
        if (name !in settings.glossaries) return
        update { it.copy(activeGlossary = name, technicalMode = true) }
        toast = "Glossário: $name (${glossary.size} termos)"
    }

    fun saveGlossaryText(name: String, text: String) = update { it.copy(glossaries = it.glossaries + (name to text)) }

    fun addGlossaryArea(name: String) {
        val n = name.trim()
        if (n.isEmpty() || n in settings.glossaries) return
        update { it.copy(glossaries = it.glossaries + (n to "# $n · formato: português | english | 中文\n"), activeGlossary = n) }
    }

    fun deleteGlossaryArea(name: String) {
        if (settings.glossaries.size <= 1) return
        update { st ->
            val m = st.glossaries - name
            st.copy(glossaries = m, activeGlossary = if (st.activeGlossary == name) m.keys.first() else st.activeGlossary)
        }
    }

    // ================= Meeting minutes =================

    /** Writes the conversation as .xlsx or .docx in the cache and returns the file (to share). */
    fun exportMinutes(word: Boolean): java.io.File? {
        if (history.isEmpty()) {
            toast = "Ainda não há conversa para exportar."
            return null
        }
        return runCatching {
            val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = java.io.File(dir, Exporter.fileName(if (word) "docx" else "xlsx"))
            val who = buildString {
                append(sync.myName).append(" (").append(settings.me.label).append(")")
                append(" · ").append(partnerName ?: "Interlocutor").append(" (").append((partnerLang ?: settings.other).label).append(")")
            }
            val meta = Exporter.Meta("Ata de conversa — VoxLink AI", who, settings.activeGlossary)
            val list = history.reversed()
            if (word) Exporter.writeDocx(f, meta, list) else Exporter.writeXlsx(f, meta, list)
            f
        }.onFailure { toast = "Falha ao gerar a ata: ${it.message}" }.getOrNull()
    }

    // ================= Camera (signs, labels, screens) =================

    data class OcrItem(val original: String, val from: Lang, val translation: String, val to: Lang, val originalPinyin: String?, val translationPinyin: String?)

    /** Translates one block of text read by the camera into my language (or into the other, if it is already mine). */
    suspend fun translateForCamera(text: String): OcrItem? {
        val t = text.replace('\n', ' ').trim()
        if (t.isEmpty()) return null
        val s = settings
        val src = if (Pinyin.hasHan(t)) Lang.ZH else detector.detect(t, if (s.me == Lang.ZH) Lang.EN else s.me, null, setOf(s.me, s.other, Lang.EN))
        val tgt = if (src == s.me) s.other else s.me
        val prepared = if (s.technicalMode) glossary.apply(t, src, tgt) else t
        val r = store.memoryLookup(t, src, tgt)?.let { TrResult(it, "Memória ✎") } ?: translate(prepared, src, tgt) ?: return null
        return OcrItem(
            t, src, r.text, tgt,
            if (src == Lang.ZH) (r.sourceRoman?.takeIf { prepared == t } ?: Pinyin.of(t)) else null,
            if (tgt == Lang.ZH) (r.targetRoman ?: Pinyin.of(r.text)) else null,
        )
    }

    fun speakText(text: String, lang: Lang) = enqueueSpeech(text, lang, force = true)

    fun testVoice(lang: Lang) = enqueueSpeech(SAMPLE_PHRASES.getValue(lang), lang, force = true)

    // ================= Two-phone sync =================

    fun syncHostWifi() = sync.hostWifi()
    fun syncDiscoverWifi() = sync.discoverWifi()
    fun syncJoinWifi(host: String) = sync.joinWifi(host)
    fun syncStartWifiDirect() = sync.startWifiDirect()
    fun syncHostBluetooth() = sync.hostBluetooth()
    fun syncListBluetooth() = sync.listBluetooth()
    fun syncStop() = sync.stop()
    fun syncLocalIp(): String = sync.localIp()

    fun syncConnectPeer(p: Peer) = when (p.transport) {
        SyncTransport.WIFI -> sync.joinWifi(p.id)
        SyncTransport.WIFI_DIRECT -> sync.connectWifiDirect(p)
        SyncTransport.BLUETOOTH -> sync.joinBluetooth(p)
    }

    override fun onSyncState(state: SyncState, info: String) {
        val wasConnected = syncState == SyncState.CONNECTED
        syncState = state
        syncInfo = info
        if (state == SyncState.CONNECTED) {
            sync.send(JSONObject().put("t", "hello").put("name", sync.myName).put("lang", settings.me.code))
            listenLang = settings.me
            if (sessionActive && !paused && !micPausedForTts) {
                speech.cancel()
                restartSoon(300)
            }
        } else if (wasConnected) {
            partnerName = null
            partnerLang = null
            remotePartial = ""
            toast = "Celulares desconectados: $info"
        }
    }

    override fun onPeers(peers: List<Peer>) {
        syncPeers = peers
    }

    override fun onSyncMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello" -> {
                partnerName = msg.optString("name").ifBlank { "outro celular" }
                val pl = Lang.fromCode(msg.optString("lang"))
                partnerLang = pl
                if (pl != null && pl != settings.me && pl != settings.other) update { it.copy(other = pl) }
                toast = "Conectado a $partnerName (${pl?.flag ?: ""} ${pl?.label ?: ""})"
                if (!sessionActive) status = "Conectado a $partnerName — toque no microfone para começar"
            }
            "partial" -> remotePartial = msg.optString("text")
            "seg" -> {
                val from = Lang.fromCode(msg.optString("from")) ?: partnerLang ?: settings.other
                val to = Lang.fromCode(msg.optString("to")) ?: settings.me
                val text = msg.optString("text")
                val translation = msg.optString("translation")
                remotePartial = ""
                viewModelScope.launch { receiveSegment(text, from, translation, to, msg.optString("op"), msg.optString("tp")) }
            }
            "bye" -> toast = "${partnerName ?: "O outro celular"} encerrou a conexão"
        }
    }

    private suspend fun receiveSegment(text: String, from: Lang, translation: String, to: Lang, op: String, tp: String) {
        var out = translation
        var dst = to
        var dstPy = tp.ifBlank { null }
        if (out.isBlank() || dst != settings.me) {
            // Sender could not translate into my language: do it here.
            dst = settings.me
            val r = translate(text, from, dst) ?: return
            out = r.text
            dstPy = if (dst == Lang.ZH) r.targetRoman ?: Pinyin.of(out) else null
        }
        val now = System.currentTimeMillis()
        lastOriginal = text
        lastFrom = from
        lastOriginalPinyin = op.ifBlank { null } ?: if (from == Lang.ZH) Pinyin.of(text) else null
        lastTranslation = out
        lastTo = dst
        lastTranslationPinyin = dstPy ?: if (dst == Lang.ZH) Pinyin.of(out) else null
        lastProvider = "de ${partnerName ?: "outro celular"}"
        addHistory(Entry(now, text, from, out, dst, now, lastProvider, lastOriginalPinyin, lastTranslationPinyin))
        if (settings.autoSpeak && !muted) enqueueSpeech(out, dst) else status = "Recebido de ${partnerName ?: "outro celular"}"
    }

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
        if (old.deviceName != s.deviceName) sync.myName = s.deviceName.ifBlank { Build.MODEL ?: "VoxLink" }
        if (old.me != s.me || old.other != s.other) {
            if (listenLang != s.me && listenLang != s.other) listenFor(s.me)
            if (syncConnected && old.me != s.me) sync.send(JSONObject().put("t", "hello").put("name", sync.myName).put("lang", s.me.code))
        }
        if (old.forceOffline != s.forceOffline) refreshOffline()
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

    fun exportText(): String = history.reversed().joinToString("\n\n") { e ->
        buildString {
            append("${e.from.flag} ${e.original}")
            e.originalPinyin?.let { append("\n   $it") }
            append("\n${e.to.flag} ${e.translation}")
            e.translationPinyin?.let { append("\n   $it") }
        }
    }

    fun loadVoices() {
        viewModelScope.launch {
            androidVoices = androidTts.voices()
            androidEngines = androidTts.engines()
        }
    }

    val glossarySize: Int get() = glossary.size

    override fun onCleared() {
        runCatching {
            (ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(netCallback)
        }
        sync.shutdown()
        speech.destroy()
        androidTts.shutdown()
        offline.close()
    }
}
