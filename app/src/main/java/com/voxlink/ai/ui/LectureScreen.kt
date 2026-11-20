package com.voxlink.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxlink.ai.MainViewModel
import com.voxlink.ai.model.Lang

private val LBg = Color(0xFF06101E)
private val LCyan = Color(0xFF32D9FF)
private val LMuted = Color(0xFF8BA6C2)
private val LWhite = Color(0xFFEFF7FF)
private val LLine = Color(0xFF164A76)

/**
 * Modo Palestra: hands-free. Opens already listening; one person speaks, the subtitles roll in real time,
 * and after each pause the app translates and speaks (microphone paused while it speaks).
 */
@Composable
fun LectureScreen(vm: MainViewModel, startMic: () -> Unit, onExport: (Boolean) -> Unit, close: () -> Unit) {
    val s = vm.settings
    LaunchedEffect(Unit) {
        val speaker = vm.lectureSpeaker.takeIf { it != s.me } ?: s.other
        vm.startLecture(speaker, s.me)
        if (!vm.sessionActive) startMic()
    }
    val entries = vm.lectureEntries()
    val live = listOf(vm.pendingText, vm.partial).filter { it.isNotBlank() }.joinToString(" ")
    val listState = rememberLazyListState()
    val total = entries.size + 1
    LaunchedEffect(entries.size, live, vm.liveTranslation) {
        if (total > 0) listState.animateScrollToItem(total - 1)
    }
    var exportMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(LBg).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🎤 Modo Palestra", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LWhite, modifier = Modifier.weight(1f))
            Button(onClick = { vm.stopLecture(); close() }) { Text("Sair") }
        }

        // Who speaks → translate to
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("Quem fala", color = LMuted, fontSize = 12.sp, modifier = Modifier.width(70.dp))
            Lang.entries.forEach { l -> LangChip(l, vm.lectureSpeaker == l) { vm.chooseLectureSpeaker(l) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Traduzir p/", color = LMuted, fontSize = 12.sp, modifier = Modifier.width(70.dp))
            Lang.entries.forEach { l -> LangChip(l, vm.lectureTarget == l) { vm.chooseLectureTarget(l) } }
        }

        // Voice + pause
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("EACH" to "🔊 Fala a cada pausa", "SILENT" to "🔇 Só legenda", "AT_END" to "⏭ Lê no final").forEach { (k, label) ->
                Option(label, s.lectureVoice == k, Modifier.weight(1f)) { vm.update { it.copy(lectureVoice = k) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pausa", color = LMuted, fontSize = 12.sp)
            listOf(0.6f, 0.9f, 1.2f, 1.5f, 2.0f).forEach { p ->
                Option("${"%.1f".format(p)} s", kotlin.math.abs(s.endPause - p) < 0.05f, Modifier.weight(1f)) { vm.update { it.copy(endPause = p) } }
            }
        }

        Spacer(Modifier.height(10.dp))
        SpeechIndicator(vm, vm.lectureSpeaker, big = true)

        // Rolling subtitles
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp)) {
            if (entries.isEmpty() && live.isEmpty()) {
                item {
                    Text(
                        "As frases traduzidas aparecem aqui, em tempo real, uma embaixo da outra.",
                        color = LMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp),
                    )
                }
            }
            items(entries, key = { it.id }) { e -> Subtitle(e.from, e.original, e.originalPinyin, e.to, e.translation, e.translationPinyin, s.showPinyin, live = false) }
            item {
                if (live.isNotEmpty()) {
                    Subtitle(vm.lectureSpeaker, live, null, vm.lectureTarget, vm.liveTranslation, null, s.showPinyin, live = true)
                }
            }
        }

        // Bottom controls
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { vm.swapLecture() }) { Text("⇄ Inverter") }
            Box(
                Modifier.size(72.dp).clip(CircleShape)
                    .background(if (vm.sessionActive) Color(0xFF0B6B5A) else Color(0xFF0B3D74))
                    .border(3.dp, if (vm.sessionActive) langColor(vm.lectureSpeaker) else LCyan, CircleShape)
                    .clickable {
                        if (vm.sessionActive) vm.stopSession() else {
                            vm.startLecture(vm.lectureSpeaker, vm.lectureTarget)
                            startMic()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text(if (vm.sessionActive) "■" else "🎙", fontSize = 26.sp, color = LWhite) }
            Box {
                OutlinedButton(onClick = { exportMenu = true }) { Text("📄 Ata") }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    DropdownMenuItem(text = { Text("Excel (.xlsx)") }, onClick = { exportMenu = false; onExport(false) })
                    DropdownMenuItem(text = { Text("Word (.docx)") }, onClick = { exportMenu = false; onExport(true) })
                }
            }
        }
    }
}

@Composable
private fun LangChip(l: Lang, selected: Boolean, onClick: () -> Unit) {
    val c = langColor(l)
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.copy(alpha = .22f) else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) c else LLine, RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text("${l.flag} ${l.short}", color = if (selected) c else LWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
private fun Option(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) LCyan.copy(alpha = .18f) else Color(0xFF0A1A2E))
            .border(1.dp, if (selected) LCyan else LLine, RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) LCyan else LWhite, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun Subtitle(
    from: Lang, original: String, originalPinyin: String?, to: Lang, translation: String, translationPinyin: String?,
    showPinyin: Boolean, live: Boolean,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(langColor(from)))
        Column(Modifier.padding(start = 10.dp)) {
            Text("${from.flag} $original", color = if (live) LMuted else Color(0xFFB0C6DE), fontSize = 15.sp,
                fontStyle = if (live) FontStyle.Italic else FontStyle.Normal)
            PinyinLine(original, showPinyin, small = true, known = originalPinyin)
            if (translation.isNotBlank()) {
                Text("${to.flag} $translation", color = if (live) Color(0xFFBFE9FF) else LWhite, fontSize = 24.sp, lineHeight = 31.sp,
                    fontWeight = FontWeight.Medium, fontStyle = if (live) FontStyle.Italic else FontStyle.Normal,
                    modifier = Modifier.padding(top = 2.dp))
                PinyinLine(translation, showPinyin, known = translationPinyin)
            } else if (live) {
                Text("…", color = LMuted, fontSize = 20.sp)
            }
        }
    }
}
