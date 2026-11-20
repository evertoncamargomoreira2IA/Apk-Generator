package com.voxlink.ai.model

import android.os.Build
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * Chinese → pinyin with tone marks.
 * Best quality comes from the online translator (word-segmented, correct polyphones) and is stored
 * with each translation. Offline fallback: ICU "Han-Latin" (Android 10+) or the bundled pinyin4j.
 */
object Pinyin {
    private val icu: android.icu.text.Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { android.icu.text.Transliterator.getInstance("Han-Latin") }.getOrNull()
        } else null
    }

    private val format by lazy {
        HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.LOWERCASE
            toneType = HanyuPinyinToneType.WITH_TONE_MARK
            vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
        }
    }

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }

    fun isHan(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    fun hasHan(text: String): Boolean = text.any { isHan(it) }

    /** Offline pinyin for texts containing Chinese characters, or null. */
    fun of(text: String): String? {
        if (text.isBlank() || !hasHan(text)) return null
        synchronized(cache) { cache[text]?.let { return it } }
        val viaIcu = icu?.let { t -> runCatching { t.transliterate(text) }.getOrNull() }
        val raw = if (viaIcu != null && !hasHan(viaIcu)) viaIcu else viaPinyin4j(text)
        val clean = tidy(raw)
        synchronized(cache) { cache[text] = clean }
        return clean
    }

    private fun viaPinyin4j(text: String): String = buildString {
        var prevHan = false
        for (c in text) {
            if (isHan(c)) {
                val py = runCatching { PinyinHelper.toHanyuPinyinStringArray(c, format) }.getOrNull()?.firstOrNull()
                if (isNotEmpty() && !endsWith(" ")) append(' ')
                append(py ?: c.toString())
                prevHan = true
            } else {
                if (prevHan && c.isLetterOrDigit()) append(' ')
                append(c)
                prevHan = false
            }
        }
    }

    fun tidy(s: String): String = s
        .replace('，', ',').replace('。', '.').replace('？', '?').replace('！', '!')
        .replace('：', ':').replace('；', ';').replace('、', ',')
        .replace(Regex("\\s+([,.?!:;])"), "$1")
        .replace(Regex("([,.?!:;])(?=\\S)"), "$1 ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
