package com.voxlink.ai.model

import android.os.Build

/** Chinese → pinyin with tone marks (ICU "Han-Latin", built into Android 10+). */
object Pinyin {
    private val transliterator: android.icu.text.Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { android.icu.text.Transliterator.getInstance("Han-Latin") }.getOrNull()
        } else null
    }

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    fun hasHan(text: String): Boolean = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    /** Returns pinyin for texts containing Chinese characters, or null. */
    fun of(text: String): String? {
        if (text.isBlank() || !hasHan(text)) return null
        synchronized(cache) { cache[text]?.let { return it } }
        val t = transliterator ?: return null
        val out = runCatching { t.transliterate(text) }.getOrNull() ?: return null
        val clean = out
            .replace('，', ',').replace('。', '.').replace('？', '?').replace('！', '!')
            .replace('：', ':').replace('；', ';').replace('、', ',')
            .replace(Regex("\\s+([,.?!:;])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
        synchronized(cache) { cache[text] = clean }
        return clean
    }
}
