package com.minimal.pdfcreate.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.minimal.pdfcreate.AppContainer
import com.minimal.pdfcreate.data.FilterMode
import com.minimal.pdfcreate.data.FilterSettings
import com.minimal.pdfcreate.data.Overlay
import com.minimal.pdfcreate.imaging.PageRenderer
import com.minimal.pdfcreate.ui.common.ColorPickerDialog
import com.minimal.pdfcreate.ui.theme.MinimalPdfTheme
import java.io.File
import java.io.FileOutputStream

/** The bottom control surface; which controls appear depends on the selected [tab]. */
@Composable
fun EditorPanel(
    tab: EditorTab,
    filter: FilterSettings,
    onFilter: (FilterSettings) -> Unit,
    brushColor: Color,
    onBrushColor: (Color) -> Unit,
    brushWidth: Float,
    onBrushWidth: (Float) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onClearStrokes: () -> Unit,
    textColor: Color,
    onTextColor: (Color) -> Unit,
    textSize: Float,
    onTextSize: (Float) -> Unit,
    onAddText: (String) -> Unit,
    onAddSignature: (String) -> Unit,
    selected: Overlay?,
    onUpdateOverlay: (Overlay) -> Unit,
    onDeleteOverlay: (String) -> Unit,
    onAutoDetect: () -> Unit,
    onResetCrop: () -> Unit,
    onRotate: (Int) -> Unit,
) {
    when (tab) {
        EditorTab.FILTER -> FilterPanel(filter, onFilter)
        EditorTab.DRAW -> DrawPanel(brushColor, onBrushColor, brushWidth, onBrushWidth, canUndo, onUndo, onClearStrokes)
        EditorTab.TEXT -> TextPanel(textColor, onTextColor, textSize, onTextSize, onAddText, selected, onUpdateOverlay, onDeleteOverlay)
        EditorTab.SIGN -> SignPanel(onAddSignature, selected, onUpdateOverlay, onDeleteOverlay)
        EditorTab.CROP -> CropPanel(onAutoDetect, onResetCrop, onRotate)
    }
}

@Composable
private fun FilterPanel(filter: FilterSettings, onFilter: (FilterSettings) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterMode.entries.forEach { mode ->
                FilterChip(
                    selected = filter.mode == mode,
                    onClick = { onFilter(filter.copy(mode = mode)) },
                    label = {
                        Text(
                            when (mode) {
                                FilterMode.ORIGINAL -> "Original"
                                FilterMode.GRAYSCALE -> "Greyscale"
                                FilterMode.BW -> "B&W"
                                FilterMode.DOCUMENT -> "Document"
                            }
                        )
                    },
                )
            }
        }
        LabelledSlider("Brightness", filter.brightness, -0.5f..0.5f) { onFilter(filter.copy(brightness = it)) }
        LabelledSlider("Contrast", filter.contrast, 0.4f..2.5f) { onFilter(filter.copy(contrast = it)) }
        if (filter.mode == FilterMode.BW) {
            LabelledSlider("Threshold", filter.threshold, 0.1f..0.9f) { onFilter(filter.copy(threshold = it)) }
        } else if (filter.mode == FilterMode.ORIGINAL) {
            LabelledSlider("Saturation", filter.saturation, 0f..2f) { onFilter(filter.copy(saturation = it)) }
        }
    }
}

@Composable
private fun DrawPanel(
    brushColor: Color,
    onBrushColor: (Color) -> Unit,
    brushWidth: Float,
    onBrushWidth: (Float) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(brushColor)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { picking = true }
            )
            OutlinedButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.Default.Undo, null)
                Text("  Undo")
            }
            OutlinedButton(onClick = onClear, enabled = canUndo) { Text("Clear") }
        }
        LabelledSlider("Brush size", brushWidth, 0.002f..0.05f, onBrushWidth)
    }
    if (picking) {
        ColorPickerDialog(brushColor, onDismiss = { picking = false }) { onBrushColor(it); picking = false }
    }
}

@Composable
private fun TextPanel(
    textColor: Color,
    onTextColor: (Color) -> Unit,
    textSize: Float,
    onTextSize: (Float) -> Unit,
    onAddText: (String) -> Unit,
    selected: Overlay?,
    onUpdate: (Overlay) -> Unit,
    onDelete: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val selectedText = selected as? Overlay.Text

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { typing = true }) { Text("Add text") }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(selectedText?.let { Color(it.color.toInt()) } ?: textColor)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { picking = true }
            )
            if (selectedText != null) {
                OutlinedButton(onClick = { onDelete(selectedText.id) }) {
                    Icon(Icons.Default.Delete, null)
                    Text("  Remove")
                }
            }
        }
        LabelledSlider(
            label = if (selectedText != null) "Size (selected)" else "Size",
            value = selectedText?.sizeN ?: textSize,
            range = 0.02f..0.18f,
        ) { v ->
            if (selectedText != null) onUpdate(selectedText.copy(sizeN = v)) else onTextSize(v)
        }
        Text(
            "Tap a text box on the page to select it, then drag to move.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (picking) {
        ColorPickerDialog(selectedText?.let { Color(it.color.toInt()) } ?: textColor, onDismiss = { picking = false }) { c ->
            if (selectedText != null) {
                onUpdate(selectedText.copy(color = c.toArgb().toLong() and 0xFFFFFFFFL))
            } else {
                onTextColor(c)
            }
            picking = false
        }
    }
    if (typing) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("Add text") },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Text") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (value.isNotBlank()) onAddText(value)
                    typing = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SignPanel(
    onAddSignature: (String) -> Unit,
    selected: Overlay?,
    onUpdate: (Overlay) -> Unit,
    onDelete: (String) -> Unit,
) {
    // Reading the repository lazily (rather than into a val here) keeps this panel
    // renderable in the preview pane, where no app process — and so no repository — exists.
    val previewing = LocalInspectionMode.current
    var drawing by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var saved by remember {
        mutableStateOf(if (previewing) emptyList<File>() else AppContainer.repository.signatures())
    }
    val selectedSig = selected as? Overlay.Signature

    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { drawing = true }) {
                Icon(Icons.Default.Draw, null)
                Text("  Draw")
            }
            Button(onClick = { scanning = true }) {
                Icon(Icons.Default.PhotoCamera, null)
                Text("  Scan from paper")
            }
            if (selectedSig != null) {
                OutlinedButton(onClick = { onDelete(selectedSig.id) }) {
                    Icon(Icons.Default.Delete, null)
                    Text("  Remove")
                }
            }
        }
        if (saved.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                saved.forEach { file ->
                    val bmp = remember(file.path) { PageRenderer.decode(file, 200) }
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Saved signature",
                            modifier = Modifier
                                .size(width = 96.dp, height = 40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFEFEFEF))
                                .clickable { onAddSignature(file.name) },
                        )
                    }
                }
            }
        }
        if (selectedSig != null) {
            LabelledSlider("Signature width", selectedSig.widthN, 0.1f..0.9f) {
                onUpdate(selectedSig.copy(widthN = it))
            }
        } else if (saved.isNotEmpty()) {
            Text(
                "Tap a saved signature to place it, then drag it on the page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (drawing) {
        SignaturePadDialog(
            onDismiss = { drawing = false },
            onSave = { file ->
                saved = AppContainer.repository.signatures()
                onAddSignature(file.name)
                drawing = false
            },
        )
    }
    if (scanning) {
        SignatureScanDialog(
            onDismiss = { scanning = false },
            onSave = { file ->
                saved = AppContainer.repository.signatures()
                onAddSignature(file.name)
                scanning = false
            },
        )
    }
}

@Composable
private fun CropPanel(onAutoDetect: () -> Unit, onResetCrop: () -> Unit, onRotate: (Int) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAutoDetect) { Text("Auto detect") }
            OutlinedButton(onClick = onResetCrop) { Text("Full page") }
            OutlinedButton(onClick = { onRotate(-90) }) { Icon(Icons.Default.RotateLeft, "Rotate left") }
            OutlinedButton(onClick = { onRotate(90) }) { Icon(Icons.Default.RotateRight, "Rotate right") }
        }
        Text(
            "Drag the four corners to match the page edges.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            "$label  ${"%.2f".format(value)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

/** Freehand signature capture, saved as a transparent PNG for reuse on any page. */
@Composable
private fun SignaturePadDialog(onDismiss: () -> Unit, onSave: (File) -> Unit) {
    val repo = AppContainer.repository
    var paths by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasWidth by remember { mutableStateOf(1f) }
    var canvasHeight by remember { mutableStateOf(1f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign here") },
        text = {
            Column {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { live = listOf(it) },
                                onDragEnd = { paths = paths + listOf(live); live = emptyList() },
                                onDragCancel = { live = emptyList() },
                            ) { change, _ -> live = live + change.position }
                        }
                ) {
                    canvasWidth = size.width
                    canvasHeight = size.height
                    fun stroke(points: List<Offset>) {
                        for (i in 1 until points.size) {
                            drawLine(
                                Color.Black, points[i - 1], points[i], strokeWidth = 6f,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                        }
                    }
                    paths.forEach { stroke(it) }
                    stroke(live)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { paths = paths.dropLast(1) }, enabled = paths.isNotEmpty()) {
                        Icon(Icons.Default.Undo, null)
                        Text("  Undo")
                    }
                    OutlinedButton(onClick = { paths = emptyList() }) { Text("Clear") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = paths.isNotEmpty(),
                onClick = {
                    val file = repo.newSignatureFile()
                    writeSignature(paths, canvasWidth, canvasHeight, file)
                    onSave(file)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Rasterises the captured strokes onto a transparent bitmap at a printable size. */
private fun writeSignature(paths: List<List<Offset>>, srcW: Float, srcH: Float, target: File) {
    val outW = 1000
    val outH = (outW * (srcH / srcW)).toInt().coerceAtLeast(100)
    val scale = outW / srcW
    val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = AndroidPaint.Style.STROKE
        strokeWidth = 6f * scale
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
    }
    paths.forEach { points ->
        if (points.size < 2) return@forEach
        val path = AndroidPath()
        path.moveTo(points[0].x * scale, points[0].y * scale)
        points.drop(1).forEach { path.lineTo(it.x * scale, it.y * scale) }
        canvas.drawPath(path, paint)
    }
    FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    bitmap.recycle()
}

// ---------------------------------------------------------------------------------------
// Previews of each control surface. Every panel here is "dumb": it receives values and emits
// callbacks, holding no state of its own beyond which dialog is open — which is exactly what
// makes it possible to render one from a handful of literals.
// ---------------------------------------------------------------------------------------

@Composable
private fun PanelPreview(content: @Composable () -> Unit) {
    MinimalPdfTheme(dynamicColor = false) {
        Surface(tonalElevation = 2.dp) {
            Box(Modifier.padding(12.dp)) { content() }
        }
    }
}

@Preview(name = "Panel · Filter", showBackground = true, widthDp = 400)
@Composable
private fun FilterPanelPreview() = PanelPreview {
    FilterPanel(FilterSettings(mode = FilterMode.DOCUMENT, contrast = 1.4f, brightness = 0.1f)) {}
}

@Preview(name = "Panel · Filter (B&W)", showBackground = true, widthDp = 400)
@Composable
private fun FilterPanelBwPreview() = PanelPreview {
    FilterPanel(FilterSettings(mode = FilterMode.BW, threshold = 0.55f)) {}
}

@Preview(name = "Panel · Draw", showBackground = true, widthDp = 400)
@Composable
private fun DrawPanelPreview() = PanelPreview {
    DrawPanel(
        brushColor = Color(0xFFE53935),
        onBrushColor = {},
        brushWidth = 0.012f,
        onBrushWidth = {},
        canUndo = true,
        onUndo = {},
        onClear = {},
    )
}

@Preview(name = "Panel · Text", showBackground = true, widthDp = 400)
@Composable
private fun TextPanelPreview() = PanelPreview {
    TextPanel(
        textColor = Color(0xFF1E88E5),
        onTextColor = {},
        textSize = 0.05f,
        onTextSize = {},
        onAddText = {},
        selected = null,
        onUpdate = {},
        onDelete = {},
    )
}

@Preview(name = "Panel · Sign", showBackground = true, widthDp = 400)
@Composable
private fun SignPanelPreview() = PanelPreview {
    SignPanel(onAddSignature = {}, selected = null, onUpdate = {}, onDelete = {})
}

@Preview(name = "Panel · Crop", showBackground = true, widthDp = 400)
@Composable
private fun CropPanelPreview() = PanelPreview {
    CropPanel(onAutoDetect = {}, onResetCrop = {}, onRotate = {})
}
