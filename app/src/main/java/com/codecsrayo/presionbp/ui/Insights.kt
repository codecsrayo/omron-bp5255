package com.codecsrayo.presionbp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.codecsrayo.presionbp.analysis.Bucket
import com.codecsrayo.presionbp.analysis.Category
import com.codecsrayo.presionbp.analysis.Stats
import com.codecsrayo.presionbp.analysis.classify
import com.codecsrayo.presionbp.data.Reading
import com.codecsrayo.presionbp.data.toSample
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.hypot

private val SYS = Color(0xFFC62828)
private val DIA = Color(0xFF1565C0)

fun Category.color(): Color = when (this) {
    Category.LOW -> Color(0xFF42A5F5)
    Category.NORMAL -> Color(0xFF43A047)
    Category.ELEVATED -> Color(0xFFFDD835)
    Category.STAGE1 -> Color(0xFFFB8C00)
    Category.STAGE2 -> Color(0xFFE53935)
    Category.CRISIS -> Color(0xFF8E0000)
}

private enum class Range(val label: String, val days: Long?) { D7("7 días", 7), D30("30 días", 30), D90("90 días", 90), ALL("Todo", null) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartsScreen(readings: List<Reading>, modifier: Modifier = Modifier) {
    var range by rememberSaveable { mutableStateOf(Range.D30) }
    val now = Instant.now().epochSecond
    val trusted = readings.filter { !it.clockUnset }.sortedBy { it.measuredAtEpoch }
    val data = range.days?.let { d -> trusted.filter { now - it.measuredAtEpoch <= d * 86_400 } } ?: trusted
    val samples = data.map { it.toSample() }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Range.entries.forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) }) }
        }
        val hidden = readings.count { it.clockUnset }
        if (hidden > 0) {
            Text("$hidden mediciones sin fecha fiable (reloj del tensiómetro sin configurar) no se incluyen.", style = MaterialTheme.typography.bodySmall)
        }
        if (samples.isEmpty()) {
            Text("No hay mediciones con fecha en este rango.")
            return@Column
        }

        Section("Resumen", "Promedios del periodo y cuánto varía tu presión.") { SummaryTiles(samples) }
        Section("¿En qué rango estás?", "Proporción de mediciones en cada categoría (guía ACC/AHA).") { CategoryBar(samples) }
        Section("Momento del día", "Promedio según la hora. Diferencias grandes entre mañana y noche son útiles para tu médico.") {
            DayPartChart(Stats.byDayPart(samples))
        }
        val weeks = Stats.weekly(samples)
        if (weeks.size >= 2) {
            Section("Evolución semanal", "Barra: rango de la sistólica en la semana. Puntos: promedios. Toca una semana.") {
                WeeklyChart(weeks.map { it.second })
            }
        }
        Section("Mapa de presión", "Cada punto es una medición sobre las zonas de riesgo. Toca un punto.") { ScatterZones(data) }
        Section("Línea de tiempo", "Toca o arrastra para ver cada medición.") { BpChart(data) }
    }
}

@Composable
private fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryTiles(samples: List<com.codecsrayo.presionbp.analysis.Sample>) {
    val s = Stats.summary(samples) ?: return
    val avgCat = classify(s.avgSys, s.avgDia)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("Promedio", "${s.avgSys}/${s.avgDia}", avgCat.label, avgCat.color())
        Tile("Pulso medio", "${s.avgPulse}", "lpm")
        Tile("En objetivo", "${s.inTargetPct}%", "< 130/80")
        Tile("Sistólica", "${s.minSys}–${s.maxSys}", "mín–máx")
        Tile("Variabilidad", "±${s.sdSys}", if (s.sdSys > 15) "alta" else "mmHg")
        Tile("Presión de pulso", "${s.pulsePressure}", if (s.pulsePressure > 60) "elevada" else "mmHg")
        Tile("Mediciones", "${s.count}", "en el periodo")
    }
}

@Composable
private fun Tile(title: String, value: String, caption: String, accent: Color? = null) {
    Card(Modifier.width(150.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent ?: Color.Unspecified)
            Text(caption, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryBar(samples: List<com.codecsrayo.presionbp.analysis.Sample>) {
    val cats = Stats.categories(samples)
    val total = samples.size.toFloat()
    Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp))) {
        cats.filter { it.second > 0 }.forEach { (c, n) ->
            Box(Modifier.weight(n / total).height(28.dp).background(c.color()))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cats.filter { it.second > 0 }.forEach { (c, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c.color()))
                Text(" ${c.label} ${(n * 100 / total).toInt()}% ($n)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DayPartChart(parts: List<Bucket>) {
    val withData = parts.filter { it.count > 0 }
    val maxV = (withData.maxOfOrNull { it.avgSys } ?: 150).coerceAtLeast(150) + 10
    Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        parts.forEach { b ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom, modifier = Modifier.weight(1f)) {
                if (b.count == 0) {
                    Text("—", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${b.avgSys}/${b.avgDia}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(18.dp).height((140f * b.avgSys / maxV).dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(SYS))
                        Box(Modifier.width(18.dp).height((140f * b.avgDia / maxV).dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(DIA))
                    }
                }
                Text(b.label, style = MaterialTheme.typography.labelSmall)
                Text("n=${b.count}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WeeklyChart(weeks: List<Bucket>) {
    var selected by remember(weeks) { mutableStateOf(weeks.lastIndex) }
    val w = weeks[selected.coerceIn(0, weeks.lastIndex)]
    Text("Semana del ${w.label}: ${w.avgSys}/${w.avgDia} promedio · sistólica ${w.minSys}–${w.maxSys} · ${w.count} mediciones")
    val minV = (weeks.minOf { it.avgDia } / 10) * 10 - 10
    val maxV = ((weeks.maxOf { it.maxSys } + 9) / 10) * 10 + 10
    Canvas(
        Modifier.fillMaxWidth().height(220.dp).pointerInput(weeks) {
            detectTapGestures { p -> selected = ((p.x / size.width) * weeks.size).toInt().coerceIn(0, weeks.lastIndex) }
        },
    ) {
        val slot = size.width / weeks.size
        fun y(v: Int) = size.height - (v - minV).toFloat() / (maxV - minV) * size.height
        listOf(130 to SYS, 80 to DIA).forEach { (v, c) ->
            drawLine(c.copy(alpha = 0.3f), Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 2f)
        }
        weeks.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2
            if (i == selected) drawRect(Color.Gray.copy(alpha = 0.12f), Offset(slot * i, 0f), Size(slot, size.height))
            drawLine(SYS.copy(alpha = 0.35f), Offset(cx, y(b.maxSys)), Offset(cx, y(b.minSys)), strokeWidth = slot * 0.35f)
            drawCircle(SYS, 9f, Offset(cx, y(b.avgSys)))
            drawCircle(DIA, 9f, Offset(cx, y(b.avgDia)))
            if (weeks.size <= 12 || i % 2 == 0) label(b.label, cx - 20f, size.height - 2f)
        }
    }
}

@Composable
private fun ScatterZones(data: List<Reading>) {
    var selected by remember(data) { mutableStateOf<Reading?>(null) }
    selected?.let {
        Text(
            "${it.measuredAt.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))}: ${it.systolic}/${it.diastolic} · ${classify(it.systolic, it.diastolic).label}",
            fontWeight = FontWeight.Bold,
        )
    }
    // Eje X: diastólica 40–130; eje Y: sistólica 70–200.
    val dx = 40..130
    val sy = 70..200
    Canvas(
        Modifier.fillMaxWidth().height(300.dp).pointerInput(data) {
            detectTapGestures { p ->
                fun px(d: Int) = (d - dx.first).toFloat() / (dx.last - dx.first) * size.width
                fun py(s: Int) = size.height - (s - sy.first).toFloat() / (sy.last - sy.first) * size.height
                selected = data.minByOrNull { hypot(px(it.diastolic) - p.x, py(it.systolic) - p.y) }
                    ?.takeIf { hypot(px(it.diastolic) - p.x, py(it.systolic) - p.y) < 60 }
            }
        },
    ) {
        fun x(d: Int) = (d.coerceIn(dx) - dx.first).toFloat() / (dx.last - dx.first) * size.width
        fun y(s: Int) = size.height - (s.coerceIn(sy) - sy.first).toFloat() / (sy.last - sy.first) * size.height
        // Zonas de mayor a menor: cada una se pinta encima de la anterior.
        fun zone(maxDia: Int, maxSys: Int, c: Category) = drawRect(
            c.color().copy(alpha = 0.22f), Offset(0f, y(maxSys)), Size(x(maxDia), size.height - y(maxSys)),
        )
        drawRect(Category.CRISIS.color().copy(alpha = 0.22f), Offset.Zero, size)
        zone(120, 180, Category.STAGE2)
        zone(90, 140, Category.STAGE1)
        zone(80, 130, Category.ELEVATED)
        zone(80, 120, Category.NORMAL)
        // Baja = (sist < 90 o diast < 60) sin llegar a grado 1: dos franjas.
        val low = Category.LOW.color().copy(alpha = 0.35f)
        drawRect(low, Offset(0f, y(130)), Size(x(60), size.height - y(130)))
        drawRect(low, Offset(0f, y(90)), Size(x(80), size.height - y(90)))
        for (v in 80..200 step 20) label("$v", 4f, y(v))
        for (v in 50..130 step 20) label("$v", x(v) - 12f, size.height - 4f)
        data.forEach { r ->
            val c = classify(r.systolic, r.diastolic).color()
            drawCircle(Color.White, 10f, Offset(x(r.diastolic), y(r.systolic)))
            drawCircle(c, 7f, Offset(x(r.diastolic), y(r.systolic)))
        }
        selected?.let { drawCircle(Color.Black, 14f, Offset(x(it.diastolic), y(it.systolic)), style = androidx.compose.ui.graphics.drawscope.Stroke(3f)) }
    }
    Text("Eje vertical: sistólica · eje horizontal: diastólica", style = MaterialTheme.typography.bodySmall)
}

private fun DrawScope.label(text: String, x: Float, y: Float) {
    drawContext.canvas.nativeCanvas.drawText(
        text, x, y - 4f,
        android.graphics.Paint().apply { textSize = 26f; color = android.graphics.Color.DKGRAY; isAntiAlias = true },
    )
}
