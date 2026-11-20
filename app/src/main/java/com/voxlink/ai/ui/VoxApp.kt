package com.voxlink.ai.ui

import android.Manifest
import android.content.ClipData
import android.os.Build
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxlink.ai.MainViewModel
import com.voxlink.ai.model.Lang
import com.voxlink.ai.model.Pinyin
import com.voxlink.ai.model.TranslationMode
import com.voxlink.ai.sync.SyncState
import com.voxlink.ai.sync.SyncTransport
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
fun VoxApp(vm: MainViewModel, onMic: () -> Unit, requestPermissions: (List<String>, () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var showSettings by remember { mutableStateOf(false) }
    var faceToFace by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }

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
                showSync -> {
                    BackHandler { showSync = false }
                    SyncScreen(vm, requestPermissions) { showSync = false }
                }
                faceToFace -> {
                    BackHandler { faceToFace = false }
                    FaceToFaceScreen(vm, onMic) { faceToFace = false }
                }
                else -> MainScreen(vm, onMic, openSettings = { showSettings = true }, openFaceToFace = { faceToFace = true }, openSync = { showSync = true })
            }
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel, onMic: () -> Unit, openSettings: () -> Unit, openFaceToFace: () -> Unit, openSync: () -> Unit) {
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
                OutlinedButton(onClick = openSync, border = BorderStroke(1.dp, if (vm.syncConnected) Green else Line)) {
                    Text("📱", fontSize = 18.sp, color = White)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = openFaceToFace, border = BorderStroke(1.dp, Line)) { Text("⇅", fontSize = 20.sp, color = White) }
                Spacer(Modifier.width(4.dp))
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

        // Mode banner: two phones / offline direction / online turns
        item {
            when {
                vm.syncConnected -> ModeBanner(
                    Green, "📱⇄📱 CONECTADO A ${(vm.partnerName ?: "OUTRO CELULAR").uppercase()}",
                    "Você fala ${s.me.flag} ${s.me.label} · o outro celular fala ${(vm.partnerLang ?: s.other).let { "${it.flag} ${it.label}" }}",
                    "Gerenciar", openSync,
                )
                vm.offlineNow -> ModeBanner(
                    Color(0xFFFFC870), "OFFLINE · SEM INTERNET",
                    "${vm.listenLang.flag} ${vm.listenLang.label}  →  ${vm.other(vm.listenLang).flag} ${vm.other(vm.listenLang).label}",
                    "⇄ Trocar", { vm.swapDirection() }, big = true,
                )
                else -> Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ouvindo:", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    listOf(s.me, s.other).forEach { l ->
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
                    Text("Alternar", color = Muted, fontSize = 12.sp)
                    Switch(checked = s.autoSwitch, onCheckedChange = { v -> vm.update { it.copy(autoSwitch = v) } })
                }
            }
        }

        item { Waveform(active = vm.listening || vm.speaking, level = if (vm.speaking) .7f else vm.rms, speaking = vm.speaking) }

        // Original speech
        item {
            val live = listOf(vm.remotePartial, vm.pendingText, vm.partial).filter { it.isNotBlank() }.joinToString(" ")
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
                        if (vm.liveTranslation.isNotBlank()) {
                            Column {
                                Text("● ao vivo", color = Cyan, fontSize = 11.sp)
                                Text(vm.liveTranslation, color = Color(0xFFBFE9FF), fontSize = 24.sp, lineHeight = 32.sp, fontStyle = FontStyle.Italic)
                                PinyinLine(vm.liveTranslation, s.showPinyin)
                            }
                        } else if (vm.lastTranslation.isEmpty()) {
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
                    val voice = if (vm.offlineNow) "offline" else "online"
                    Text(
                        (if (vm.lastLatency.isNotBlank()) "⏱ ${vm.lastLatency} · " else "") +
                            "modo $voice · tradução ${vm.lastProvider}" + if (s.technicalMode) " · técnico" else "",
                        color = Muted, fontSize = 12.sp,
                    )
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

        // Typed input + quick phrases (works in both phones when connected)
        item { TypeBar(vm) }

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
            active = vm.listenLang == s.other && vm.listening, showPinyin = s.showPinyin,
            modifier = Modifier.weight(1f).rotate(180f),
        ) { vm.listenFor(s.other) }
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
            active = vm.listenLang == s.me && vm.listening, showPinyin = s.showPinyin,
            modifier = Modifier.weight(1f),
        ) { vm.listenFor(s.me) }
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

@Composable
private fun ModeBanner(color: Color, title: String, line: String, action: String, onAction: () -> Unit, big: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = .12f)).border(1.dp, color, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = color, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text(line, color = White, fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Bg)) { Text(action) }
    }
}

@Composable
private fun TypeBar(vm: MainViewModel) {
    val s = vm.settings
    var text by remember { mutableStateOf("") }
    var from by remember(s.me) { mutableStateOf(s.me) }
    val to = if (vm.syncConnected) (vm.partnerLang ?: s.other) else vm.other(from)
    fun send() {
        if (text.isNotBlank()) {
            vm.sendTyped(text, from)
            text = ""
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            "⌨ DIGITAR " + if (vm.syncConnected) "· vai para ${vm.partnerName ?: "o outro celular"}" else "· o app fala a tradução",
            color = Color(0xFF55D3FF), fontSize = 11.sp, letterSpacing = 1.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
                    .clickable { if (!vm.syncConnected) from = vm.other(from) }
                    .padding(horizontal = 10.dp, vertical = 14.dp)
            ) { Text("${from.flag}→${to.flag}", fontSize = 16.sp) }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Digite em ${from.label}…", fontSize = 14.sp) },
                modifier = Modifier.weight(1f), maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { send() }, enabled = text.isNotBlank(), shape = CircleShape) { Text("➤") }
        }
        PinyinLine(text, s.showPinyin, small = true)
        val phrases = remember(s.quickPhrases) { s.quickPhrases.lines().map { it.trim() }.filter { it.isNotEmpty() } }
        if (phrases.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                phrases.forEach { p ->
                    Box(
                        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A1A2E))
                            .border(1.dp, Color(0xFF15578A), RoundedCornerShape(14.dp))
                            .clickable { vm.sendTyped(p, s.me) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(p, color = Color(0xFFC7DEF8), fontSize = 13.sp, maxLines = 1) }
                }
            }
        }
    }
}

// ======================= Two phones =======================

@Composable
private fun SyncScreen(vm: MainViewModel, requestPermissions: (List<String>, () -> Unit) -> Unit, close: () -> Unit) {
    var tab by remember { mutableStateOf(SyncTransport.WIFI) }
    var manualIp by remember { mutableStateOf("") }
    val connected = vm.syncConnected

    fun withPerms(t: SyncTransport, action: () -> Unit) {
        val perms = when (t) {
            SyncTransport.WIFI -> emptyList()
            SyncTransport.WIFI_DIRECT -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            SyncTransport.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        }
        requestPermissions(perms, action)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dois celulares", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = White, modifier = Modifier.weight(1f))
            Button(onClick = close) { Text("Voltar") }
        }
        Hint("Cada pessoa usa o próprio celular com o VoxLink (de preferência com fone). Cada celular ouve só o seu dono e manda a tradução para o outro, que fala no idioma dele. Os dois podem falar sem esperar a vez. Não precisa de internet nem de servidor.")

        // Status
        val stColor = when (vm.syncState) {
            SyncState.CONNECTED -> Green
            SyncState.IDLE -> Muted
            else -> Cyan
        }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(RoundedCornerShape(14.dp))
                .border(1.dp, stColor, RoundedCornerShape(14.dp)).padding(12.dp)
        ) {
            Text(
                when (vm.syncState) {
                    SyncState.CONNECTED -> "● Conectado a ${vm.partnerName ?: "outro celular"}"
                    SyncState.WAITING -> "● Aguardando / procurando"
                    SyncState.CONNECTING -> "● Conectando…"
                    SyncState.IDLE -> "○ Desconectado"
                }, color = stColor, fontWeight = FontWeight.SemiBold
            )
            if (vm.syncInfo.isNotBlank()) Text(vm.syncInfo, color = Muted, fontSize = 12.sp)
            if (connected) {
                Text(
                    "Você fala ${vm.settings.me.flag} ${vm.settings.me.label} · ${vm.partnerName ?: "o outro"} fala ${(vm.partnerLang ?: vm.settings.other).flag} ${(vm.partnerLang ?: vm.settings.other).label}",
                    color = White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
                )
                Text("Agora volte e toque no microfone nos dois celulares.", color = Green, fontSize = 13.sp)
            }
            if (vm.syncState != SyncState.IDLE) {
                OutlinedButton(onClick = { vm.syncStop() }, modifier = Modifier.padding(top = 6.dp)) { Text(if (connected) "Desconectar" else "Cancelar") }
            }
        }

        if (!connected) {
            // Transport tabs
            Row(Modifier.fillMaxWidth()) {
                SyncTransport.entries.forEach { t ->
                    val sel = tab == t
                    Box(
                        Modifier.weight(1f).padding(2.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) Cyan.copy(alpha = .18f) else Color.Transparent)
                            .border(1.dp, if (sel) Cyan else Line, RoundedCornerShape(12.dp))
                            .clickable { tab = t }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(t.label, color = if (sel) Cyan else White, fontSize = 12.sp, textAlign = TextAlign.Center) }
                }
            }

            when (tab) {
                SyncTransport.WIFI -> {
                    Section("Wi-Fi local ou hotspot")
                    Steps(
                        "Os dois celulares na mesma rede Wi-Fi. Se a rede da empresa bloquear, ligue o roteador/hotspot de um celular e conecte o outro nele.",
                        "No celular A toque em \"Aguardar conexão\".",
                        "No celular B toque em \"Procurar\" e escolha o A na lista — ou digite o IP mostrado no A.",
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        Button(onClick = { vm.syncHostWifi() }) { Text("Aguardar conexão") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.syncDiscoverWifi() }) { Text("Procurar") }
                    }
                    Text("IP deste celular: ${vm.syncLocalIp()}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedTextField(
                            value = manualIp, onValueChange = { manualIp = it }, singleLine = true,
                            label = { Text("IP do outro celular") }, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { if (manualIp.isNotBlank()) vm.syncJoinWifi(manualIp) }) { Text("Conectar") }
                    }
                }
                SyncTransport.WIFI_DIRECT -> {
                    Section("Wi-Fi Direct (celular ↔ celular, sem roteador)")
                    Steps(
                        "Ligue o Wi-Fi nos dois celulares (não precisa estar conectado a nenhuma rede). No Android 12 ou anterior, ligue também a Localização.",
                        "Toque em \"Procurar\" nos DOIS celulares.",
                        "Em um deles, toque no nome do outro na lista e aceite o convite que aparece no outro celular.",
                    )
                    Button(onClick = { withPerms(SyncTransport.WIFI_DIRECT) { vm.syncStartWifiDirect() } }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Procurar")
                    }
                }
                SyncTransport.BLUETOOTH -> {
                    Section("Bluetooth")
                    Steps(
                        "Pareie os dois celulares uma vez em Configurações → Bluetooth.",
                        "No celular A toque em \"Aguardar conexão\".",
                        "No celular B toque em \"Escolher aparelho\" e selecione o A. Alcance ≈ 10 m.",
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        Button(onClick = { withPerms(SyncTransport.BLUETOOTH) { vm.syncHostBluetooth() } }) { Text("Aguardar conexão") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { withPerms(SyncTransport.BLUETOOTH) { vm.syncListBluetooth() } }) { Text("Escolher aparelho") }
                    }
                }
            }

            val peers = vm.syncPeers.filter { it.transport == tab }
            if (peers.isNotEmpty()) {
                Section("Celulares encontrados")
                peers.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0A1A2E)).border(1.dp, Line, RoundedCornerShape(12.dp))
                            .clickable { vm.syncConnectPeer(p) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📱", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = White, fontWeight = FontWeight.SemiBold)
                            Text(p.id, color = Muted, fontSize = 11.sp)
                        }
                        Text("Conectar ›", color = Cyan)
                    }
                }
            }
        }

        Section("Dica")
        Hint("Com fone de ouvido em cada celular, cada pessoa escuta a tradução só no próprio fone e o microfone nunca para de ouvir. Sem fone, o celular pausa o microfone enquanto fala a tradução.")
    }
}

@Composable
private fun Steps(vararg steps: String) {
    steps.forEachIndexed { i, t ->
        Row(Modifier.padding(vertical = 3.dp)) {
            Text("${i + 1}.", color = Cyan, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
            Text(t, color = White, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

// ======================= Settings =======================

@Composable
private fun SettingsScreen(vm: MainViewModel, close: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings
    var glossaryText by remember { mutableStateOf(s.glossary) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Configurações", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = White, modifier = Modifier.weight(1f))
            Button(onClick = {
                vm.update { it.copy(glossary = glossaryText) }
                close()
            }) { Text("Concluir") }
        }
        Hint("Tudo grátis, sem chave nem assinatura. Online é o modo mais rápido; sem internet o app passa sozinho para o modo offline.")

        Section("Desempenho")
        SwitchRow("Prioridade velocidade", s.speedMode) { v -> vm.update { it.copy(speedMode = v) } }
        Hint("Traduz no aparelho (≈0,1 s) e só usa a tradução online se ela chegar em até 0,7 s. Desligado: espera a online (melhor qualidade, mais lenta).")
        SwitchRow("Reconhecimento de fala no aparelho", s.onDeviceRecognition) { v -> vm.update { it.copy(onDeviceRecognition = v) } }
        Hint("Android 12+: mais rápido e funciona sem internet. Se o pacote do idioma não estiver baixado, o app usa o online e baixa o pacote.")
        SwitchRow("Vozes do aparelho (começam na hora)", s.fastVoice) { v -> vm.update { it.copy(fastVoice = v) } }
        SwitchRow("Legenda traduzida ao vivo enquanto fala", s.liveSubtitles) { v -> vm.update { it.copy(liveSubtitles = v) } }

        Section("Modo offline")
        SwitchRow("Usar modo offline mesmo com internet", s.forceOffline) { v -> vm.update { it.copy(forceOffline = v) } }
        Hint("No offline não há detecção automática: você escolhe o sentido na tela principal (ex.: 🇧🇷 Português → 🇨🇳 Chinês) e toca em \"⇄ Trocar\" quando a outra pessoa for falar. Usa o reconhecimento, a tradução e as vozes baixadas no celular.")
        Button(onClick = { vm.prepareOffline() }, modifier = Modifier.padding(top = 6.dp)) { Text("Preparar modo offline (baixar pacotes)") }
        Row(Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }) { Text("Reconhecimento offline") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Vozes offline") }
        }
        Hint("Faça uma vez, com Wi-Fi: 1) toque em Preparar; 2) em Reconhecimento offline, baixe Português (Brasil) e Chinês; 3) em Vozes offline (Serviços de voz do Google → Instalar dados de voz), baixe as vozes de Português (Brasil) e Chinês (Mandarim).")

        Section("Reconhecimento de fala")
        LabeledSlider("Pausa para considerar que terminou de falar", s.endPause, 0.6f..3.0f, "%.1f s") { v -> vm.update { it.copy(endPause = v) } }
        Hint("Aumente se o app estiver traduzindo antes de a pessoa terminar a frase.")
        SwitchRow("Mostrar pinyin no texto em chinês", s.showPinyin) { v -> vm.update { it.copy(showPinyin = v) } }

        Section("Voz da tradução")
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
        Hint("No app \"Serviços de voz do Google\", baixe as vozes Português (Brasil) e Chinês (Mandarim) para funcionar offline. As marcadas \"online\" são as mais naturais quando há internet.")

        Section("Fone de ouvido")
        SwitchRow("Fone para mim, alto-falante para o colega", s.splitAudio) { v -> vm.update { it.copy(splitAudio = v) } }
        Hint("Com fone Bluetooth/com fio conectado: a tradução para o seu idioma toca só no seu fone (e o microfone continua ouvindo, sem espera); a tradução para o colega sai no alto-falante do celular.")

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
        Hint("Online: tradução + pinyin do Google (grátis). Offline: ML Kit no aparelho (grátis).")

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

        Section("Frases rápidas")
        var phrasesText by remember { mutableStateOf(s.quickPhrases) }
        OutlinedTextField(
            value = phrasesText, onValueChange = { phrasesText = it },
            label = { Text("Uma frase por linha, no seu idioma (${s.me.label})") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), textStyle = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { vm.update { it.copy(quickPhrases = phrasesText) }; vm.toast = "Frases salvas" }, modifier = Modifier.padding(top = 6.dp)) {
            Text("Salvar frases")
        }

        Section("Dois celulares")
        var nameText by remember { mutableStateOf(s.deviceName) }
        OutlinedTextField(
            value = nameText, onValueChange = { nameText = it; vm.update { st -> st.copy(deviceName = it) } },
            label = { Text("Nome deste celular (aparece no outro)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

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

