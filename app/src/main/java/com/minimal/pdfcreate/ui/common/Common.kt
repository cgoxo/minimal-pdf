package com.minimal.pdfcreate.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Where a bitmap of [imgW] x [imgH] lands inside a box of [canvas], letterboxed (fit-centre). */
fun fittedRect(canvas: Size, imgW: Int, imgH: Int): Rect {
    if (imgW <= 0 || imgH <= 0 || canvas.width <= 0f || canvas.height <= 0f) {
        return Rect(Offset.Zero, canvas)
    }
    val scale = minOf(canvas.width / imgW, canvas.height / imgH)
    val w = imgW * scale
    val h = imgH * scale
    val left = (canvas.width - w) / 2f
    val top = (canvas.height - h) / 2f
    return Rect(Offset(left, top), Size(w, h))
}

fun Rect.toNormalised(point: Offset): Offset =
    Offset(((point.x - left) / width).coerceIn(0f, 1f), ((point.y - top) / height).coerceIn(0f, 1f))

fun Rect.fromNormalised(x: Float, y: Float): Offset = Offset(left + x * width, top + y * height)

fun Bitmap.aspect(): Float = if (height == 0) 1f else width.toFloat() / height

val SWATCHES = listOf(
    Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFE53935), Color(0xFFFB8C00),
    Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
)

/** Minimal HSV picker: hue strip on top, saturation/value square below, plus swatches. */
@Composable
fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit,
) {
    val hsv = remember {
        FloatArray(3).also {
            android.graphics.Color.colorToHSV(initial.toArgb(), it)
        }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick a colour") },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    SWATCHES.forEach { swatch ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable {
                                    val out = FloatArray(3)
                                    android.graphics.Color.colorToHSV(swatch.toArgb(), out)
                                    hue = out[0]; sat = out[1]; value = out[2]
                                }
                        )
                    }
                }

                // Saturation (x) / value (y) square for the current hue.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            fun update(p: Offset) {
                                sat = (p.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                            }
                            detectDragGestures(onDragStart = { update(it) }) { change, _ -> update(change.position) }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { p ->
                                sat = (p.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                        val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                        drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        drawCircle(
                            color = Color.White,
                            radius = 8f,
                            center = Offset(sat * size.width, (1f - value) * size.height),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                        )
                    }
                }

                // Hue strip.
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .padding(top = 12.dp)
                        .pointerInput(Unit) {
                            fun update(p: Offset) { hue = (p.x / size.width).coerceIn(0f, 1f) * 360f }
                            detectDragGestures(onDragStart = { update(it) }) { change, _ -> update(change.position) }
                        }
                ) {
                    val colors = (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
                    drawRect(Brush.horizontalGradient(colors))
                    drawLine(
                        color = Color.White,
                        start = Offset(hue / 360f * size.width, 0f),
                        end = Offset(hue / 360f * size.width, size.height),
                        strokeWidth = 4f
                    )
                }

                Box(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(current)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                )
            }
        }
    )
}

@Preview(name = "Colour picker", showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun ColorPickerPreview() {
    com.minimal.pdfcreate.ui.theme.MinimalPdfTheme(dynamicColor = false) {
        ColorPickerDialog(initial = Color(0xFF1E88E5), onDismiss = {}, onPick = {})
    }
}
