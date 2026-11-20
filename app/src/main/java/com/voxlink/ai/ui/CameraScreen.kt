package com.voxlink.ai.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.voxlink.ai.MainViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private val CBg = Color(0xFF06101E)
private val CCyan = Color(0xFF32D9FF)
private val CGreen = Color(0xFF35E6A6)
private val CMuted = Color(0xFF8BA6C2)
private val CWhite = Color(0xFFEFF7FF)
private val CLine = Color(0xFF164A76)
private val CPinyin = Color(0xFFFFC870)

private data class Block(val box: Rect, val lines: Int, val item: MainViewModel.OcrItem)

/**
 * Point at a label, machine warning or instruction (Chinese, Portuguese or English), take a photo:
 * the text is read on the phone (ML Kit, offline) and the translation is drawn over each block.
 */
@Composable
fun CameraScreen(vm: MainViewModel, requestPermissions: (List<String>, () -> Unit) -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    @Suppress("DEPRECATION") val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var blocks by remember { mutableStateOf<List<Block>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val recognizer = remember { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    LaunchedEffect(Unit) { requestPermissions(listOf(Manifest.permission.CAMERA)) { granted = true } }
    DisposableEffect(Unit) { onDispose { runCatching { recognizer.close() } } }

    fun analyse(bmp: Bitmap) {
        photo = bmp
        blocks = emptyList()
        busy = true
        scope.launch {
            try {
                val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                val found = mutableListOf<Block>()
                for (b in text.textBlocks) {
                    val box = b.boundingBox ?: continue
                    val item = vm.translateForCamera(b.text) ?: continue
                    found += Block(box, b.lines.size.coerceAtLeast(1), item)
                    blocks = found.toList()
                }
                if (found.isEmpty()) vm.toast = "Nenhum texto encontrado. Aproxime e enquadre melhor."
            } catch (e: Exception) {
                vm.toast = "Falha ao ler o texto: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun takePhoto() {
        busy = true
        capture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val raw = image.toBitmap()
                image.close()
                val bmp = if (rot != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else raw
                analyse(bmp)
            }

            override fun onError(exception: ImageCaptureException) {
                busy = false
                vm.toast = "Falha na câmera: ${exception.message}"
            }
        })
    }

    Column(Modifier.fillMaxSize().background(CBg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ler etiqueta / tela", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CWhite, modifier = Modifier.weight(1f))
            Button(onClick = close) { Text("Voltar") }
        }
        Text(
            "Aponte para uma etiqueta, aviso de máquina ou instrução e toque em Traduzir. Lê chinês, português e inglês no próprio celular — funciona offline.",
            color = CMuted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 8.dp),
        )

        val p = photo
        if (p == null) {
            if (granted) {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(18.dp)).border(1.dp, CLine, RoundedCornerShape(18.dp))) {
                    AndroidView(
                        factory = { c ->
                            PreviewView(c).also { view ->
                                val future = ProcessCameraProvider.getInstance(c)
                                future.addListener({
                                    val provider = future.get()
                                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                    runCatching {
                                        provider.unbindAll()
                                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                                    }.onFailure { vm.toast = "Câmera indisponível: ${it.message}" }
                                }, ContextCompat.getMainExecutor(c))
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                    Box(
                        Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF0B3D74)).border(3.dp, CCyan, CircleShape)
                            .clickable(enabled = !busy) { takePhoto() },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (busy) "…" else "📷", fontSize = 30.sp) }
                }
                Text("Traduzir", color = CWhite, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                Text("Permita o acesso à câmera para ler etiquetas.", color = CWhite)
                Button(onClick = { requestPermissions(listOf(Manifest.permission.CAMERA)) { granted = true } }) { Text("Permitir câmera") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mostrar tradução sobre a foto", color = CWhite, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = showTranslation, onCheckedChange = { showTranslation = it })
            }
            PhotoWithOverlay(p, blocks, showTranslation)
            if (busy) Text("Lendo e traduzindo…", color = CCyan, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 10.dp)) {
                Button(onClick = { photo = null; blocks = emptyList() }) { Text("📷 Nova foto") }
            }
            if (blocks.isNotEmpty()) {
                Text("TEXTOS ENCONTRADOS", color = CCyan, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                blocks.forEach { b ->
                    val it = b.item
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF08172A)).border(1.dp, Color(0xFF17304A), RoundedCornerShape(12.dp))
                            .clickable { vm.speakText(it.translation, it.to) }.padding(12.dp)
                    ) {
                        Text("${it.from.flag} ${it.original}", color = Color(0xFFC8D9EE), fontSize = 13.sp)
                        it.originalPinyin?.let { py -> Text(py, color = CPinyin, fontSize = 12.sp, fontStyle = FontStyle.Italic) }
                        Text("${it.to.flag} ${it.translation}", color = CGreen, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                        it.translationPinyin?.let { py -> Text(py, color = CPinyin, fontSize = 12.sp, fontStyle = FontStyle.Italic) }
                        Text("🔊 toque para ouvir", color = CMuted, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PhotoWithOverlay(photo: Bitmap, blocks: List<Block>, showTranslation: Boolean) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
        val widthPx = with(density) { maxWidth.toPx() }
        val scale = widthPx / photo.width
        Image(
            bitmap = photo.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat() / photo.height),
        )
        if (showTranslation) blocks.forEach { b ->
            val x = with(density) { (b.box.left * scale).toDp() }
            val y = with(density) { (b.box.top * scale).toDp() }
            val w = with(density) { (b.box.width() * scale).toDp() }
            val h = with(density) { (b.box.height() * scale).toDp() }
            val lineHeightPx = b.box.height() * scale / b.lines
            val fs = with(density) { (lineHeightPx * 0.62f).toSp() }.value.coerceIn(8f, 22f).sp
            Box(
                Modifier.offset(x, y).width(w).height(h).background(Color(0xE6061323))
                    .border(1.dp, CGreen.copy(alpha = .8f)).padding(2.dp)
            ) {
                Text(b.item.translation, color = CWhite, fontSize = fs, lineHeight = fs * 1.1f, fontWeight = FontWeight.Medium)
            }
        }
    }
}
