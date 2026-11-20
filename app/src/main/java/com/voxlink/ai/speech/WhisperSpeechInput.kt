package com.voxlink.ai.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.voxlink.ai.model.Lang
import com.voxlink.ai.model.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Native bridge to whisper.cpp (MIT licence). */
object WhisperLib {
    val loaded: Boolean = runCatching { System.loadLibrary("voxwhisper") }.isSuccess

    @JvmStatic external fun initContext(path: String): Long
    @JvmStatic external fun freeContext(ptr: Long)
    @JvmStatic external fun transcribe(ptr: Long, samples: FloatArray, threads: Int, langs: Array<String>): ByteArray
}

/** Downloads the open-source Whisper models once (Hugging Face, MIT licence). */
object WhisperModels {
    fun file(ctx: Context, m: WhisperModel) = File(ctx.filesDir, m.file)

    fun ready(ctx: Context, m: WhisperModel): Boolean = file(ctx, m).let { it.exists() && it.length() > 20_000_000L }

    suspend fun download(ctx: Context, m: WhisperModel, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val dest = file(ctx, m)
        val part = File(dest.path + ".part")
        var url = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${m.file}")
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "VoxLinkAI")
            val code = c.responseCode
            if (code in 300..399 && hops < 6) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect sem destino")
                url = URL(url, loc)
                c.disconnect()
                hops++
                continue
            }
            if (code != 200) throw IOException("HTTP $code")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: (m.sizeMb * 1_048_576L)
        try {
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                        if (pct != last) { last = pct; withContext(Dispatchers.Main) { progress(pct) } }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (part.length() < 20_000_000L) { part.delete(); throw IOException("Download incompleto") }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Falha ao salvar o modelo")
    }
}

/**
 * Offline recogniser: own microphone capture + energy VAD (waits for a real pause, configurable)
 * + Whisper with language identification limited to PT / EN / 中文. No internet, no cost.
 */
class WhisperSpeechInput(private val ctx: Context, private val cb: SpeechInput.Callbacks) : SpeechEngine {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var ctxPtr = 0L          // only touched on the worker thread
    private var loadedModel: WhisperModel? = null

    @Volatile var model: WhisperModel = WhisperModel.BASE
    @Volatile var silenceMs: Long = 1500L
    @Volatile var candidates: List<Lang> = Lang.entries

    val modelReady: Boolean get() = WhisperLib.loaded && WhisperModels.ready(ctx, model)

    override fun start(lang: Lang, alternatives: List<Lang>) {
        if (running) return
        if (!modelReady) {
            main.post { cb.onError(SpeechInput.WHISPER_NO_MODEL) }
            return
        }
        running = true
        worker.execute { ensureContext() } // warm up the model while the user starts talking
        thread = Thread({ captureLoop() }, "vox-mic").also { it.start() }
    }

    override fun cancel() {
        running = false
    }

    override fun destroy() {
        running = false
        worker.execute {
            if (ctxPtr != 0L) WhisperLib.freeContext(ctxPtr)
            ctxPtr = 0L
            loadedModel = null
        }
        worker.shutdown()
    }

    private fun ensureContext(): Boolean {
        val m = model
        if (ctxPtr != 0L && loadedModel == m) return true
        if (ctxPtr != 0L) WhisperLib.freeContext(ctxPtr)
        ctxPtr = runCatching { WhisperLib.initContext(WhisperModels.file(ctx, m).absolutePath) }.getOrDefault(0L)
        loadedModel = if (ctxPtr != 0L) m else null
        return ctxPtr != 0L
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, sr * 2)
            )
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            rec?.release()
            main.post { cb.onError(android.speech.SpeechRecognizer.ERROR_AUDIO) }
            return
        }
        rec.startRecording()
        main.post { cb.onReady() }

        val frameMs = 30
        val frame = ShortArray(sr * frameMs / 1000)
        val preRoll = ArrayDeque<ShortArray>()
        val speech = ArrayList<ShortArray>()
        var inSpeech = false
        var silentMs = 0
        var voicedMs = 0
        var noise = 250.0
        var lastLevel = 0L

        fun endSegment() {
            if (voicedMs >= 350) {
                val total = speech.sumOf { it.size }
                val pcm = FloatArray(total)
                var o = 0
                for (f in speech) for (v in f) pcm[o++] = v / 32768f
                main.post { cb.onPartial("⌛ transcrevendo…") }
                worker.execute { transcribe(pcm) }
            }
            speech.clear()
            inSpeech = false
            silentMs = 0
            voicedMs = 0
        }

        try {
            while (running) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                val f = frame.copyOf(n)
                var sum = 0.0
                for (v in f) sum += v.toDouble() * v
                val rms = sqrt(sum / n)
                if (!inSpeech) noise = noise * 0.97 + min(rms, 3000.0) * 0.03
                val threshold = max(noise * 2.6, 420.0)

                val now = SystemClock.uptimeMillis()
                if (now - lastLevel > 80) {
                    lastLevel = now
                    val level = (rms / (threshold * 3)).coerceIn(0.0, 1.0).toFloat()
                    main.post { cb.onRms(level * 12f - 2f) }
                }

                if (rms > threshold) {
                    if (!inSpeech) {
                        inSpeech = true
                        speech.addAll(preRoll)
                        preRoll.clear()
                        main.post { cb.onSpeechStart() }
                    }
                    silentMs = 0
                    voicedMs += frameMs
                    speech.add(f)
                } else if (inSpeech) {
                    speech.add(f)
                    silentMs += frameMs
                    if (silentMs >= silenceMs) endSegment()
                } else {
                    preRoll.addLast(f)
                    if (preRoll.size > 12) preRoll.removeFirst()
                }
                if (inSpeech && speech.size * frameMs >= 28_000) endSegment() // Whisper window is 30 s
            }
            if (inSpeech) endSegment()
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun transcribe(pcm: FloatArray) {
        if (!ensureContext()) {
            main.post { cb.onError(SpeechInput.WHISPER_ERROR) }
            return
        }
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
        val langs = candidates.map { it.code }.toTypedArray()
        val raw = runCatching { String(WhisperLib.transcribe(ctxPtr, pcm, threads, langs), Charsets.UTF_8) }.getOrDefault("")
        val nl = raw.indexOf('\n')
        val lang = if (nl > 0) raw.substring(0, nl) else null
        val text = clean(if (nl >= 0) raw.substring(nl + 1) else "")
        main.post {
            if (text.isEmpty()) cb.onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH) else cb.onFinal(text, lang)
        }
    }

    private fun clean(t: String): String {
        var s = t.replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()
        // Typical Whisper hallucinations on silence/noise
        val junk = listOf(
            "legendas pela comunidade amara.org", "obrigado por assistir", "inscreva-se", "thank you for watching",
            "thanks for watching", "字幕", "请不吝点赞", "订阅", "amara.org",
        )
        if (junk.any { s.lowercase().contains(it) }) s = ""
        if (s.length <= 1) s = ""
        return s
    }
}
