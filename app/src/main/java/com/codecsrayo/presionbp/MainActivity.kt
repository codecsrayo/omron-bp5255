package com.codecsrayo.presionbp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codecsrayo.presionbp.analysis.Finding
import com.codecsrayo.presionbp.analysis.Level
import com.codecsrayo.presionbp.analysis.classify
import com.codecsrayo.presionbp.data.Reading
import com.codecsrayo.presionbp.export.CsvExporter
import com.codecsrayo.presionbp.ui.ChartsScreen
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(vm)
            }
        }
    }
}

private enum class Tab(val label: String) { HOME("Inicio"), CHART("Gráfica"), HISTORY("Historial"), SETTINGS("Ajustes") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val readings by vm.readings.collectAsStateWithLifecycle()
    val findings by vm.findings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        perms.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
    }

    ui.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("Aceptar") } },
            title = { Text("No se pudo completar") },
            text = { Text(msg) },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("BP") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val icon = when (t) {
                        Tab.HOME -> Icons.Filled.Home
                        Tab.CHART -> Icons.Filled.ShowChart
                        Tab.HISTORY -> Icons.Filled.List
                        Tab.SETTINGS -> Icons.Filled.Settings
                    }
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(icon, null) }, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad).padding(16.dp)
        when (tab) {
            Tab.HOME -> HomeScreen(ui, readings, findings, vm, m)
            Tab.CHART -> ChartsScreen(readings, m)
            Tab.HISTORY -> HistoryScreen(readings, vm::deleteReading, m)
            Tab.SETTINGS -> SettingsScreen(ui, vm, m)
        }
    }
}

@Composable
private fun HomeScreen(ui: UiState, readings: List<Reading>, findings: List<Finding>, vm: MainViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.deviceAddress == null) {
            Text("Pon el tensiómetro en modo \"P\" (mantén el botón de Bluetooth hasta que parpadee) y pulsa Emparejar.")
            Button(onClick = vm::pair, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bluetooth, null); Spacer(Modifier.size(8.dp)); Text("Emparejar")
            }
        } else {
            Text("Mide o pulsa el botón de Bluetooth del tensiómetro y luego Sincronizar.")
            Button(onClick = vm::sync, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bluetooth, null); Spacer(Modifier.size(8.dp)); Text("Sincronizar")
            }
        }
        if (ui.busy || ui.status != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(ui.status.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        readings.firstOrNull { !it.clockUnset }?.let { last ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Última medición · " + last.measuredAt.format(DateTimeFormatter.ofPattern("d MMM, HH:mm")), style = MaterialTheme.typography.labelLarge)
                    Text("${last.systolic}/${last.diastolic}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    Text("mmHg · pulso ${last.pulse} lpm · ${classify(last.systolic, last.diastolic).label}")
                }
            }
        }

        Text("Hallazgos (últimos 7 días)", style = MaterialTheme.typography.titleMedium)
        if (findings.isEmpty()) Text("Nada destacable.", style = MaterialTheme.typography.bodyMedium)
        findings.forEach { FindingCard(it) }
        Text(
            "Orientativo, según la guía ACC/AHA 2017. No sustituye la valoración de un profesional de salud.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FindingCard(f: Finding) {
    val color = when (f.level) {
        Level.URGENT -> Color(0xFFB71C1C)
        Level.WARNING -> Color(0xFFE65100)
        Level.INFO -> Color(0xFF2E7D32)
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, null, tint = color)
            Column {
                Text(f.title, fontWeight = FontWeight.Bold, color = color)
                Text(f.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryScreen(readings: List<Reading>, onDelete: (Long) -> Unit, modifier: Modifier) {
    val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    var toDelete by remember { mutableStateOf<Reading?>(null) }
    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            confirmButton = { TextButton(onClick = { onDelete(r.id); toDelete = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
            title = { Text("¿Eliminar medición?") },
            text = { Text("${r.systolic}/${r.diastolic} · ${r.pulse} lpm. No se volverá a importar del tensiómetro.") },
        )
    }
    if (readings.isEmpty()) Text("Sin mediciones. Sincroniza el tensiómetro desde Inicio.", modifier)
    LazyColumn(modifier.fillMaxSize()) {
        items(readings, key = { it.id }) { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = { toDelete = r })
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("${r.systolic}/${r.diastolic} · ${r.pulse} lpm", fontWeight = FontWeight.Bold)
                    Text(classify(r.systolic, r.diastolic).label + if (r.irregularHeartbeat) " · latido irregular" else "", style = MaterialTheme.typography.bodySmall)
                }
                Text(if (r.clockUnset) "sin fecha (#${r.sequence})" else r.measuredAt.format(fmt), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SettingsScreen(ui: UiState, vm: MainViewModel, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val hcPerms = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(vm.healthConnect.permissions)) vm.setHealthConnectAuto(true)
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Tensiómetro", style = MaterialTheme.typography.titleMedium)
        Text(ui.deviceAddress?.let { "Emparejado: $it" } ?: "Sin emparejar")
        if (ui.deviceAddress != null) OutlinedButton(onClick = vm::forgetDevice) { Text("Olvidar tensiómetro") }

        SwitchRow("Sincronizar automáticamente al medir", ui.autoSync, vm::setAutoSync)
        Text(
            "En Xiaomi activa también Ajustes › Apps › BP › Inicio automático y Ahorro de batería › Sin restricciones.",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow("Poner en hora el tensiómetro al sincronizar", ui.syncClock, vm::setSyncClock)

        HorizontalDivider()
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
        if (!ui.healthConnectAvailable) {
            Text("Health Connect no está disponible en este móvil.")
        } else {
            SwitchRow("Enviar automáticamente tras sincronizar", ui.healthConnectAuto) { on ->
                if (!on) vm.setHealthConnectAuto(false)
                else scope.launch {
                    if (vm.healthConnect.hasPermissions()) vm.setHealthConnectAuto(true) else hcPerms.launch(vm.healthConnect.permissions)
                }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    if (vm.healthConnect.hasPermissions()) vm.exportHealthConnect() else hcPerms.launch(vm.healthConnect.permissions)
                }
            }) { Text("Enviar pendientes ahora") }
        }

        HorizontalDivider()
        Text("Exportar", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { vm.exportCsv { context.startActivity(CsvExporter.shareIntent(context, it)) } }) {
            Text("Exportar CSV (Excel)")
        }

        HorizontalDivider()
        Text("Datos", style = MaterialTheme.typography.titleMedium)
        var confirmDeleteAll by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { confirmDeleteAll = true }) { Text("Borrar historial") }
        Text(
            "Para borrar una sola medición, mantenla pulsada en Historial. Los datos ya enviados a Health Connect se gestionan desde Health Connect.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (confirmDeleteAll) {
            AlertDialog(
                onDismissRequest = { confirmDeleteAll = false },
                confirmButton = { TextButton(onClick = { vm.deleteAll(); confirmDeleteAll = false }) { Text("Borrar todo") } },
                dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancelar") } },
                title = { Text("¿Borrar todo el historial?") },
                text = {
                    Text(
                        "Se eliminan de la app todas las mediciones actuales. No se borran del tensiómetro, pero tampoco " +
                            "se volverán a importar: solo llegarán las mediciones nuevas.",
                    )
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
