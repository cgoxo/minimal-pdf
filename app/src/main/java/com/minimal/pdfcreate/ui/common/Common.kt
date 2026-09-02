package com.minimal.pdfcreate.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
    return Rect(Offset((canvas.width - w) / 2f, (canvas.height - h) / 2f), Size(w, h))
}

val SWATCHES = listOf(
    Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFE53935), Color(0xFFFB8C00),
    Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
)

private fun hsv(h: Float, s: Float, v: Float) =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

/**
 * HSV colour picker: preset swatches, a saturation/value square, and a hue slider.
 *
 * The square uses one `awaitEachGesture` loop — press, then drag — rather than separate tap
 * and drag detectors. Stacked detectors on the same node fight over the down event, which is
 * how the earlier version ended up feeling like it ignored you. Hue is a plain Material
 * `Slider` for the same reason: no custom gesture code to get wrong.
 */
@Composable
fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit,
) {
    val start = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val current = hsv(hue, sat, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick a colour") },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
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

                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            fun apply(p: Offset) {
                                sat = (p.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                apply(down.position)
                                down.consume()
                                drag(down.id) { change ->
                                    apply(change.position)
                                    change.consume()
                                }
                            }
                        }
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hsv(hue, 1f, 1f))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    // A proper cursor: the chosen colour in the middle so it stays visible
                    // against both ends of the square.
                    val centre = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(hsv(hue, sat, value), radius = 9.dp.toPx(), center = centre)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = centre, style = Stroke(2.5.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius = 11.dp.toPx(), center = centre, style = Stroke(1.dp.toPx()))
                }

                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    drawRect(
                        Brush.horizontalGradient(
                            (0..6).map { hsv(it * 60f, 1f, 1f) }
                        )
                    )
                }
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(current)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    )
                    Text(
                        "  #%06X".format(current.toArgb() and 0xFFFFFF),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    )
}

@Preview(name = "Colour picker", showBackground = true, widthDp = 380, heightDp = 620)
@Composable
private fun ColorPickerPreview() {
    com.minimal.pdfcreate.ui.theme.ScanlyTheme(dynamicColor = false) {
        ColorPickerDialog(initial = Color(0xFF1E88E5), onDismiss = {}, onPick = {})
    }
}
