package com.trananh.rollingdoor.ui.pairing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.trananh.rollingdoor.R
import com.trananh.rollingdoor.protocol.SetupCode
import com.trananh.rollingdoor.ui.components.PressableSurface
import com.trananh.rollingdoor.ui.theme.DoorTheme
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Full-screen camera scanner built into the app: CameraX preview plus ML Kit's bundled QR model,
// so it works offline and without Google Play services. Only RDOOR1 setup codes are accepted;
// any other QR code shows a warning and scanning goes on. The caller holds CAMERA permission.
@Composable
fun QrScannerOverlay(
    onCode: (String) -> Unit,
    onClose: () -> Unit,
    onCameraError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var wrongCode by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    LightStatusBarIcons()

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        val delivered = AtomicBoolean(false)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, QrAnalyzer(scanner, mainExecutor) { raw ->
                    if (SetupCode.parse(raw) == null) {
                        wrongCode = true
                    } else if (delivered.compareAndSet(false, true)) {
                        currentOnCode(raw)
                    }
                })
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.w("QrScanner", "camera unavailable", e)
                currentOnCameraError()
            }
        }, mainExecutor)

        onDispose {
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val frame = minOf(maxWidth, maxHeight) * 0.7f
            Viewfinder(frameSize = frame)
            Text(
                stringResource(if (wrongCode) R.string.scanner_wrong_code else R.string.scanner_hint),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = frame / 2 + 40.dp)
                    .padding(horizontal = DoorTheme.spacing.l)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = DoorTheme.type.headline,
                color = if (wrongCode) DoorTheme.colors.orange else Color.White,
                textAlign = TextAlign.Center,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(DoorTheme.spacing.m),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundIconButton(Icons.Rounded.Close, stringResource(R.string.scanner_close), onClose)
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                RoundIconButton(
                    if (torchOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                    stringResource(R.string.scanner_torch),
                ) {
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                }
            }
        }
    }
}

// Feeds camera frames to ML Kit; reports each QR code's text on the main thread.
private class QrAnalyzer(
    private val scanner: BarcodeScanner,
    private val mainExecutor: Executor,
    private val onText: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val image = proxy.image
        if (image == null) {
            proxy.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener(mainExecutor) { codes ->
                codes.firstNotNullOfOrNull { it.rawValue }?.let(onText)
            }
            .addOnCompleteListener { proxy.close() }
    }
}

// Dims everything but a rounded square in the middle, outlined in white.
@Composable
private fun Viewfinder(frameSize: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        val side = frameSize.toPx()
        val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2)
        val corner = CornerRadius(24.dp.toPx())
        val hole = RoundRect(topLeft.x, topLeft.y, topLeft.x + side, topLeft.y + side, corner)
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(hole)
        }
        drawPath(dim, Color.Black.copy(alpha = 0.55f))
        drawRoundRect(
            color = Color.White,
            topLeft = topLeft,
            size = Size(side, side),
            cornerRadius = corner,
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    PressableSurface(onClick = onClick, shape = CircleShape, color = Color.Black.copy(alpha = 0.45f)) {
        Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.padding(10.dp).size(24.dp))
    }
}

// The camera view is dark in both themes: light status bar icons while it shows.
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    val isDark = DoorTheme.colors.isDark
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        onDispose { controller?.isAppearanceLightStatusBars = !isDark }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
