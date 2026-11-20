package com.voxlink.ai.model

import java.util.Locale

enum class Lang(
    val code: String,
    val bcp47: String,
    val label: String,
    val flag: String,
    val locale: Locale,
    val webCode: String,
) {
    PT("pt", "pt-BR", "Português", "🇧🇷", Locale("pt", "BR"), "pt"),
    EN("en", "en-US", "English", "🇺🇸", Locale.US, "en"),
    ZH("zh", "zh-CN", "中文 · Mandarim", "🇨🇳", Locale.SIMPLIFIED_CHINESE, "zh-CN");

    val short: String get() = code.uppercase()

    /** Language codes used by Android voices / locales for this language. */
    val localeLanguages: Set<String>
        get() = when (this) {
            PT -> setOf("pt", "por")
            EN -> setOf("en", "eng")
            ZH -> setOf("zh", "cmn", "zho", "chi")
        }

    companion object {
        fun fromCode(c: String?): Lang? {
            if (c.isNullOrBlank()) return null
            val l = c.lowercase().replace('_', '-')
            return when {
                l.startsWith("pt") -> PT
                l.startsWith("en") -> EN
                l.startsWith("zh") || l.startsWith("cmn") -> ZH
                else -> null
            }
        }
    }
}

data class Entry(
    val id: Long,
    val original: String,
    val from: Lang,
    val translation: String,
    val to: Lang,
    val time: Long,
    val provider: String,
    val originalPinyin: String? = null,
    val translationPinyin: String? = null,
)

/** ANDROID = Google recognizer (online, turn-based language switching); WHISPER = offline, open source, automatic language. */
enum class RecognitionEngine { ANDROID, WHISPER }

/** Open-source Whisper models (whisper.cpp, MIT), downloaded once from Hugging Face. */
enum class WhisperModel(val file: String, val label: String, val sizeMb: Int) {
    BASE("ggml-base-q5_1.bin", "Base — rápido", 57),
    SMALL("ggml-small-q5_1.bin", "Small — mais preciso (mais lento)", 181),
}
enum class TranslationMode { ONLINE_THEN_OFFLINE, OFFLINE_ONLY }

data class AppSettings(
    val me: Lang = Lang.PT,
    val other: Lang = Lang.ZH,
    val autoSwitch: Boolean = true,
    val autoSpeak: Boolean = true,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val androidEngine: String = "",
    val androidVoices: Map<String, String> = emptyMap(),
    val translationMode: TranslationMode = TranslationMode.ONLINE_THEN_OFFLINE,
    val technicalMode: Boolean = true,
    val glossary: String = DEFAULT_GLOSSARY,
    val saveHistory: Boolean = false,
    val keepScreenOn: Boolean = true,
    val recognitionEngine: RecognitionEngine = RecognitionEngine.ANDROID,
    val showPinyin: Boolean = true,
    /** Silence (seconds) that marks the end of what a person is saying. */
    val endPause: Float = 1.5f,
    val whisperModel: WhisperModel = WhisperModel.BASE,
    /** With headphones connected: my language → headphones, the other person's language → loudspeaker. */
    val splitAudio: Boolean = true,
)

val SAMPLE_PHRASES = mapOf(
    Lang.PT to "A instalação do equipamento foi concluída. Começamos os testes amanhã.",
    Lang.EN to "The equipment installation is complete. We start testing tomorrow.",
    Lang.ZH to "设备安装已经完成，我们明天开始测试。",
)

const val DEFAULT_GLOSSARY = """# Glossário técnico — formato: português | english | 中文
linha de produção | production line | 生产线
posto de trabalho | workstation | 工位
tempo de ciclo | cycle time | 周期时间
takt time | takt time | 节拍时间
instrução de trabalho | work instruction | 作业指导书
gabarito | jig | 治具
parafusadeira | electric screwdriver | 电批
torque | torque | 扭力
calibração | calibration | 校准
manutenção preventiva | preventive maintenance | 预防性维护
não conformidade | nonconformity | 不合格
retrabalho | rework | 返工
esteira | conveyor | 输送线
bancada | workbench | 工作台
primeira peça | first article | 首件
inspeção | inspection | 检验
balanceamento de linha | line balancing | 产线平衡
"""
