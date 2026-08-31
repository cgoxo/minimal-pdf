package com.minimal.pdfcreate.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.minimal.pdfcreate.AppContainer
import com.minimal.pdfcreate.data.DocumentRepository
import com.minimal.pdfcreate.data.FilterMode
import com.minimal.pdfcreate.data.Overlay
import com.minimal.pdfcreate.data.Page
import com.minimal.pdfcreate.data.PointN
import com.minimal.pdfcreate.data.Quad
import com.minimal.pdfcreate.data.Stroke
import com.minimal.pdfcreate.imaging.EdgeDetector
import com.minimal.pdfcreate.imaging.Filters
import com.minimal.pdfcreate.imaging.PageRenderer
import com.minimal.pdfcreate.ui.common.fittedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

enum class EditorTab(val label: String) { FILTER("Filter"), DRAW("Draw"), TEXT("Text"), SIGN("Sign"), CROP("Crop") }

/**
 * Non-destructive page editor. Every tab mutates one small piece of [Page] state; the
 * source JPEG is never touched, so any page — freshly scanned, or from a document saved
 * weeks ago — can be reopened with all previous adjustments still live and adjustable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(docId: String, pageId: String, onBack: () -> Unit) {
    val repo = AppContainer.repository
    val original = remember(docId, pageId) { repo.get(docId)?.page(pageId) }

    if (original == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Page not found") }
        return
    }

    var tab by remember { mutableStateOf(EditorTab.FILTER) }
    var filter by remember { mutableStateOf(original.filter) }
    var strokes by remember { mutableStateOf(original.strokes) }
    var overlays by remember { mutableStateOf(original.overlays) }
    var crop by remember { mutableStateOf(original.crop) }
    var rotation by remember { mutableIntStateOf(original.rotation) }
    var selectedOverlay by remember { mutableStateOf<String?>(null) }

    var brushColor by remember { mutableStateOf(Color(0xFFE53935)) }
    var brushWidth by remember { mutableFloatStateOf(0.008f) }
    var textColor by remember { mutableStateOf(Color(0xFF1E88E5)) }
    var textSize by remember { mutableFloatStateOf(0.05f) }
    var liveStroke by remember { mutableStateOf<List<PointN>>(emptyList()) }

    fun currentPage(): Page = original.copy(
        crop = crop, rotation = rotation, filter = filter, strokes = strokes, overlays = overlays
    )

    fun save() {
        val doc = repo.get(docId) ?: return
        repo.save(doc.replacePage(currentPage()))
    }

    // The raw capture: the crop tab must place corners on the un-cropped frame.
    val sourceBitmap by produceState<Bitmap?>(initialValue = null, docId, pageId) {
        value = withContext(Dispatchers.Default) {
            PageRenderer.decode(repo.imageFile(docId, original.imageName), PageRenderer.EDIT_DIM)
        }
    }

    // Cropped + rotated, before tone. Recomputed only when the geometry changes.
    val baseBitmap by produceState<Bitmap?>(initialValue = null, docId, pageId, crop, rotation) {
        value = withContext(Dispatchers.Default) {
            PageRenderer.base(repo, docId, original.copy(crop = crop, rotation = rotation), PageRenderer.EDIT_DIM)
        }
    }

    // B&W / Document need per-pixel work, so they run off the main thread. The tone-only
    // modes are previewed for free by handing a ColorFilter to drawImage.
    val pixelFiltered by produceState<Bitmap?>(initialValue = null, baseBitmap, filter) {
        val base = baseBitmap
        value = if (base == null || filter.mode == FilterMode.ORIGINAL || filter.mode == FilterMode.GRAYSCALE) {
            null
        } else {
            withContext(Dispatchers.Default) { runCatching { Filters.apply(base, filter) }.getOrNull() }
        }
    }

    val showCrop = tab == EditorTab.CROP
    val displayed = when {
        showCrop -> sourceBitmap
        pixelFiltered != null -> pixelFiltered
        else -> baseBitmap
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit page") },
                navigationIcon = { IconButton(onClick = { save(); onBack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    if (tab == EditorTab.DRAW) {
                        IconButton(
                            onClick = { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) },
                            enabled = strokes.isNotEmpty(),
                        ) { Icon(Icons.Default.Undo, "Undo") }
                    }
                    IconButton(onClick = { save(); onBack() }) { Icon(Icons.Default.Check, "Done") }
                },
            )
        },
        bottomBar = {
            Column {
                Surface(tonalElevation = 2.dp) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        EditorPanel(
                            tab = tab,
                            filter = filter,
                            onFilter = { filter = it },
                            brushColor = brushColor,
                            onBrushColor = { brushColor = it },
                            brushWidth = brushWidth,
                            onBrushWidth = { brushWidth = it },
                            canUndo = strokes.isNotEmpty(),
                            onUndo = { strokes = strokes.dropLast(1) },
                            onClearStrokes = { strokes = emptyList() },
                            textColor = textColor,
                            onTextColor = { textColor = it },
                            textSize = textSize,
                            onTextSize = { textSize = it },
                            onAddText = { text ->
                                val overlay = Overlay.Text(
                                    text = text,
                                    posN = PointN(0.15f, 0.25f),
                                    sizeN = textSize,
                                    color = textColor.toArgb().toLong() and 0xFFFFFFFFL,
                                )
                                overlays = overlays + overlay
                                selectedOverlay = overlay.id
                            },
                            onAddSignature = { fileName ->
                                val overlay = Overlay.Signature(
                                    fileName = fileName, posN = PointN(0.2f, 0.6f), widthN = 0.35f
                                )
                                overlays = overlays + overlay
                                selectedOverlay = overlay.id
                            },
                            selected = overlays.firstOrNull { it.id == selectedOverlay },
                            onUpdateOverlay = { updated ->
                                overlays = overlays.map { if (it.id == updated.id) updated else it }
                            },
                            onDeleteOverlay = { id ->
                                overlays = overlays.filterNot { it.id == id }
                                selectedOverlay = null
                            },
                            onAutoDetect = { sourceBitmap?.let { crop = EdgeDetector.detect(it) ?: Quad.FULL } },
                            onResetCrop = { crop = Quad.FULL },
                            onRotate = { delta -> rotation = ((rotation + delta) % 360 + 360) % 360 },
                        )
                    }
                }
                NavigationBar {
                    EditorTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry; selectedOverlay = null },
                            icon = {
                                Icon(
                                    when (entry) {
                                        EditorTab.FILTER -> Icons.Default.Tune
                                        EditorTab.DRAW -> Icons.Default.Brush
                                        EditorTab.TEXT -> Icons.Default.TextFields
                                        EditorTab.SIGN -> Icons.Default.Draw
                                        EditorTab.CROP -> Icons.Default.Crop
                                    },
                                    contentDescription = entry.label,
                                )
                            },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = displayed
            if (bitmap == null) {
                CircularProgressIndicator()
                return@Box
            }
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            var canvasSize by remember { mutableStateOf(Size.Zero) }
            val rect = fittedRect(canvasSize, bitmap.width, bitmap.height)

            val gestures = when (tab) {
                EditorTab.DRAW -> Modifier.pointerInput(brushColor, brushWidth, rect) {
                    detectDragGestures(
                        onDragStart = { p -> liveStroke = listOf(rect.normalise(p)) },
                        onDragEnd = {
                            if (liveStroke.size > 1) {
                                strokes = strokes + Stroke(
                                    color = brushColor.toArgb().toLong() and 0xFFFFFFFFL,
                                    widthN = brushWidth,
                                    points = liveStroke,
                                )
                            }
                            liveStroke = emptyList()
                        },
                        onDragCancel = { liveStroke = emptyList() },
                    ) { change, _ -> liveStroke = liveStroke + rect.normalise(change.position) }
                }

                EditorTab.TEXT, EditorTab.SIGN -> Modifier
                    .pointerInput(overlays, rect) {
                        detectTapGestures { p ->
                            val n = rect.normalise(p)
                            selectedOverlay = overlays
                                .minByOrNull { abs(it.posN.x - n.x) + abs(it.posN.y - n.y) }
                                ?.takeIf { abs(it.posN.x - n.x) < 0.3f && abs(it.posN.y - n.y) < 0.3f }
                                ?.id
                        }
                    }
                    .pointerInput(selectedOverlay, rect) {
                        detectDragGestures { change, drag ->
                            val id = selectedOverlay ?: return@detectDragGestures
                            change.consume()
                            overlays = overlays.map { o ->
                                if (o.id != id) o else {
                                    val np = PointN(
                                        (o.posN.x + drag.x / rect.width).coerceIn(0f, 1f),
                                        (o.posN.y + drag.y / rect.height).coerceIn(0f, 1f),
                                    )
                                    when (o) {
                                        is Overlay.Text -> o.copy(posN = np)
                                        is Overlay.Signature -> o.copy(posN = np)
                                    }
                                }
                            }
                        }
                    }

                EditorTab.CROP -> Modifier.pointerInput(rect) {
                    var dragging = -1
                    detectDragGestures(
                        onDragStart = { p ->
                            val corners = (crop ?: Quad.FULL).toList().map { rect.denormalise(it) }
                            dragging = corners.indices
                                .minByOrNull { (corners[it] - p).getDistance() }
                                ?.takeIf { (corners[it] - p).getDistance() < 140f } ?: -1
                        },
                        onDragEnd = { dragging = -1 },
                        onDragCancel = { dragging = -1 },
                    ) { change, _ ->
                        if (dragging < 0) return@detectDragGestures
                        val corners = (crop ?: Quad.FULL).toList().toMutableList()
                        corners[dragging] = rect.normalise(change.position)
                        crop = Quad.fromList(corners)
                    }
                }

                else -> Modifier
            }

            Canvas(Modifier.fillMaxSize().then(gestures)) {
                canvasSize = size
                val r = fittedRect(size, bitmap.width, bitmap.height)
                val tone = if (pixelFiltered == null && !showCrop) {
                    ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix(Filters.matrixValues(filter))
                    )
                } else {
                    null
                }

                drawImage(
                    image = image,
                    dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                    dstSize = IntSize(r.width.toInt(), r.height.toInt()),
                    colorFilter = tone,
                )

                if (showCrop) {
                    drawCropQuad(r, crop ?: Quad.FULL)
                } else {
                    drawStrokes(r, strokes, liveStroke, brushColor, brushWidth)
                    drawOverlays(r, overlays, selectedOverlay, repo)
                }
            }
        }
    }

    // Persist as the user works, so leaving via the system back gesture never loses edits.
    // The delay debounces slider drags — each key change cancels the pending write.
    LaunchedEffect(strokes, overlays, filter, crop, rotation) {
        kotlinx.coroutines.delay(400)
        withContext(Dispatchers.IO) { save() }
    }
}

private fun Rect.normalise(p: Offset) = PointN(
    ((p.x - left) / width).coerceIn(0f, 1f),
    ((p.y - top) / height).coerceIn(0f, 1f),
)

private fun Rect.denormalise(p: PointN) = Offset(left + p.x * width, top + p.y * height)

private fun DrawScope.drawStrokes(
    rect: Rect,
    strokes: List<Stroke>,
    live: List<PointN>,
    liveColor: Color,
    liveWidth: Float,
) {
    fun draw(points: List<PointN>, color: Color, widthN: Float) {
        if (points.isEmpty()) return
        val path = Path()
        val first = rect.denormalise(points.first())
        path.moveTo(first.x, first.y)
        points.drop(1).forEach { val o = rect.denormalise(it); path.lineTo(o.x, o.y) }
        drawPath(
            path, color,
            style = StrokeStyle(width = widthN * rect.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
    strokes.forEach { draw(it.points, Color(it.color.toInt()), it.widthN) }
    draw(live, liveColor, liveWidth)
}

private fun DrawScope.drawOverlays(
    rect: Rect,
    overlays: List<Overlay>,
    selectedId: String?,
    repo: DocumentRepository,
) {
    val selectionStyle = StrokeStyle(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
    overlays.forEach { overlay ->
        val origin = rect.denormalise(overlay.posN)
        when (overlay) {
            is Overlay.Text -> {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = overlay.color.toInt()
                    textSize = overlay.sizeN * rect.height
                }
                val lines = overlay.text.split("\n")
                drawIntoCanvas { canvas ->
                    var y = origin.y
                    lines.forEach { line ->
                        canvas.nativeCanvas.drawText(line, origin.x, y, paint)
                        y += paint.textSize * 1.2f
                    }
                }
                if (overlay.id == selectedId) {
                    val widest = lines.maxOf { paint.measureText(it) }
                    drawRect(
                        color = Color(0xFF55E39B),
                        topLeft = Offset(origin.x - 8f, origin.y - paint.textSize - 8f),
                        size = Size(widest + 16f, paint.textSize * (0.4f + 1.2f * lines.size) + 16f),
                        style = selectionStyle,
                    )
                }
            }

            is Overlay.Signature -> {
                val bmp = PageRenderer.decode(File(repo.signaturesDir, overlay.fileName), 600) ?: return@forEach
                val targetW = overlay.widthN * rect.width
                val targetH = targetW * bmp.height / bmp.width
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstOffset = IntOffset(origin.x.toInt(), origin.y.toInt()),
                    dstSize = IntSize(targetW.toInt(), targetH.toInt()),
                )
                if (overlay.id == selectedId) {
                    drawRect(Color(0xFF55E39B), origin, Size(targetW, targetH), style = selectionStyle)
                }
            }
        }
    }
}

private fun DrawScope.drawCropQuad(rect: Rect, quad: Quad) {
    val pts = quad.toList().map { rect.denormalise(it) }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, Color(0x2255E39B))
    drawPath(path, Color(0xFF55E39B), style = StrokeStyle(4f))
    pts.forEach {
        drawCircle(Color.White, radius = 22f, center = it)
        drawCircle(Color(0xFF55E39B), radius = 22f, center = it, style = StrokeStyle(4f))
    }
}
