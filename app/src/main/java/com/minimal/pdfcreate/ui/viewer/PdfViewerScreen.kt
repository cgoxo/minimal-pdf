package com.minimal.pdfcreate.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.minimal.pdfcreate.AppContainer
import com.minimal.pdfcreate.data.PdfStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-app viewer. `PdfRenderer` is a platform class — no external viewer, no Drive hand-off.
 *
 * It can only have one page open at a time and is not thread safe, so all access goes
 * through a [Mutex] and pages are rasterised lazily as they scroll into view.
 */
private class PdfSource(uri: Uri, context: android.content.Context) : AutoCloseable {
    private val descriptor: ParcelFileDescriptor? =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
    private val renderer: PdfRenderer? = descriptor?.let { runCatching { PdfRenderer(it) }.getOrNull() }
    private val lock = Mutex()

    val pageCount: Int get() = renderer?.pageCount ?: 0

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        val r = renderer ?: return null
        withContext(Dispatchers.Default) {
            runCatching {
                r.openPage(index).use { page ->
                    val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.getOrNull()
        }
    }

    override fun close() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(docId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = AppContainer.repository
    val doc = remember(docId) { repo.get(docId) }
    val uri = remember(docId) { doc?.pdfFileName?.let { PdfStore.findByName(context, it) } }

    val source = remember(uri) { uri?.let { PdfSource(it, context) } }
    DisposableEffect(source) { onDispose { source?.close() } }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Rasterise to the pixels actually on screen, not a fixed guess. Zoom is quantised into
    // three tiers so pinching re-renders once per tier instead of on every frame.
    val windowWidthPx = LocalWindowInfo.current.containerSize.width.coerceAtLeast(720)
    val qualityTier = when {
        scale < 1.5f -> 1.5f
        scale < 3f -> 2.5f
        else -> 3.5f
    }
    val renderWidthPx = (windowWidthPx * qualityTier).toInt().coerceIn(1080, 2800)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.name ?: "PDF") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    if (uri != null) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share PDF"))
                        }) { Icon(Icons.Default.Share, "Share") }
                    }
                },
            )
        }
    ) { padding ->
        if (source == null || source.pageCount == 0) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (doc?.pages.isNullOrEmpty()) {
                        "This document has no pages.\nAdd pages and save it again."
                    } else {
                        "Could not open this PDF."
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
                start = 12.dp, end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF3A3A3A))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale <= 1.01f) Offset.Zero else offset + pan
                    }
                }
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        ) {
            items(source.pageCount) { index ->
                PdfPage(source, index, renderWidthPx)
            }
        }
    }
}

@Composable
private fun PdfPage(source: PdfSource, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, source, index, widthPx) {
        value = source.render(index, widthPx)
    }
    Box(
        Modifier.fillMaxWidth().background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp == null) {
            Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
