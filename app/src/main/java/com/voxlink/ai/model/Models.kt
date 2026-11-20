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
    /** Glossaries by area (name → "pt | en | zh" lines); the active one is applied in technical mode. */
    val glossaries: Map<String, String> = DEFAULT_GLOSSARIES,
    val activeGlossary: String = "Geral",
    val saveHistory: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** Force offline mode (also automatic when there is no internet). */
    val forceOffline: Boolean = false,
    val showPinyin: Boolean = true,
    /** Name shown to the other phone in sync mode. */
    val deviceName: String = "",
    /** Silence (seconds) that marks the end of what a person is saying. */
    val endPause: Float = 0.9f,
    /** Speed first: on-device translation unless the online one arrives within ~0.7 s. */
    val speedMode: Boolean = true,
    /** On-device speech recognition (Android 12+): faster and works offline. */
    val onDeviceRecognition: Boolean = true,
    /** Prefer voices installed on the phone (start instantly) over online voices. */
    val fastVoice: Boolean = true,
    /** Show the translation live while the person is still talking. */
    val liveSubtitles: Boolean = true,
    /** Quick phrases (one per line, in my language) shown as buttons. */
    val quickPhrases: String = DEFAULT_QUICK_PHRASES,
    /** Keep the microphone open while the app speaks (only with headphones). Off = mic pauses while speaking. */
    val listenWhileSpeaking: Boolean = false,
    /** Lecture mode voice: EACH = speak after every pause, SILENT = subtitles only, AT_END = read everything at the end. */
    val lectureVoice: String = "EACH",
    /** With headphones connected: my language → headphones, the other person's language → loudspeaker. */
    val splitAudio: Boolean = true,
) {
    /** Text of the active glossary. */
    val glossary: String get() = glossaries[activeGlossary] ?: glossaries.values.firstOrNull() ?: ""
}

val SAMPLE_PHRASES = mapOf(
    Lang.PT to "A instalação do equipamento foi concluída. Começamos os testes amanhã.",
    Lang.EN to "The equipment installation is complete. We start testing tomorrow.",
    Lang.ZH to "设备安装已经完成，我们明天开始测试。",
)

const val DEFAULT_QUICK_PHRASES = """Pode repetir, por favor?
Fale mais devagar, por favor.
Entendi.
Um momento, por favor.
Onde está o problema?
Vamos testar agora.
Está aprovado.
Não está conforme.
Obrigado!"""

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

const val GLOSSARY_LEO = """# LEO — impressoras · formato: português | english | 中文
cabeçote de impressão | printhead | 打印头
cartucho de tinta | ink cartridge | 墨盒
bandeja de papel | paper tray | 纸盒
alimentação de papel | paper feed | 进纸
atolamento de papel | paper jam | 卡纸
carro de impressão | carriage | 字车
placa principal | main board | 主板
cabo flat | flat cable | 排线
correia | belt | 皮带
parafuso | screw | 螺丝
teste de impressão | print test | 打印测试
etiqueta | label | 标签
embalagem | packing | 包装
posto de trabalho | workstation | 工位
tempo de ciclo | cycle time | 周期时间
"""

const val GLOSSARY_INKJET = """# Inkjet · formato: português | english | 中文
tinta | ink | 墨水
bico injetor | nozzle | 喷嘴
cabeçote de impressão | printhead | 打印头
limpeza do cabeçote | head cleaning | 清洗打印头
alinhamento do cabeçote | printhead alignment | 打印头校准
cartucho de tinta | ink cartridge | 墨盒
atolamento de papel | paper jam | 卡纸
teste de impressão | print test | 打印测试
"""

const val GLOSSARY_QUALIDADE = """# Qualidade · formato: português | english | 中文
não conformidade | nonconformity | 不合格
inspeção de entrada | incoming inspection | 来料检验
inspeção final | final inspection | 终检
amostragem | sampling | 抽样
defeito | defect | 不良
taxa de defeitos | defect rate | 不良率
rendimento | yield | 良率
ação corretiva | corrective action | 纠正措施
análise de causa raiz | root cause analysis | 根本原因分析
segregação | quarantine | 隔离
auditoria | audit | 审核
primeira peça | first article | 首件
"""

const val GLOSSARY_MANUTENCAO = """# Manutenção · formato: português | english | 中文
manutenção preventiva | preventive maintenance | 预防性维护
manutenção corretiva | corrective maintenance | 故障维修
parada de máquina | machine downtime | 停机
peça de reposição | spare part | 备件
lubrificação | lubrication | 润滑
sensor | sensor | 传感器
cilindro pneumático | pneumatic cylinder | 气缸
válvula solenoide | solenoid valve | 电磁阀
painel elétrico | electrical cabinet | 电柜
aterramento | grounding | 接地
calibração | calibration | 校准
torque | torque | 扭力
"""

val DEFAULT_GLOSSARIES: Map<String, String> = linkedMapOf(
    "Geral" to DEFAULT_GLOSSARY,
    "LEO" to GLOSSARY_LEO,
    "Inkjet" to GLOSSARY_INKJET,
    "Qualidade" to GLOSSARY_QUALIDADE,
    "Manutenção" to GLOSSARY_MANUTENCAO,
)
