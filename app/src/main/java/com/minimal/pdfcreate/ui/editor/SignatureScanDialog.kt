package com.minimal.pdfcreate.ui.editor

import androidx.compose.ui.input.pointer.pointerInput
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.minimal.pdfcreate.AppContainer
import com.minimal.pdfcreate.imaging.CaptureSaver
import com.minimal.pdfcreate.imaging.SignatureExtractor
import com.minimal.pdfcreate.ui.common.fittedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Photograph a signature written on paper and lift it off the page.
 *
 * Two steps in one full-screen dialog: capture, then a live preview of the extraction with a
 * sensitivity slider. Nothing is written to disk until the user accepts the result, and what
 * gets written is a transparent PNG — identical in every way to a signature drawn on the pad,
 * so the rest of the app needs no special case for it.
 */
@Composable
fun SignatureScanDialog(onDismiss: () -> Unit, onSave: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repo = AppContainer.repository

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var captured by remember { mutableStateOf<Bitmap?>(null) }
    var sensitivity by remember { mutableFloatStateOf(0.5f) }
    var capturing by remember { mutableStateOf(false) }
    // Which part of the photo is actually the signature, in 0..1 of the captured image.
    var area by remember(captured) { mutableStateOf(Rect(0.06f, 0.28f, 0.94f, 0.72f)) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    // Re-runs whenever the photo or the slider changes; a superseded run is cancelled rather
    // than queued, which is what keeps the slider smooth.
    val extracted by produceState<Bitmap?>(initialValue = null, captured, sensitivity, area) {
        val src = captured
        value = if (src == null) null else withContext(Dispatchers.Default) {
            runCatching {
                val x = (area.left * src.width).toInt().coerceIn(0, src.width - 2)
                val y = (area.top * src.height).toInt().coerceIn(0, src.height - 2)
                val w = ((area.width) * src.width).toInt().coerceIn(2, src.width - x)
                val h = ((area.height) * src.height).toInt().coerceIn(2, src.height - y)
                val region = Bitmap.createBitmap(src, x, y, w, h)
                SignatureExtractor.extract(region, sensitivity).also {
                    if (region !== src) region.recycle()
                }
            }.getOrNull()
        }
    }

    LaunchedEffect(granted, captured) {
        if (!granted) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            // Only hold the camera while we are actually framing a shot.
            if (captured == null) {
                val preview = Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun capture() {
        if (capturing) return
        capturing = true
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val jpeg = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                captured = CaptureSaver.decodeUpright(jpeg, rotation, maxDim = 1600)
                capturing = false
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
            }
        })
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                if (captured == null) {
                    CaptureStep(
                        granted = granted,
                        capturing = capturing,
                        previewView = previewView,
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onShutter = { capture() },
                    )
                } else {
                    ReviewStep(
                        photo = captured!!,
                        area = area,
                        onArea = { area = it },
                        extracted = extracted,
                        sensitivity = sensitivity,
                        onSensitivity = { sensitivity = it },
                        onRetake = { captured = null },
                        onAccept = {
                            val bitmap = extracted ?: return@ReviewStep
                            val file = repo.newSignatureFile()
                            SignatureExtractor.writePng(bitmap, file)
                            onSave(file)
                        },
                    )
                }

                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .clickable { onDismiss() },
                )
            }
        }
    }
}

@Composable
private fun CaptureStep(
    granted: Boolean,
    capturing: Boolean,
    previewView: PreviewView,
    onRequestPermission: () -> Unit,
    onShutter: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // Framing guide. Advisory only — the extractor trims to the ink it finds, so the
            // box is about getting the signature big and flat, not about exact alignment.
            Canvas(Modifier.fillMaxSize()) {
                val boxW = size.width * 0.88f
                val boxH = size.height * 0.26f
                drawRect(
                    color = Color(0xFF55E39B),
                    topLeft = Offset((size.width - boxW) / 2f, (size.height - boxH) / 2f),
                    size = Size(boxW, boxH),
                    style = Stroke(
                        width = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 16f)),
                    ),
                )
            }

            Text(
                "Place the signature inside the box.\nFlat paper, even light, no shadow across the ink.",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp, start = 32.dp, end = 32.dp),
            )

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (capturing) Color.Gray else Color.White)
                    .border(4.dp, Color(0xFF55E39B), CircleShape)
                    .clickable(enabled = !capturing) { onShutter() }
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = onRequestPermission) { Text("Grant camera access") }
            }
        }
    }
}

@Composable
private fun ReviewStep(
    photo: Bitmap,
    area: Rect,
    onArea: (Rect) -> Unit,
    extracted: Bitmap?,
    sensitivity: Float,
    onSensitivity: (Float) -> Unit,
    onRetake: () -> Unit,
    onAccept: () -> Unit,
) {
    val image = remember(photo) { photo.asImageBitmap() }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val fitted = fittedRect(canvasSize, photo.width, photo.height)

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        Text(
            "Drag the corners around the signature",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 32.dp, bottom = 8.dp),
        )

        // The photo, with an adjustable box. Everything outside it is ignored, which is what
        // stops the rest of the page — other writing, printed rules — coming along too.
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(fitted) {
                    var corner = -1
                    detectDragGestures(
                        onDragStart = { p ->
                            val corners = areaCorners(fitted, area)
                            corner = corners.indices
                                .minByOrNull { (corners[it] - p).getDistance() }
                                ?.takeIf { (corners[it] - p).getDistance() < 140f } ?: -1
                        },
                        onDragEnd = { corner = -1 },
                        onDragCancel = { corner = -1 },
                    ) { change, _ ->
                        if (corner < 0 || fitted.width <= 0f) return@detectDragGestures
                        val nx = ((change.position.x - fitted.left) / fitted.width).coerceIn(0f, 1f)
                        val ny = ((change.position.y - fitted.top) / fitted.height).coerceIn(0f, 1f)
                        val min = 0.05f
                        onArea(
                            when (corner) {
                                0 -> area.copy(left = nx.coerceAtMost(area.right - min), top = ny.coerceAtMost(area.bottom - min))
                                1 -> area.copy(right = nx.coerceAtLeast(area.left + min), top = ny.coerceAtMost(area.bottom - min))
                                2 -> area.copy(right = nx.coerceAtLeast(area.left + min), bottom = ny.coerceAtLeast(area.top + min))
                                else -> area.copy(left = nx.coerceAtMost(area.right - min), bottom = ny.coerceAtLeast(area.top + min))
                            }
                        )
                    }
                }
        ) {
            canvasSize = size
            val r = fittedRect(size, photo.width, photo.height)
            drawImage(
                image = image,
                dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                dstSize = IntSize(r.width.toInt(), r.height.toInt()),
            )
            val box = Rect(
                r.left + area.left * r.width, r.top + area.top * r.height,
                r.left + area.right * r.width, r.top + area.bottom * r.height,
            )
            // Dim everything that will be thrown away.
            drawRect(Color(0xAA000000), topLeft = r.topLeft, size = Size(r.width, box.top - r.top))
            drawRect(Color(0xAA000000), topLeft = Offset(r.left, box.bottom), size = Size(r.width, r.bottom - box.bottom))
            drawRect(Color(0xAA000000), topLeft = Offset(r.left, box.top), size = Size(box.left - r.left, box.height))
            drawRect(Color(0xAA000000), topLeft = Offset(box.right, box.top), size = Size(r.right - box.right, box.height))

            drawRect(Color(0xFF55E39B), topLeft = box.topLeft, size = Size(box.width, box.height), style = Stroke(4f))
            areaCorners(r, area).forEach {
                drawCircle(Color.White, radius = 20f, center = it)
                drawCircle(Color(0xFF55E39B), radius = 20f, center = it, style = Stroke(4f))
            }
        }

        Text(
            "Result",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        // Shown on white, because that is what it will sit on in the PDF.
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            when {
                extracted != null -> Image(
                    bitmap = extracted.asImageBitmap(),
                    contentDescription = "Extracted signature",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )

                else -> CircularProgressIndicator()
            }
        }

        Text(
            "Ink sensitivity",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 14.dp),
        )
        Slider(value = sensitivity, onValueChange = onSensitivity, valueRange = 0f..1f)

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Retake") }
            Button(onClick = onAccept, enabled = extracted != null, modifier = Modifier.weight(1f)) {
                Text("Use signature")
            }
        }
    }
}

/** Corner handle positions, clockwise from top-left, in canvas pixels. */
private fun areaCorners(fitted: Rect, area: Rect): List<Offset> = listOf(
    Offset(fitted.left + area.left * fitted.width, fitted.top + area.top * fitted.height),
    Offset(fitted.left + area.right * fitted.width, fitted.top + area.top * fitted.height),
    Offset(fitted.left + area.right * fitted.width, fitted.top + area.bottom * fitted.height),
    Offset(fitted.left + area.left * fitted.width, fitted.top + area.bottom * fitted.height),
)
