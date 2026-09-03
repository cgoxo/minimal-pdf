package com.minimal.pdfcreate.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat
import com.minimal.pdfcreate.AppContainer
import com.minimal.pdfcreate.data.Page
import com.minimal.pdfcreate.data.Quad
import com.minimal.pdfcreate.imaging.CaptureSaver
import com.minimal.pdfcreate.imaging.EdgeDetector
import com.minimal.pdfcreate.ui.common.PageThumbnail
import com.minimal.pdfcreate.ui.common.fittedRect
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live capture: CameraX Preview + ImageCapture + ImageAnalysis.
 *
 * The analysis use case runs [EdgeDetector] on the luma plane of every frame it is given
 * and the result is drawn as a green quad over the preview; the same quad is stored as the
 * page's crop when the shutter fires, so captures come out already de-skewed.
 */
@Composable
fun CameraScreen(docId: String, onDone: () -> Unit, onBack: () -> Unit) {
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

    var pages by remember { mutableStateOf(repo.get(docId)?.pages ?: emptyList()) }
    var detected by remember { mutableStateOf<Quad?>(null) }
    // Held only to ask whether there is a flash unit at all; a front camera or an emulator has
    // none, in which case the button is not offered.
    var camera by remember { mutableStateOf<Camera?>(null) }
    var flashOn by remember { mutableStateOf(false) }
    // Where the user last tapped to focus, in preview pixels, so the ring can be drawn there.
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var frameAspect by remember { mutableStateOf(3f / 4f) }
    var capturing by remember { mutableStateOf(false) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            captureExecutor.shutdown()
        }
    }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    val plane = image.planes[0]
                    val buffer: ByteBuffer = plane.buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val rotation = image.imageInfo.rotationDegrees
                    val quad = EdgeDetector.detect(bytes, image.width, image.height, plane.rowStride)
                    detected = quad?.let { EdgeDetector.rotateQuad(it, rotation) }
                    frameAspect = if (rotation % 180 == 0) {
                        image.width.toFloat() / image.height
                    } else {
                        image.height.toFloat() / image.width
                    }
                } catch (_: Throwable) {
                    detected = null
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis
            )
        }, ContextCompat.getMainExecutor(context))
    }

    // Same cleanup whichever way the user leaves: arrow, or the system back gesture.
    fun leave() {
        if (pages.isEmpty()) repo.delete(docId)
        onBack()
    }
    BackHandler { leave() }

    fun capture() {
        if (capturing) return
        capturing = true
        // Flash fires with the shutter and nothing else. A torch left burning while you line
        // the page up blinds the preview, cooks the battery, and is not what "flash" means.
        imageCapture.flashMode =
            if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        val quadAtCapture = detected
        imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val jpeg = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val rotation = image.imageInfo.rotationDegrees
                image.close()

                val file = repo.newImageFile(docId)
                val ok = CaptureSaver.save(jpeg, rotation, file)
                if (ok) {
                    // The document is born here, with its first page, so backing out of an
                    // empty camera session leaves nothing behind to tidy up.
                    val doc = repo.getOrNew(docId)
                    val updated = doc.copy(
                        pages = doc.pages + Page(imageName = file.name, crop = quadAtCapture)
                    )
                    repo.save(updated)
                    pages = updated.pages
                }
                capturing = false
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
            }
        })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    // Tap to focus. Continuous autofocus hunts on a flat page — there is
                    // little for it to lock onto until you tell it which part of the frame
                    // you actually mean — so pointing at the text is what makes it sharp.
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            val control = camera?.cameraControl ?: return@detectTapGestures
                            val point = previewView.meteringPointFactory
                                .createPoint(tap.x, tap.y)
                            control.startFocusAndMetering(
                                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                    .addPoint(point, FocusMeteringAction.FLAG_AE)
                                    // Then hand control back, so moving to the next page does
                                    // not stay locked on wherever the last one was.
                                    .setAutoCancelDuration(4, TimeUnit.SECONDS)
                                    .build()
                            )
                            focusAt = tap
                        }
                    },
            )

            // The ring fades itself out; a focus tap that leaves no mark feels like a tap the
            // app ignored.
            focusAt?.let { at ->
                LaunchedEffect(at) {
                    delay(900)
                    focusAt = null
                }
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color(0xFF55E39B), radius = 46f, center = at, style = Stroke(width = 3f))
                    drawCircle(Color(0x3355E39B), radius = 46f, center = at)
                }
            }

            // Detected page boundary, mapped into the letterboxed preview area.
            Canvas(Modifier.fillMaxSize()) {
                val quad = detected ?: return@Canvas
                val imgW = (frameAspect * 1000f).toInt().coerceAtLeast(1)
                val rect = fittedRect(size, imgW, 1000)
                val pts = quad.toList().map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, Color(0x3355E39B))
                drawPath(path, Color(0xFF55E39B), style = Stroke(width = 5f))
                pts.forEach { drawCircle(Color(0xFF55E39B), radius = 12f, center = it) }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
                }
            }
        }

        Icon(
            Icons.Default.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
                .clickable { leave() },
        )

        // Arms the flash for the next shutter press; the light itself only fires during the
        // capture, so framing stays under whatever light the room has.
        if (granted && camera?.cameraInfo?.hasFlashUnit() == true) {
            Icon(
                if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = if (flashOn) "Flash on" else "Flash off",
                tint = if (flashOn) Color(0xFF55E39B) else Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .clickable { flashOn = !flashOn },
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Bottom-left: stack of what has been scanned so far.
            Box(Modifier.size(56.dp, 72.dp)) {
                if (pages.isNotEmpty()) {
                    BadgedBox(badge = { Badge { Text(pages.size.toString()) } }) {
                        PageThumbnail(
                            docId = docId,
                            page = pages.last(),
                            modifier = Modifier
                                .size(48.dp, 64.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, Color.White, RoundedCornerShape(4.dp))
                                .clickable { onDone() },
                        )
                    }
                }
            }

            // Shutter.
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (capturing) Color.Gray else Color.White)
                    .clickable(enabled = granted && !capturing) { capture() }
                    .border(4.dp, Color(0xFF55E39B), CircleShape)
            )

            // Bottom-right: done.
            Box(Modifier.size(56.dp, 72.dp), contentAlignment = Alignment.Center) {
                FloatingActionButton(
                    onClick = { if (pages.isNotEmpty()) onDone() },
                    containerColor = if (pages.isEmpty()) Color.DarkGray else MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Done")
                }
            }
        }
    }
}
