package com.codecsrayo.presionbp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codecsrayo.presionbp.analysis.classify
import com.codecsrayo.presionbp.data.Reading
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val SYS_COLOR = Color(0xFFC62828)
private val DIA_COLOR = Color(0xFF1565C0)
private val PULSE_COLOR = Color(0xFF6D6D6D)

/** Línea de tiempo interactiva. [data]: mediciones con fecha fiable, ya filtradas por rango. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpChart(data: List<Reading>, modifier: Modifier = Modifier) {
    var showPulse by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Reading?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = showPulse, onClick = { showPulse = !showPulse }, label = { Text("Mostrar pulso") })
        }

        val sel = selected?.takeIf { s -> data.any { it.id == s.id } } ?: data.lastOrNull()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (sel == null) {
                    Text("Sin mediciones con fecha en este rango")
                } else {
                    Text(
                        sel.measuredAt.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${sel.systolic}/${sel.diastolic} mmHg", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${sel.pulse} lpm", style = MaterialTheme.typography.headlineSmall, color = PULSE_COLOR)
                    }
                    Text(classify(sel.systolic, sel.diastolic).label + if (sel.irregularHeartbeat) " · latido irregular" else "")
                }
            }
        }

        if (data.size < 2) {
            Text("Se necesitan al menos 2 mediciones con fecha para dibujar la gráfica.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        val minT = data.first().measuredAtEpoch
        val maxT = data.last().measuredAtEpoch.coerceAtLeast(minT + 1)
        val values = data.flatMap { listOfNotNull(it.systolic, it.diastolic, if (showPulse) it.pulse else null) }
        val minV = (minOf(values.min(), 60) / 10) * 10 - 10
        val maxV = ((maxOf(values.max(), 150) + 9) / 10) * 10 + 10

        fun nearest(x: Float, width: Float, left: Float): Reading {
            val t = minT + ((x - left) / (width - left)).coerceIn(0f, 1f) * (maxT - minT)
            return data.minBy { abs(it.measuredAtEpoch - t) }
        }

        val leftPad = 36f
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .pointerInput(data) { detectTapGestures { selected = nearest(it.x, size.width.toFloat(), leftPad) } }
                .pointerInput(data) {
                    detectDragGestures { change, _ -> selected = nearest(change.position.x, size.width.toFloat(), leftPad) }
                },
        ) {
            val w = size.width
            val h = size.height
            fun x(t: Long) = leftPad + (t - minT).toFloat() / (maxT - minT) * (w - leftPad)
            fun y(v: Int) = h - (v - minV).toFloat() / (maxV - minV) * h

            // Rejilla y referencias (120/80 y 140/90)
            for (v in minV..maxV step 20) {
                drawLine(Color.LightGray.copy(alpha = 0.5f), Offset(leftPad, y(v)), Offset(w, y(v)))
                drawLabel("$v", 0f, y(v))
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            listOf(120 to SYS_COLOR, 140 to SYS_COLOR, 80 to DIA_COLOR, 90 to DIA_COLOR).forEach { (v, c) ->
                drawLine(c.copy(alpha = 0.35f), Offset(leftPad, y(v)), Offset(w, y(v)), pathEffect = dash, strokeWidth = 2f)
            }

            fun series(sel: (Reading) -> Int, color: Color, dashed: Boolean = false) {
                val path = Path()
                data.forEachIndexed { i, r ->
                    val p = Offset(x(r.measuredAtEpoch), y(sel(r)))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, color, style = Stroke(width = 4f, pathEffect = if (dashed) dash else null))
                data.forEach { r -> drawCircle(color, radius = 6f, center = Offset(x(r.measuredAtEpoch), y(sel(r)))) }
            }
            series({ it.systolic }, SYS_COLOR)
            series({ it.diastolic }, DIA_COLOR)
            if (showPulse) series({ it.pulse }, PULSE_COLOR, dashed = true)

            sel?.let { s ->
                val sx = x(s.measuredAtEpoch)
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(sx, 0f), Offset(sx, h), strokeWidth = 2f)
                drawCircle(SYS_COLOR, radius = 11f, center = Offset(sx, y(s.systolic)))
                drawCircle(DIA_COLOR, radius = 11f, center = Offset(sx, y(s.diastolic)))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("● Sistólica", color = SYS_COLOR)
            Text("● Diastólica", color = DIA_COLOR)
            if (showPulse) Text("● Pulso", color = PULSE_COLOR)
        }
    }
}

private fun DrawScope.drawLabel(text: String, x: Float, y: Float) {
    drawContext.canvas.nativeCanvas.drawText(
        text, x, y - 4f,
        android.graphics.Paint().apply { textSize = 26f; color = android.graphics.Color.GRAY; isAntiAlias = true },
    )
}
