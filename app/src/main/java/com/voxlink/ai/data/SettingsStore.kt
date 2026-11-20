package com.voxlink.ai.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.voxlink.ai.model.AppSettings
import com.voxlink.ai.model.Entry
import com.voxlink.ai.model.Lang
import com.voxlink.ai.model.RecognitionEngine
import com.voxlink.ai.model.TranslationMode
import com.voxlink.ai.model.VoiceProvider
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
            voiceProvider = runCatching { VoiceProvider.valueOf(p.getString("voiceProvider", null)!!) }.getOrDefault(d.voiceProvider),
            androidEngine = p.getString("androidEngine", d.androidEngine) ?: "",
            androidVoices = (p.getString("androidVoices", "") ?: "").split(";")
                .mapNotNull { s -> s.split("=", limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() }?.let { it[0] to it[1] } }
                .toMap(),
            azureKey = p.getString("azureKey", d.azureKey) ?: "",
            azureRegion = p.getString("azureRegion", d.azureRegion) ?: d.azureRegion,
            azureFemale = p.getBoolean("azureFemale", d.azureFemale),
            translationMode = runCatching { TranslationMode.valueOf(p.getString("translationMode", null)!!) }.getOrDefault(d.translationMode),
            technicalMode = p.getBoolean("technicalMode", d.technicalMode),
            glossary = p.getString("glossary", d.glossary) ?: d.glossary,
            saveHistory = p.getBoolean("saveHistory", d.saveHistory),
            keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn),
            recognitionEngine = runCatching { RecognitionEngine.valueOf(p.getString("recognitionEngine", null)!!) }.getOrDefault(d.recognitionEngine),
            showPinyin = p.getBoolean("showPinyin", d.showPinyin),
            endPause = p.getFloat("endPause", d.endPause),
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
            .putString("voiceProvider", s.voiceProvider.name)
            .putString("androidEngine", s.androidEngine)
            .putString("androidVoices", s.androidVoices.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putString("azureKey", s.azureKey)
            .putString("azureRegion", s.azureRegion)
            .putBoolean("azureFemale", s.azureFemale)
            .putString("translationMode", s.translationMode.name)
            .putBoolean("technicalMode", s.technicalMode)
            .putString("glossary", s.glossary)
            .putBoolean("saveHistory", s.saveHistory)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putString("recognitionEngine", s.recognitionEngine.name)
            .putBoolean("showPinyin", s.showPinyin)
            .putFloat("endPause", s.endPause)
            .apply()
    }

    fun saveHistory(items: List<Entry>) {
        val arr = JSONArray()
        items.take(300).forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id).put("o", e.original).put("f", e.from.code)
                    .put("t", e.translation).put("to", e.to.code).put("ts", e.time).put("p", e.provider)
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
