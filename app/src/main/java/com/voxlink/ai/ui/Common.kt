package com.voxlink.ai.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxlink.ai.MainViewModel
import com.voxlink.ai.model.Lang

/** Language colours: Brasil verde, China vermelho, EUA azul. */
fun langColor(l: Lang?): Color = when (l) {
    Lang.PT -> Color(0xFF00C853)
    Lang.ZH -> Color(0xFFFF3B3B)
    Lang.EN -> Color(0xFF2F80FF)
    null -> Color(0xFF8BA6C2)
}

/** Dot in the language colour; blinks while someone is speaking. */
@Composable
fun SpeechDot(lang: Lang?, blinking: Boolean, size: Dp = 14.dp, dimmed: Boolean = false) {
    val t = rememberInfiniteTransition(label = "blink")
    val a by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "a")
    val sc by t.animateFloat(1f, 1.35f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "s")
    val c = langColor(lang)
    Box(
        Modifier.size(size).scale(if (blinking) sc else 1f).alpha(if (blinking) a else if (dimmed) 0.35f else 0.9f)
            .clip(CircleShape).background(c)
    )
}

/**
 * Big hands-free status: who is expected to speak (colour), blinking while voice is detected,
 * "translating" and "app speaking — microphone paused".
 */
@Composable
fun SpeechIndicator(vm: MainViewModel, lang: Lang, big: Boolean = false) {
    val c = langColor(lang)
    val (label, color, blink) = when {
        !vm.sessionActive -> Triple("Microfone desligado", Color(0xFF8BA6C2), false)
        vm.paused -> Triple("Escuta pausada", Color(0xFF8BA6C2), false)
        vm.speaking -> Triple("🔊 App falando — microfone pausado", Color(0xFFBFE9FF), false)
        vm.translating -> Triple("Traduzindo…", Color(0xFFFFC870), false)
        vm.voiceActive -> Triple("Detectando fala · ${lang.flag} ${lang.label}", c, true)
        vm.pendingText.isNotBlank() -> Triple("Aguardando a pausa para traduzir…", c, false)
        vm.listening -> Triple("Ouvindo · ${lang.flag} ${lang.label} — pode falar", c, false)
        else -> Triple("Preparando o microfone…", Color(0xFF8BA6C2), false)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = if (blink) 0.16f else 0.08f))
            .border(if (blink) 2.dp else 1.dp, color.copy(alpha = if (blink) 1f else 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = if (big) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeechDot(if (vm.speaking || vm.translating || !vm.sessionActive) null else lang, blink, if (big) 22.dp else 14.dp,
            dimmed = !vm.listening && !vm.speaking)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = color, fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.SemiBold)
            if (big && vm.sessionActive && !vm.speaking) {
                Text("Traduz e fala sozinho após ${"%.1f".format(vm.settings.endPause)} s de pausa", color = Color(0xFF8BA6C2), fontSize = 12.sp)
            }
        }
    }
}
