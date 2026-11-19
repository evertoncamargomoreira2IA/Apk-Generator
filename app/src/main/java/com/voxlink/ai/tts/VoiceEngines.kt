package com.voxlink.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.voxlink.ai.data.Net
import com.voxlink.ai.model.AppSettings
import com.voxlink.ai.model.Lang
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Provider abstraction for voice synthesis. speak() suspends until playback has finished. */
interface VoiceEngine {
    val name: String
    suspend fun speak(text: String, lang: Lang, s: AppSettings)
    fun stop()
    fun shutdown()
}

/**
 * Android TextToSpeech. Automatically picks the most natural voice installed for each language
 * (Google "network"/neural voices when online, high-quality offline voices otherwise).
 */
class AndroidVoiceEngine(private val ctx: Context, enginePackage: String?) : VoiceEngine {
    override val name = "Voz neural do Android"
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
    private val tts: TextToSpeech = TextToSpeech(
        ctx,
        { status -> ready.complete(status == TextToSpeech.SUCCESS) },
        enginePackage?.takeIf { it.isNotBlank() },
    )

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private fun finish(id: String?) {
        id ?: return
        pending.remove(id)?.let { if (it.isActive) it.resume(Unit) }
    }

    suspend fun isReady(): Boolean = ready.await()

    suspend fun voices(): List<Voice> {
        if (!ready.await()) return emptyList()
        return runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
    }

    suspend fun engines(): List<TextToSpeech.EngineInfo> {
        ready.await()
        return runCatching { tts.engines }.getOrNull().orEmpty()
    }

    val defaultEngine: String get() = runCatching { tts.defaultEngine }.getOrNull().orEmpty()

    fun voicesFor(all: List<Voice>, lang: Lang): List<Voice> = all.filter { v ->
        val l = v.locale.language.lowercase()
        val c = v.locale.country.uppercase()
        l in lang.localeLanguages && when (lang) {
            Lang.PT -> c.isEmpty() || c == "BR"
            Lang.ZH -> c.isEmpty() || c == "CN"
            Lang.EN -> true
        }
    }.sortedByDescending { score(it, true) }

    private fun score(v: Voice, online: Boolean): Int {
        var s = v.quality * 10
        s += if (v.isNetworkConnectionRequired) (if (online) 6 else -10_000) else 3
        if (v.locale.country.equals("US", true)) s += 1
        if (v.latency >= Voice.LATENCY_VERY_HIGH) s -= 2
        return s
    }

    private fun pick(lang: Lang, preferred: String?): Voice? {
        val all = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val online = Net.online(ctx)
        if (!preferred.isNullOrBlank()) {
            all.firstOrNull { it.name == preferred }?.let { if (online || !it.isNetworkConnectionRequired) return it }
        }
        return voicesFor(all, lang).maxByOrNull { score(it, online) }?.takeIf { score(it, online) > 0 }
    }

    override suspend fun speak(text: String, lang: Lang, s: AppSettings) {
        if (!ready.await()) throw IOException("Motor de voz do Android indisponível")
        val v = pick(lang, s.androidVoices[lang.code])
        if (v != null) tts.voice = v else tts.language = lang.locale
        tts.setSpeechRate(s.rate)
        tts.setPitch(s.pitch)
        val id = UUID.randomUUID().toString()
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, s.volume) }
        suspendCancellableCoroutine<Unit> { cont ->
            pending[id] = cont
            cont.invokeOnCancellation {
                pending.remove(id)
                runCatching { tts.stop() }
            }
            val r = tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
            if (r != TextToSpeech.SUCCESS) finish(id)
        }
    }

    override fun stop() { runCatching { tts.stop() } }

    override fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }
}

/**
 * Microsoft Azure Neural TTS (premium, very natural voices). Requires the user's own Speech key.
 * Voices: pt-BR Francisca/Antonio, en-US Jenny/Guy, zh-CN Xiaoxiao/Yunxi.
 */
class AzureVoiceEngine(private val ctx: Context) : VoiceEngine {
    override val name = "Azure Neural"
    private var player: MediaPlayer? = null

    fun voiceName(lang: Lang, female: Boolean) = when (lang) {
        Lang.PT -> if (female) "pt-BR-FranciscaNeural" else "pt-BR-AntonioNeural"
        Lang.EN -> if (female) "en-US-JennyNeural" else "en-US-GuyNeural"
        Lang.ZH -> if (female) "zh-CN-XiaoxiaoNeural" else "zh-CN-YunxiNeural"
    }

    override suspend fun speak(text: String, lang: Lang, s: AppSettings) {
        val audio = synthesize(text, lang, s)
        val f = File(ctx.cacheDir, "tts_${System.nanoTime()}.mp3")
        f.writeBytes(audio)
        try {
            play(f, s.volume)
        } finally {
            f.delete()
        }
    }

    private fun pct(n: Int) = (if (n >= 0) "+" else "") + n + "%"

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private suspend fun synthesize(text: String, lang: Lang, s: AppSettings): ByteArray = withContext(Dispatchers.IO) {
        val region = s.azureRegion.trim().ifEmpty { "brazilsouth" }
        val rate = ((s.rate - 1f) * 100).roundToInt()
        val pitch = ((s.pitch - 1f) * 50).roundToInt()
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${lang.bcp47}'>" +
            "<voice name='${voiceName(lang, s.azureFemale)}'><prosody rate='${pct(rate)}' pitch='${pct(pitch)}'>" +
            esc(text) + "</prosody></voice></speak>"
        val c = URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 6000
        c.readTimeout = 15000
        c.setRequestProperty("Ocp-Apim-Subscription-Key", s.azureKey.trim())
        c.setRequestProperty("Content-Type", "application/ssml+xml")
        c.setRequestProperty("X-Microsoft-OutputFormat", "audio-24khz-96kbitrate-mono-mp3")
        c.setRequestProperty("User-Agent", "VoxLinkAI")
        try {
            c.outputStream.use { it.write(ssml.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw IOException("Azure HTTP ${c.responseCode} ${err.take(120)}")
            }
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun play(f: File, volume: Float) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { p, _, _ ->
                p.release()
                if (player === p) player = null
                if (cont.isActive) cont.resume(Unit)
                true
            }
            cont.invokeOnCancellation {
                runCatching { mp.stop() }
                runCatching { mp.release() }
                if (player === mp) player = null
            }
            try {
                mp.setDataSource(f.absolutePath)
                mp.setVolume(volume, volume)
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                runCatching { mp.release() }
                if (player === mp) player = null
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    override fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    override fun shutdown() = stop()
}
