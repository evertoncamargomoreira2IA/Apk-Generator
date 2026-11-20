package com.voxlink.ai.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.voxlink.ai.model.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Provider abstraction: any translation backend can be plugged in here. */
interface TranslationProvider {
    val name: String
    suspend fun translate(text: String, from: Lang, to: Lang): String
}

/** Translation plus romanisation (pinyin) of source and target, when available. */
data class Translated(val text: String, val sourceRoman: String? = null, val targetRoman: String? = null)

/** Online neural translation (Google web endpoint, no key). Best quality for conversation + pinyin. */
class OnlineTranslator : TranslationProvider {
    override val name = "Online"

    override suspend fun translate(text: String, from: Lang, to: Lang): String = translateFull(text, from, to).text

    suspend fun translateFull(text: String, from: Lang, to: Lang): Translated = withContext(Dispatchers.IO) {
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${from.webCode}&tl=${to.webCode}&dt=t&dt=rm&q=" +
                URLEncoder.encode(text, "UTF-8")
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) VoxLink")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parts = JSONArray(body).getJSONArray(0)
            val out = StringBuilder()
            var srcRoman: String? = null
            var dstRoman: String? = null
            for (i in 0 until parts.length()) {
                val p = parts.optJSONArray(i) ?: continue
                if (!p.isNull(0)) {
                    out.append(p.optString(0, ""))
                } else {
                    // romanisation row: [null, null, targetRoman, sourceRoman]
                    if (p.length() > 2 && !p.isNull(2)) dstRoman = p.optString(2).takeIf { it.isNotBlank() }
                    if (p.length() > 3 && !p.isNull(3)) srcRoman = p.optString(3).takeIf { it.isNotBlank() }
                }
            }
            val result = out.toString().trim()
            if (result.isEmpty()) throw IOException("Resposta vazia")
            Translated(
                result,
                sourceRoman = if (from == Lang.ZH) srcRoman else null,
                targetRoman = if (to == Lang.ZH) dstRoman else null,
            )
        } finally {
            c.disconnect()
        }
    }

    /** Pinyin only (used for Chinese text that was not translated online). */
    suspend fun pinyin(text: String): String? = runCatching {
        translateFull(text, Lang.ZH, Lang.EN).sourceRoman
    }.getOrNull()
}

/** On-device translation (Google ML Kit). Works offline after the language packs are downloaded. */
class OfflineTranslator : TranslationProvider {
    override val name = "Offline"
    private val clients = HashMap<String, Translator>()

    private fun mk(l: Lang) = when (l) {
        Lang.PT -> TranslateLanguage.PORTUGUESE
        Lang.EN -> TranslateLanguage.ENGLISH
        Lang.ZH -> TranslateLanguage.CHINESE
    }

    private fun client(from: Lang, to: Lang): Translator = synchronized(clients) {
        clients.getOrPut("${from.code}-${to.code}") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(mk(from)).setTargetLanguage(mk(to)).build()
            )
        }
    }

    suspend fun download(from: Lang, to: Lang) {
        client(from, to).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        downloadedAt = 0L
    }

    @Volatile private var downloaded: Set<String> = emptySet()
    @Volatile private var downloadedAt = 0L

    /** True when both language packs are on the phone (translation then takes ~0.1 s, no internet). */
    suspend fun isReady(from: Lang, to: Lang): Boolean {
        if (System.currentTimeMillis() - downloadedAt > 30_000) {
            downloaded = runCatching {
                RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
                    .map { it.language }.toSet()
            }.getOrDefault(downloaded)
            downloadedAt = System.currentTimeMillis()
        }
        fun ok(l: Lang) = l == Lang.EN || mk(l) in downloaded
        return ok(from) && ok(to)
    }

    override suspend fun translate(text: String, from: Lang, to: Lang): String {
        val c = client(from, to)
        c.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return c.translate(text).await()
    }

    fun close() = synchronized(clients) { clients.values.forEach { it.close() }; clients.clear() }
}

/** Combines the speech recognizer's hint, script analysis and ML Kit language ID. */
class LanguageDetector {
    private val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.3f).build()
    )

    suspend fun detect(text: String, heard: Lang, recognizerDetected: Lang?, pair: Set<Lang>): Lang {
        val letters = text.count { it.isLetter() }.coerceAtLeast(1)
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        if (han > 0 && han * 3 >= letters) return Lang.ZH
        if (recognizerDetected != null && recognizerDetected != Lang.ZH) return recognizerDetected

        val words = text.trim().split(Regex("\\s+")).size
        if (heard != Lang.ZH && words < 3) return heard

        val ml = runCatching { client.identifyPossibleLanguages(text).await() }.getOrDefault(emptyList())
        val best = ml.mapNotNull { il -> Lang.fromCode(il.languageTag)?.let { it to il.confidence } }
            .filter { it.first != Lang.ZH }
            .maxByOrNull { it.second }

        if (heard != Lang.ZH) {
            if (best != null && best.first != heard && best.second >= 0.85f && (best.first in pair || heard !in pair)) {
                return best.first
            }
            return heard
        }
        return best?.first ?: heuristic(text)
    }

    private fun heuristic(text: String): Lang {
        val t = text.lowercase()
        if (Regex("[ãõáâàéêíóôúç]").containsMatchIn(t)) return Lang.PT
        val pt = Regex("\\b(não|você|está|para|com|que|uma|precisamos|máquina|linha|vamos|isso)\\b").findAll(t).count()
        val en = Regex("\\b(the|this|that|we|you|need|have|is|are|will|please|machine|line)\\b").findAll(t).count()
        return if (pt >= en) Lang.PT else Lang.EN
    }
}

/** Customisable technical glossary: terms keep their intended technical meaning. */
class Glossary(raw: String) {
    private val rows: List<Map<Lang, String>> = raw.lines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
        val p = t.split("|").map { it.trim() }
        if (p.size < 3) return@mapNotNull null
        mapOf(Lang.PT to p[0], Lang.EN to p[1], Lang.ZH to p[2]).filterValues { it.isNotEmpty() }
    }

    val size: Int get() = rows.size

    fun apply(text: String, from: Lang, to: Lang): String {
        var out = text
        rows.sortedByDescending { it[from]?.length ?: 0 }.forEach { row ->
            val s = row[from] ?: return@forEach
            val d = row[to] ?: return@forEach
            if (s.equals(d, ignoreCase = true)) return@forEach
            out = if (from == Lang.ZH) {
                out.replace(s, " $d ")
            } else {
                Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(s) + "(?![\\p{L}\\p{N}])").replace(out) { d }
            }
        }
        return out.replace(Regex("\\s{2,}"), " ").trim()
    }
}
