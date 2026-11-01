package com.kingzcheung.xime.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as DrawSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.kingzcheung.xime.camera.CameraLuminance
import com.kingzcheung.xime.camera.CameraPreviewSession
import com.kingzcheung.xime.clipboard.sync.PairingQrDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun WindowsPairingScanner(onDismiss: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    fun cameraAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(cameraAllowed()) }
    var message by remember { mutableStateOf("对准电脑上 Lime 的配对二维码，识别后自动连接") }
    var importing by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val delivered = remember { AtomicBoolean(false) }
    val latestCode by rememberUpdatedState(onCode)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    fun accept(result: PairingQrDecoder.Result?, fromImage: Boolean = false) {
        when (result) {
            is PairingQrDecoder.Result.PairingCode -> if (delivered.compareAndSet(false, true)) latestCode(result.text)
            PairingQrDecoder.Result.OtherCode -> message = "已识别二维码，但不是 Lime 配对码；请打开电脑的“手机与电脑互联”"
            PairingQrDecoder.Result.InvalidPairingCode -> message = "配对码格式不完整或版本不支持，请在电脑刷新二维码"
            null -> if (fromImage) message = "图片中未识别到二维码，请保留完整二维码和四周白边"
        }
    }
    val latestAccept by rememberUpdatedState<(PairingQrDecoder.Result?) -> Unit>({ accept(it) })
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2
                        }
                        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                            ?: return@runCatching null
                        try {
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            PairingQrDecoder.scan(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
                        } finally { bitmap.recycle() }
                    }.getOrNull()
                }
                accept(result, fromImage = true)
            } finally { importing = false }
        }
    }
    LaunchedEffect(Unit) { if (!allowed) permission.launch(Manifest.permission.CAMERA) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = cameraAllowed()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(.94f).fillMaxHeight(.85f)
            .testTag("pairing-scanner"), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("扫码连接电脑", style = MaterialTheme.typography.titleLarge)
                Text(if (importing) "正在识别图片…" else message, style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                    if (allowed && !importing) {
                        val previewView = remember { PreviewView(context).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        } }
                        var viewSize by remember { mutableStateOf(IntSize.Zero) }
                        var session by remember { mutableStateOf<CameraPreviewSession?>(null) }
                        var cameraReady by remember { mutableStateOf(false) }
                        val accent = MaterialTheme.colorScheme.primary
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it })
                        Canvas(Modifier.fillMaxSize().testTag(if (cameraReady) "pairing-camera-ready" else "pairing-camera-starting")) {
                            val edge = minOf(size.width, size.height) * .82f
                            val left = (size.width - edge) / 2; val top = (size.height - edge) / 2
                            drawRect(Color.Black.copy(alpha = .28f), size = DrawSize(size.width, top))
                            drawRect(Color.Black.copy(alpha = .28f), Offset(0f, top + edge), DrawSize(size.width, top))
                            drawRect(Color.Black.copy(alpha = .28f), Offset(0f, top), DrawSize(left, edge))
                            drawRect(Color.Black.copy(alpha = .28f), Offset(left + edge, top), DrawSize(left, edge))
                            drawRect(accent, Offset(left, top), DrawSize(edge, edge), style = Stroke(2.dp.toPx()))
                        }
                        Text("靠近二维码 · 点画面对焦", color = Color.White, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
                        if (viewSize.width > 0 && viewSize.height > 0) DisposableEffect(owner, previewView, viewSize, retry) {
                            val active = AtomicBoolean(true)
                            val receivedFrame = AtomicBoolean(false)
                            val main = ContextCompat.getMainExecutor(context)
                            var lastFrame = 0L
                            cameraFailed = false
                            cameraReady = false
                            val camera = CameraPreviewSession(context, owner, previewView, Size(1280, 720), onFrame = { frame ->
                                if (receivedFrame.compareAndSet(false, true)) main.execute { if (active.get()) cameraReady = true }
                                val now = SystemClock.elapsedRealtime()
                                if (active.get() && !delivered.get() && now - lastFrame >= 150) {
                                    lastFrame = now
                                    val result = runCatching {
                                        val rect = frame.cropRect
                                        val plane = frame.planes[0]
                                        val gray = CameraLuminance.read(plane.buffer, plane.rowStride, plane.pixelStride,
                                            rect.left, rect.top, rect.width(), rect.height())
                                        PairingQrDecoder.scan(PlanarYUVLuminanceSource(gray.bytes, gray.width, gray.height,
                                            0, 0, gray.width, gray.height, false))
                                    }.getOrNull()
                                    if (result != null) main.execute { if (active.get()) latestAccept(result) }
                                }
                            }, onError = {
                                cameraFailed = true; message = "相机无法启动，请重试或从图片识别"
                            }, onReady = { flash -> hasFlash = flash; session?.setTorch(torch) })
                            session = camera
                            onDispose { active.set(false); camera.close(); session = null; hasFlash = false }
                        }
                        LaunchedEffect(torch, session) { session?.setTorch(torch) }
                    } else if (!allowed) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("扫码需要相机权限")
                        TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("允许相机") }
                    } else CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !importing, onClick = { gallery.launch("image/*") }) { Text("从图片识别") }
                    if (cameraFailed) TextButton(onClick = { retry++ }) { Text("重试") }
                    else if (hasFlash) TextButton(onClick = { torch = !torch }) { Text(if (torch) "关灯" else "开灯") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
