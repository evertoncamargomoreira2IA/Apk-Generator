package com.voxlink.ai.tts

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Provider abstraction for voice synthesis. speak() suspends until playback has finished. */
interface VoiceEngine {
    val name: String
    /** [device]: preferred output (headphones / loudspeaker), or null for the system default. */
    suspend fun speak(text: String, lang: Lang, s: AppSettings, device: AudioDeviceInfo? = null)
    fun stop()
    fun shutdown()
}

/** Finds the headphones (Bluetooth, wired, USB) and the phone loudspeaker. */
class AudioRouter(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun headphones(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HEADPHONE_TYPES }

    fun speaker(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    fun isHeadphones(d: AudioDeviceInfo?) = d != null && d.type in HEADPHONE_TYPES

    companion object {
        val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            26, // TYPE_BLE_HEADSET (Android 12+)
        )
    }
}

/** Plays an audio file on a specific output device and suspends until it ends. */
suspend fun playFile(f: File, volume: Float, device: AudioDeviceInfo?) = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setOnCompletionListener {
            it.release()
            if (cont.isActive) cont.resume(Unit)
        }
        mp.setOnErrorListener { p, _, _ ->
            p.release()
            if (cont.isActive) cont.resume(Unit)
            true
        }
        cont.invokeOnCancellation {
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        try {
            mp.setDataSource(f.absolutePath)
            mp.setVolume(volume, volume)
            if (device != null && Build.VERSION.SDK_INT >= 28) mp.setPreferredDevice(device)
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            runCatching { mp.release() }
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
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

    private fun score(v: Voice, online: Boolean, preferLocal: Boolean = false): Int {
        var s = v.quality * 10
        s += if (v.isNetworkConnectionRequired) (if (!online) -10_000 else if (preferLocal) -150 else 6) else 3
        if (v.locale.country.equals("US", true)) s += 1
        if (v.latency >= Voice.LATENCY_VERY_HIGH) s -= 2
        return s
    }

    private fun pick(lang: Lang, preferred: String?, preferLocal: Boolean): Voice? {
        val all = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val online = Net.online(ctx)
        if (!preferred.isNullOrBlank()) {
            all.firstOrNull { it.name == preferred }?.let { if (online || !it.isNetworkConnectionRequired) return it }
        }
        return voicesFor(all, lang).maxByOrNull { score(it, online, preferLocal) }?.takeIf { score(it, online, preferLocal) > -5000 }
    }

    override suspend fun speak(text: String, lang: Lang, s: AppSettings, device: AudioDeviceInfo?) {
        if (!ready.await()) throw IOException("Motor de voz do Android indisponível")
        val v = pick(lang, s.androidVoices[lang.code], s.fastVoice)
        if (v != null) tts.voice = v else tts.language = lang.locale
        tts.setSpeechRate(s.rate)
        tts.setPitch(s.pitch)
        val id = UUID.randomUUID().toString()
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, s.volume) }
        if (device != null) {
            // Synthesise to a file, then play it on the chosen output (headphones or loudspeaker).
            val f = File(ctx.cacheDir, "tts_$id.wav")
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    pending[id] = cont
                    cont.invokeOnCancellation {
                        pending.remove(id)
                        runCatching { tts.stop() }
                    }
                    val r = tts.synthesizeToFile(text, Bundle(), f, id)
                    if (r != TextToSpeech.SUCCESS) finish(id)
                }
                if (f.length() > 44) playFile(f, s.volume, device)
            } finally {
                f.delete()
            }
            return
        }
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

