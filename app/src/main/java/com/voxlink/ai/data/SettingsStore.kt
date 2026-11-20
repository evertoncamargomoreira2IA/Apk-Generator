package com.voxlink.ai.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.voxlink.ai.model.AppSettings
import com.voxlink.ai.model.Entry
import com.voxlink.ai.model.Lang
import com.voxlink.ai.model.TranslationMode
import org.json.JSONArray
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("voxlink", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            me = Lang.fromCode(p.getString("me", null)) ?: d.me,
            other = Lang.fromCode(p.getString("other", null)) ?: d.other,
            autoSwitch = p.getBoolean("autoSwitch", d.autoSwitch),
            autoSpeak = p.getBoolean("autoSpeak", d.autoSpeak),
            rate = p.getFloat("rate", d.rate),
            pitch = p.getFloat("pitch", d.pitch),
            volume = p.getFloat("volume", d.volume),
            androidEngine = p.getString("androidEngine", d.androidEngine) ?: "",
            androidVoices = (p.getString("androidVoices", "") ?: "").split(";")
                .mapNotNull { s -> s.split("=", limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() }?.let { it[0] to it[1] } }
                .toMap(),
            translationMode = runCatching { TranslationMode.valueOf(p.getString("translationMode", null)!!) }.getOrDefault(d.translationMode),
            technicalMode = p.getBoolean("technicalMode", d.technicalMode),
            glossary = p.getString("glossary", d.glossary) ?: d.glossary,
            saveHistory = p.getBoolean("saveHistory", d.saveHistory),
            keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn),
            forceOffline = p.getBoolean("forceOffline", d.forceOffline),
            deviceName = p.getString("deviceName", d.deviceName) ?: "",
            showPinyin = p.getBoolean("showPinyin", d.showPinyin),
            endPause = p.getFloat("endPause2", d.endPause),
            speedMode = p.getBoolean("speedMode", d.speedMode),
            onDeviceRecognition = p.getBoolean("onDeviceRecognition", d.onDeviceRecognition),
            fastVoice = p.getBoolean("fastVoice", d.fastVoice),
            liveSubtitles = p.getBoolean("liveSubtitles", d.liveSubtitles),
            splitAudio = p.getBoolean("splitAudio", d.splitAudio),
        )
    }

    fun save(s: AppSettings) {
        p.edit()
            .putString("me", s.me.code)
            .putString("other", s.other.code)
            .putBoolean("autoSwitch", s.autoSwitch)
            .putBoolean("autoSpeak", s.autoSpeak)
            .putFloat("rate", s.rate)
            .putFloat("pitch", s.pitch)
            .putFloat("volume", s.volume)
            .putString("androidEngine", s.androidEngine)
            .putString("androidVoices", s.androidVoices.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putString("translationMode", s.translationMode.name)
            .putBoolean("technicalMode", s.technicalMode)
            .putString("glossary", s.glossary)
            .putBoolean("saveHistory", s.saveHistory)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("forceOffline", s.forceOffline)
            .putString("deviceName", s.deviceName)
            .putBoolean("showPinyin", s.showPinyin)
            .putFloat("endPause2", s.endPause)
            .putBoolean("speedMode", s.speedMode)
            .putBoolean("onDeviceRecognition", s.onDeviceRecognition)
            .putBoolean("fastVoice", s.fastVoice)
            .putBoolean("liveSubtitles", s.liveSubtitles)
            .putBoolean("splitAudio", s.splitAudio)
            .apply()
    }

    fun saveHistory(items: List<Entry>) {
        val arr = JSONArray()
        items.take(300).forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id).put("o", e.original).put("f", e.from.code)
                    .put("t", e.translation).put("to", e.to.code).put("ts", e.time).put("p", e.provider)
                    .put("op", e.originalPinyin ?: "").put("tp", e.translationPinyin ?: "")
            )
        }
        p.edit().putString("history", arr.toString()).apply()
    }

    fun loadHistory(): List<Entry> = runCatching {
        val arr = JSONArray(p.getString("history", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            Entry(
                id = o.getLong("id"), original = o.getString("o"),
                from = Lang.fromCode(o.getString("f")) ?: return@mapNotNull null,
                translation = o.getString("t"),
                to = Lang.fromCode(o.getString("to")) ?: return@mapNotNull null,
                time = o.getLong("ts"), provider = o.optString("p", ""),
                originalPinyin = o.optString("op", "").ifBlank { null },
                translationPinyin = o.optString("tp", "").ifBlank { null },
            )
        }
    }.getOrDefault(emptyList())

    fun clearHistory() { p.edit().remove("history").apply() }
}

object Net {
    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
