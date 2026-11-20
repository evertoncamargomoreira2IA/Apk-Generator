package com.voxlink.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxlink.ai.MainViewModel
import com.voxlink.ai.model.Lang
import com.voxlink.ai.model.Pinyin
import com.voxlink.ai.model.RecognitionEngine
import com.voxlink.ai.model.TranslationMode
import com.voxlink.ai.model.VoiceProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Bg = Color(0xFF06101E)
private val Panel = Color(0xFF0C1B2D)
private val Cyan = Color(0xFF32D9FF)
private val Blue = Color(0xFF1788FF)
private val Green = Color(0xFF35E6A6)
private val Muted = Color(0xFF8BA6C2)
private val White = Color(0xFFEFF7FF)
private val Line = Color(0xFF164A76)

@Composable
fun VoxApp(vm: MainViewModel, onMic: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var showSettings by remember { mutableStateOf(false) }
    var faceToFace by remember { mutableStateOf(false) }

    SideEffect { view.keepScreenOn = vm.sessionActive && vm.settings.keepScreenOn }
    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toast = null }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan, secondary = Green, background = Bg, surface = Panel,
            onPrimary = Bg, onBackground = White, onSurface = White,
        )
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D3154), Bg, Color(0xFF020813))))
                .systemBarsPadding()
                .imePadding()
        ) {
            when {
                showSettings -> {
                    BackHandler { showSettings = false }
                    SettingsScreen(vm) { showSettings = false }
                }
                faceToFace -> {
                    BackHandler { faceToFace = false }
                    FaceToFaceScreen(vm, onMic) { faceToFace = false }
                }
                else -> MainScreen(vm, onMic, openSettings = { showSettings = true }, openFaceToFace = { faceToFace = true })
            }
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel, onMic: () -> Unit, openSettings: () -> Unit, openFaceToFace: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).border(2.dp, Cyan, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("◎", color = Cyan, fontSize = 22.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text("VoxLink ", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = White)
                        Text("AI", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Cyan)
                    }
                    Text("INTÉRPRETE DE VOZ AO VIVO", fontSize = 11.sp, color = Muted, letterSpacing = 1.sp)
                }
                OutlinedButton(onClick = openFaceToFace, border = BorderStroke(1.dp, Line)) { Text("⇅", fontSize = 20.sp, color = White) }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = openSettings, border = BorderStroke(1.dp, Line)) { Text("⚙", fontSize = 20.sp, color = White) }
            }
        }

        // Language pair
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangPicker("VOCÊ", s.me, Modifier.weight(1f)) { l -> vm.update { if (l == it.other) it.copy(me = l, other = it.me) else it.copy(me = l) } }
                TextButton(onClick = { vm.swapPair() }) { Text("⇄", color = Cyan, fontSize = 22.sp) }
                LangPicker("OUTRA PESSOA", s.other, Modifier.weight(1f)) { l -> vm.update { if (l == it.me) it.copy(other = l, me = it.other) else it.copy(other = l) } }
            }
        }

        // Who is speaking now
        item {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ouvindo:", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                if (vm.autoDetect) {
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Green.copy(alpha = .15f))
                            .border(1.dp, Green, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("🌐 Idioma automático · PT · EN · 中文", color = Green, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                } else listOf(s.me, s.other).forEach { l ->
                    val sel = vm.listenLang == l
                    Box(
                        Modifier.padding(end = 8.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (sel) Cyan.copy(alpha = .18f) else Color.Transparent)
                            .border(1.dp, if (sel) Cyan else Line, RoundedCornerShape(14.dp))
                            .clickable { vm.listenFor(l) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("${l.flag} ${l.short}", color = if (sel) Cyan else White, fontWeight = FontWeight.SemiBold) }
                }
                Spacer(Modifier.weight(1f))
                if (!vm.autoDetect) {
                    Text("Alternar", color = Muted, fontSize = 12.sp)
                    Switch(checked = s.autoSwitch, onCheckedChange = { v -> vm.update { it.copy(autoSwitch = v) } })
                }
            }
        }

        item { Waveform(active = vm.listening || vm.speaking, level = if (vm.speaking) .7f else vm.rms, speaking = vm.speaking) }

        // Original speech
        item {
            val live = listOf(vm.pendingText, vm.partial).filter { it.isNotBlank() }.joinToString(" ")
            Card(border = Blue, onClick = { vm.flushNow() }) {
                val det = vm.lastFrom
                Text(
                    "◉ FALA ORIGINAL" + if (det != null && live.isEmpty()) " · ${det.flag} ${det.label} detectado" else "",
                    color = Color(0xFF55D3FF), fontSize = 11.sp, letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                when {
                    live.isNotEmpty() -> {
                        Text(live, color = Color(0xFFB0C6DE), fontSize = 18.sp, lineHeight = 25.sp, fontStyle = FontStyle.Italic)
                        PinyinLine(live, s.showPinyin)
                        if (vm.pendingText.isNotBlank()) {
                            Text("⏳ Aguardando terminar de falar… (toque aqui para traduzir já)", color = Cyan, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    vm.lastOriginal.isNotEmpty() -> {
                        Text(vm.lastOriginal, color = Color(0xFFC8D9EE), fontSize = 18.sp, lineHeight = 25.sp)
                        PinyinLine(vm.lastOriginal, s.showPinyin, known = vm.lastOriginalPinyin)
                    }
                    else -> Text("Toque no microfone e comece a falar…", color = Color(0xFF718BA8), fontSize = 16.sp)
                }
            }
        }

        // Translation (visual priority)
        item {
            Card(border = Color(0xFF10A987)) {
                val to = vm.lastTo
                Text("✦ TRADUÇÃO" + if (to != null) " → ${to.flag} ${to.label}" else "", color = Green, fontSize = 11.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (vm.lastTranslation.isEmpty()) {
                            Text(if (vm.translating) "Traduzindo…" else "A tradução aparece aqui…", color = Color(0xFF718BA8), fontSize = 16.sp)
                        } else {
                            Column {
                                Text(vm.lastTranslation, color = White, fontSize = 27.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
                                PinyinLine(vm.lastTranslation, s.showPinyin, big = true, known = vm.lastTranslationPinyin)
                            }
                        }
                    }
                    OutlinedButton(onClick = { vm.replay() }, shape = CircleShape, border = BorderStroke(1.dp, Color(0xFF177A63))) {
                        Text("🔊", fontSize = 18.sp)
                    }
                }
                if (vm.lastProvider.isNotEmpty()) {
                    val voice = if (s.voiceProvider == VoiceProvider.AZURE && s.azureKey.isNotBlank()) "Azure Neural" else "voz neural do Android"
                    Text("Tradução ${vm.lastProvider} · $voice" + if (s.technicalMode) " · modo técnico" else "", color = Muted, fontSize = 12.sp)
                }
            }
        }

        // Status
        item {
            val active = vm.listening || vm.speaking || vm.translating
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) Green else Muted))
                Spacer(Modifier.width(8.dp))
                Text(vm.status, color = if (active) Green else Muted, fontSize = 13.sp)
            }
        }

        // Controls
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SmallControl("↻", "Repetir") { vm.replay() }
                SmallControl(if (vm.paused) "▶" else "⏸", if (vm.paused) "Retomar" else "Pausar") {
                    if (vm.sessionActive) vm.togglePause() else onMic()
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(100.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(if (vm.sessionActive) Color(0xFF0B6B5A) else Color(0xFF0B3D74), Color(0xFF071629))))
                            .border(3.dp, if (vm.sessionActive) Green else Cyan, CircleShape)
                            .clickable { onMic() },
                        contentAlignment = Alignment.Center
                    ) { Text(if (vm.sessionActive) "■" else "🎙", fontSize = 34.sp, color = White) }
                    Text(if (vm.sessionActive) "Encerrar" else "Iniciar", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                }
                SmallControl(if (vm.muted) "🔇" else "🔈", if (vm.muted) "Mudo" else "Som") { vm.toggleMute() }
                SmallControl("⏹", "Parar voz") { vm.stopPlayback() }
            }
        }

        // History
        item {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Histórico da conversa (${vm.history.size})", color = Cyan, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (vm.history.isNotEmpty()) {
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("VoxLink", vm.exportText()))
                        vm.toast = "Conversa copiada"
                    }) { Text("Copiar", color = Muted) }
                    TextButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, vm.exportText())
                        }, "Exportar conversa"))
                    }) { Text("Exportar", color = Muted) }
                    TextButton(onClick = { vm.clearHistory() }) { Text("Apagar", color = Color(0xFFFF7A7A)) }
                }
            }
        }
        itemsIndexed(vm.history, key = { _, e -> e.id }) { i, e ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (i == 0) Color(0xFF0B2A33) else Color(0xFF08172A))
                    .border(1.dp, if (i == 0) Green.copy(alpha = .6f) else Color(0xFF17304A), RoundedCornerShape(12.dp))
                    .clickable { vm.replayEntry(e) }
                    .padding(12.dp)
            ) {
                Text("${e.from.flag} ${e.original}", color = Color(0xFFC8D9EE), fontSize = 13.sp)
                PinyinLine(e.original, s.showPinyin, small = true, known = e.originalPinyin)
                Text("${e.to.flag} ${e.translation}", color = Green, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                PinyinLine(e.translation, s.showPinyin, small = true, known = e.translationPinyin)
            }
        }
        item {
            Text(
                "Microfone ativo somente enquanto a sessão está iniciada (indicador verde). A conversa não é gravada; " +
                    "o histórico só é salvo se você autorizar nas configurações. Toque num item do histórico para ouvir de novo.",
                color = Color(0xFF809AB6), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun Card(border: Color, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(Brush.linearGradient(listOf(Color(0xEE0D1D31), Color(0xFF061323))))
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .heightIn(min = 90.dp)
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun SmallControl(icon: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF0A1A2E))
                .border(1.dp, Color(0xFF15578A), CircleShape).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 20.sp, color = Color(0xFFC7DEF8)) }
        Text(label, color = Color(0xFFA8C8E8), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LangPicker(title: String, value: Lang, modifier: Modifier, onPick: (Lang) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF10243B), Color(0xFF071526))))
                .border(1.dp, Color(0xFF176098), RoundedCornerShape(17.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(title, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text("${value.flag} ${value.label} ▾", color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Lang.entries.forEach { l ->
                DropdownMenuItem(text = { Text("${l.flag} ${l.label}") }, onClick = { open = false; onPick(l) })
            }
        }
    }
}

@Composable
private fun Waveform(active: Boolean, level: Float, speaking: Boolean) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(Modifier.fillMaxWidth().height(70.dp).padding(vertical = 6.dp)) {
        val n = 56
        val gap = size.width / n
        val bw = gap * 0.45f
        val amp = if (active) (0.35f + level * 0.9f).coerceAtMost(1.2f) else 0.25f
        for (i in 0 until n) {
            val base = 8f + abs(sin(i * .46f) * 34f + cos(i * .19f) * 20f)
            val wob = if (active) 0.55f + 0.45f * sin(phase + i * 0.35f) else 1f
            val h = (base * amp * wob).coerceIn(4f, size.height)
            val x = i * gap + (gap - bw) / 2
            val top = (size.height - h) / 2
            drawRoundRect(
                brush = Brush.verticalGradient(if (speaking) listOf(Green, Cyan) else listOf(Cyan, Blue), startY = top, endY = top + h),
                topLeft = Offset(x, top),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2, bw / 2),
                alpha = if (active) 1f else .55f,
            )
        }
    }
}

@Composable
private fun PinyinLine(text: String, enabled: Boolean, big: Boolean = false, small: Boolean = false, known: String? = null) {
    if (!enabled) return
    val py = known ?: remember(text) { Pinyin.of(text) } ?: return
    Text(
        py,
        color = Color(0xFFFFC870),
        fontSize = if (big) 17.sp else if (small) 12.sp else 14.sp,
        lineHeight = if (big) 23.sp else 18.sp,
        fontStyle = FontStyle.Italic,
        modifier = Modifier.padding(top = 3.dp),
    )
}

/**
 * Face-to-face mode: phone flat on the table between the two people. The top half is rotated
 * for the person in front of you and always shows the text in THEIR language; the bottom half
 * shows yours. In turn mode, each person taps their own half before speaking.
 */
@Composable
private fun FaceToFaceScreen(vm: MainViewModel, onMic: () -> Unit, close: () -> Unit) {
    val s = vm.settings
    fun pinyinFor(l: Lang): String? = when {
        vm.lastTo == l -> vm.lastTranslationPinyin
        vm.lastFrom == l -> vm.lastOriginalPinyin
        else -> null
    }
    fun textFor(l: Lang): String = when {
        vm.lastTo == l -> vm.lastTranslation
        vm.lastFrom == l -> vm.lastOriginal
        else -> ""
    }
    val live = listOf(vm.pendingText, vm.partial).filter { it.isNotBlank() }.joinToString(" ")
    Column(Modifier.fillMaxSize()) {
        FacePanel(
            lang = s.other, text = textFor(s.other), pinyin = pinyinFor(s.other), live = if (vm.lastFrom == s.other || vm.listenLang == s.other) live else "",
            active = !vm.autoDetect && vm.listenLang == s.other && vm.listening, showPinyin = s.showPinyin,
            modifier = Modifier.weight(1f).rotate(180f),
        ) { if (!vm.autoDetect) vm.listenFor(s.other) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = close) { Text("✕ Sair", color = Muted) }
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(if (vm.sessionActive) Color(0xFF0B6B5A) else Color(0xFF0B3D74))
                    .border(2.dp, if (vm.sessionActive) Green else Cyan, CircleShape)
                    .clickable { onMic() },
                contentAlignment = Alignment.Center,
            ) { Text(if (vm.sessionActive) "■" else "🎙", fontSize = 24.sp, color = White) }
            TextButton(onClick = { vm.replay() }) { Text("🔊 Repetir", color = Muted) }
        }
        Text(vm.status, color = if (vm.listening || vm.speaking) Green else Muted, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        FacePanel(
            lang = s.me, text = textFor(s.me), pinyin = pinyinFor(s.me), live = if (vm.lastFrom == s.me || vm.listenLang == s.me) live else "",
            active = !vm.autoDetect && vm.listenLang == s.me && vm.listening, showPinyin = s.showPinyin,
            modifier = Modifier.weight(1f),
        ) { if (!vm.autoDetect) vm.listenFor(s.me) }
    }
}

@Composable
private fun FacePanel(
    lang: Lang, text: String, pinyin: String?, live: String, active: Boolean, showPinyin: Boolean,
    modifier: Modifier, onTap: () -> Unit,
) {
    Column(
        modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xEE0D1D31), Color(0xFF061323))))
            .border(if (active) 2.dp else 1.dp, if (active) Green else Line, RoundedCornerShape(22.dp))
            .clickable { onTap() }
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
    ) {
        Text("${lang.flag} ${lang.label}" + if (active) "  ● ouvindo" else "", color = if (active) Green else Cyan, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (live.isNotBlank()) {
            Text(live, color = Muted, fontSize = 20.sp, fontStyle = FontStyle.Italic)
        } else if (text.isNotBlank()) {
            Text(text, color = White, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
            PinyinLine(text, showPinyin, big = true, known = pinyin)
        } else {
            Text(SAMPLE_HINT.getValue(lang), color = Color(0xFF718BA8), fontSize = 18.sp)
        }
    }
}

private val SAMPLE_HINT = mapOf(
    Lang.PT to "Toque aqui e fale em português",
    Lang.EN to "Tap here and speak English",
    Lang.ZH to "点这里，然后说中文",
)

// ======================= Settings =======================

@Composable
private fun SettingsScreen(vm: MainViewModel, close: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings
    var glossaryText by remember { mutableStateOf(s.glossary) }
    var azureKey by remember { mutableStateOf(s.azureKey) }
    var azureRegion by remember { mutableStateOf(s.azureRegion) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Configurações", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = White, modifier = Modifier.weight(1f))
            Button(onClick = {
                vm.update { it.copy(glossary = glossaryText, azureKey = azureKey.trim(), azureRegion = azureRegion.trim()) }
                close()
            }) { Text("Concluir") }
        }

        Section("Reconhecimento de fala")
        RadioRow("Android/Google (grátis) — idioma por turnos", s.recognitionEngine == RecognitionEngine.ANDROID) {
            vm.update { it.copy(recognitionEngine = RecognitionEngine.ANDROID) }
        }
        RadioRow("Azure — detecção automática do idioma", s.recognitionEngine == RecognitionEngine.AZURE) {
            vm.update { it.copy(recognitionEngine = RecognitionEngine.AZURE) }
        }
        Hint(
            if (s.recognitionEngine == RecognitionEngine.AZURE)
                "Cada frase tem o idioma identificado sozinho (PT · EN · 中文), sem precisar tocar em nada. Usa a mesma chave Azure Speech da voz premium (abaixo)."
            else
                "O app ouve um idioma por vez e alterna após cada tradução. No Android 14+ também tenta detectar o idioma. Para detecção 100% automática, use o Azure."
        )
        LabeledSlider("Pausa para considerar que terminou de falar", s.endPause, 0.6f..3.0f, "%.1f s") { v -> vm.update { it.copy(endPause = v) } }
        Hint("Aumente se o app estiver traduzindo antes de a pessoa terminar a frase.")
        SwitchRow("Mostrar pinyin no texto em chinês", s.showPinyin) { v -> vm.update { it.copy(showPinyin = v) } }

        if (s.recognitionEngine == RecognitionEngine.AZURE || s.voiceProvider == VoiceProvider.AZURE) {
            Section("Chave Azure Speech")
            OutlinedTextField(value = azureKey, onValueChange = { azureKey = it }, label = { Text("Chave Azure Speech") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = azureRegion, onValueChange = { azureRegion = it }, label = { Text("Região (ex.: brazilsouth, eastus)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Button(onClick = {
                vm.update { it.copy(azureKey = azureKey.trim(), azureRegion = azureRegion.trim()) }
                vm.toast = "Chave salva"
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Salvar chave") }
            Hint("portal.azure.com → criar recurso \"Speech\" (camada gratuita F0) → Chaves e Ponto de extremidade. A chave fica só neste aparelho.")
        }

        Section("Voz da tradução")
        RadioRow("Voz neural do Android (Google, grátis)", s.voiceProvider == VoiceProvider.ANDROID) {
            vm.update { it.copy(voiceProvider = VoiceProvider.ANDROID) }
        }
        RadioRow("Azure Neural (premium, chave própria)", s.voiceProvider == VoiceProvider.AZURE) {
            vm.update { it.copy(voiceProvider = VoiceProvider.AZURE) }
        }

        if (s.voiceProvider == VoiceProvider.ANDROID) {
            EnginePicker(vm)
            listOf(s.me, s.other).distinct().forEach { l -> VoicePicker(vm, l) }
            Row {
                OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { runCatching { ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } }
                }) { Text("Instalar vozes") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.loadVoices() }) { Text("Atualizar lista") }
            }
            Hint("Dica: no app \"Serviços de voz do Google\", baixe as vozes Português (Brasil) e Chinês (Mandarim). As vozes marcadas como \"online\" são as mais naturais.")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Voz feminina", color = White, modifier = Modifier.weight(1f))
                Switch(checked = s.azureFemale, onCheckedChange = { v -> vm.update { it.copy(azureFemale = v) } })
            }
            Hint("Vozes: Francisca/Antonio (PT-BR), Jenny/Guy (EN), Xiaoxiao/Yunxi (中文). A chave fica só neste aparelho. Se o Azure falhar, o app usa a voz do Android automaticamente.")
        }

        Row(Modifier.padding(top = 8.dp)) {
            listOf(s.me, s.other).distinct().forEach { l ->
                OutlinedButton(onClick = { vm.testVoice(l) }, modifier = Modifier.padding(end = 8.dp)) { Text("Testar ${l.flag}") }
            }
        }

        LabeledSlider("Velocidade da fala", s.rate, 0.6f..1.5f, "%.2f×") { v -> vm.update { it.copy(rate = v) } }
        LabeledSlider("Tom", s.pitch, 0.8f..1.2f, "%.2f") { v -> vm.update { it.copy(pitch = v) } }
        LabeledSlider("Volume", s.volume, 0.1f..1f, "%.0f%%", scale = 100f) { v -> vm.update { it.copy(volume = v) } }
        SwitchRow("Falar traduções automaticamente", s.autoSpeak) { v -> vm.update { it.copy(autoSpeak = v) } }
        SwitchRow("Trocar idioma automaticamente (conversa)", s.autoSwitch) { v -> vm.update { it.copy(autoSwitch = v) } }

        Section("Tradução")
        RadioRow("Online (melhor qualidade) com reserva offline", s.translationMode == TranslationMode.ONLINE_THEN_OFFLINE) {
            vm.update { it.copy(translationMode = TranslationMode.ONLINE_THEN_OFFLINE) }
        }
        RadioRow("Somente offline (no aparelho)", s.translationMode == TranslationMode.OFFLINE_ONLY) {
            vm.update { it.copy(translationMode = TranslationMode.OFFLINE_ONLY) }
        }
        OutlinedButton(onClick = { vm.downloadOfflinePacks() }) { Text("Baixar pacotes offline PT · EN · 中文") }

        Section("Modo técnico industrial")
        SwitchRow("Usar glossário técnico (${vm.glossarySize} termos)", s.technicalMode) { v -> vm.update { it.copy(technicalMode = v) } }
        OutlinedTextField(
            value = glossaryText, onValueChange = { glossaryText = it },
            label = { Text("português | english | 中文 (um termo por linha)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), textStyle = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { vm.update { it.copy(glossary = glossaryText) }; vm.toast = "Glossário salvo" }, modifier = Modifier.padding(top = 6.dp)) {
            Text("Salvar glossário")
        }

        Section("Privacidade")
        SwitchRow("Salvar histórico no aparelho", s.saveHistory) { v -> vm.update { it.copy(saveHistory = v) } }
        SwitchRow("Manter tela ligada durante a conversa", s.keepScreenOn) { v -> vm.update { it.copy(keepScreenOn = v) } }
        OutlinedButton(onClick = { vm.clearHistory() }) { Text("Apagar histórico", color = Color(0xFFFF7A7A)) }
        Hint("O áudio vai apenas para o reconhecedor de voz do Android. Nada é gravado. O texto é enviado ao serviço de tradução online (ou fica no aparelho no modo offline).")
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun EnginePicker(vm: MainViewModel) {
    val engines = vm.androidEngines
    if (engines.size < 2) return
    var open by remember { mutableStateOf(false) }
    val current = engines.firstOrNull { it.name == vm.settings.androidEngine }?.label
        ?: engines.firstOrNull { it.name == vm.androidTts.defaultEngine }?.label ?: "Padrão do sistema"
    Box(Modifier.padding(top = 6.dp)) {
        OutlinedButton(onClick = { open = true }) { Text("Motor: $current ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            engines.forEach { e ->
                DropdownMenuItem(text = { Text(e.label) }, onClick = { open = false; vm.update { it.copy(androidEngine = e.name) } })
            }
        }
    }
}

@Composable
private fun VoicePicker(vm: MainViewModel, lang: Lang) {
    val voices = vm.androidTts.voicesFor(vm.androidVoices, lang)
    var open by remember { mutableStateOf(false) }
    val sel = vm.settings.androidVoices[lang.code]
    fun label(name: String?): String {
        val v = voices.firstOrNull { it.name == name } ?: return "Automática (mais natural)"
        val q = when {
            v.quality >= 500 -> "★★★"
            v.quality >= 400 -> "★★"
            else -> "★"
        }
        return "${v.name} $q" + if (v.isNetworkConnectionRequired) " · online" else ""
    }
    Box(Modifier.padding(top = 6.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${lang.flag} ${label(sel)} ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Automática (mais natural)") }, onClick = {
                open = false; vm.update { it.copy(androidVoices = it.androidVoices - lang.code) }
            })
            if (voices.isEmpty()) DropdownMenuItem(text = { Text("Nenhuma voz instalada para ${lang.label}") }, onClick = { open = false })
            voices.forEach { v ->
                DropdownMenuItem(text = { Text(label(v.name)) }, onClick = {
                    open = false; vm.update { it.copy(androidVoices = it.androidVoices + (lang.code to v.name)) }
                })
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
}

@Composable
private fun Hint(t: String) {
    Text(t, color = Color(0xFF809AB6), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, color = White, fontSize = 14.sp)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, scale: Float = 1f,
    onChange: (Float) -> Unit,
) {
    var v by remember(value) { mutableStateOf(value) }
    Text("$label: ${String.format(fmt, v * scale)}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    Slider(value = v, onValueChange = { v = it }, valueRange = range, onValueChangeFinished = { onChange(v) })
}

