package com.readiness.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readiness.app.data.Snapshot
import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.domain.Confounders

@Composable
fun DashboardScreen(
    state: UiState,
    onReload: () -> Unit,
    onSaveSettings: (SettingsState) -> Unit,
    onSetCycles: (Int) -> Unit,
    onSetConfounders: (String, List<String>) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showConfounder by remember { mutableStateOf(false) }
    var progOpen by remember { mutableStateOf(false) }
    val snap = state.snapshot

    Column(
        Modifier.fillMaxSize().background(T.Bg).verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Readiness", color = T.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton({ showSettings = true }) { Icon(Icons.Filled.Settings, "Einstellungen", tint = T.Muted) }
            IconButton(onReload) { Icon(Icons.Filled.Refresh, "Neu laden", tint = T.Muted) }
        }

        if (state.settings.apiKey.isBlank()) {
            Spacer(Modifier.height(12.dp))
            Card2 { Text("Trage zuerst deinen intervals.icu-API-Key unter ⚙ ein.", color = T.Text, fontSize = 14.sp) }
        }

        snap?.thresholds?.staleMessage?.let {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.Amber.copy(alpha = .12f)).padding(12.dp)) {
                Text("⚠ $it", color = T.Amber, fontSize = 12.5.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        ScoreCard(snap)
        Spacer(Modifier.height(12.dp))
        snap?.let { RecoCard(it) }
        snap?.progression?.let {
            Spacer(Modifier.height(12.dp))
            ProgressionStrip(it) { progOpen = true }
        }
        Spacer(Modifier.height(12.dp))
        snap?.let { TileGrid(it.tiles) }

        snap?.hrvDate?.let { date ->
            Spacer(Modifier.height(12.dp))
            ConfounderCard(date, snap.confounders) { showConfounder = true }
        }

        snap?.progression?.let {
            Spacer(Modifier.height(12.dp))
            ProgressionCard(it, progOpen, { progOpen = !progOpen }, onSetCycles)
        }

        if (state.loading) { Spacer(Modifier.height(14.dp)); Centered("Lade Daten …", T.Muted) }
        else if (state.filling) { Spacer(Modifier.height(14.dp)); Centered("Kraftdaten werden im Hintergrund ergänzt …", T.Faint) }
        state.error?.let { Spacer(Modifier.height(14.dp)); Centered("Fehler: $it", T.Red) }

        Spacer(Modifier.height(24.dp))
        Centered("Datenquelle intervals.icu · kein Medizinprodukt", T.Faint, 11.sp)
        Spacer(Modifier.height(24.dp))
    }

    if (showSettings) SettingsDialog(state.settings, { showSettings = false }) { showSettings = false; onSaveSettings(it) }
    if (showConfounder && snap?.hrvDate != null)
        ConfounderDialog(snap.hrvDate, snap.confounders, { showConfounder = false }) {
            showConfounder = false; onSetConfounders(snap.hrvDate, it)
        }
}

@Composable private fun Centered(t: String, c: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.TextUnit = 13.sp) =
    Text(t, color = c, fontSize = size, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

@Composable private fun Card2(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(16.dp), content = content)

@Composable
private fun ScoreCard(snap: Snapshot?) {
    val accent = T.hex(snap?.colorHex ?: "#8A97A8")
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
            val pct = (snap?.score ?: 0) / 100f
            Canvas(Modifier.size(190.dp)) {
                val stroke = Stroke(width = 24f, cap = StrokeCap.Round)
                drawArc(T.Line, -90f, 360f, false, style = stroke, size = Size(size.width, size.height))
                drawArc(accent, -90f, 360f * pct, false, style = stroke, size = Size(size.width, size.height))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(snap?.score?.toString() ?: "–", color = T.Text, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text("Zustandsscore / 100", color = T.Muted, fontSize = 12.sp)
                Text(snap?.word ?: "", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        snap?.let {
            Text((if (it.renormalized) "Teilweise fehlende Daten — Gewichte neu normiert · " else "") + "Datenstand ${it.dataDate}",
                color = T.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton({ open = !open }) {
                Text(if (open) "Zusammensetzung verbergen ▴" else "Zusammensetzung anzeigen ▾", color = T.Text, fontSize = 13.sp)
            }
            if (open) Column(Modifier.fillMaxWidth()) {
                it.components.forEach { c -> ComponentRow(c) }
                Text("Basis-Score ${it.baseScore ?: "–"}" + (if (it.deduction > 0) " − ${it.deduction} Belastung" else "") +
                    " = ${it.score ?: "–"}", color = T.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                if (it.limits.isNotEmpty()) Text("Limitierende Faktoren: " + it.limits.joinToString("; "),
                    color = T.Amber, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ComponentRow(c: Snapshot.Component) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${c.name} · ${c.weightPct} %", color = T.Text, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(c.sub?.let { "$it/100" } ?: "–", color = T.hex(c.colorHex), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(c.explanation, color = T.Muted, fontSize = 12.sp)
        val frac = ((c.sub ?: 0).coerceIn(0, 100)) / 100f
        Box(Modifier.fillMaxWidth().height(5.dp).padding(top = 3.dp).clip(RoundedCornerShape(3.dp)).background(T.Line)) {
            Box(Modifier.fillMaxWidth(frac).height(5.dp).clip(RoundedCornerShape(3.dp)).background(T.hex(c.colorHex)))
        }
    }
}

@Composable
private fun RecoCard(snap: Snapshot) {
    val accent = T.hex(snap.colorHex)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(14.dp)) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(accent))
        Column(Modifier.padding(start = 12.dp)) {
            Text(snap.recoTitle, color = T.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(snap.recoText, color = T.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun TileGrid(tiles: List<Snapshot.Tile>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(12.dp)) {
                        Text(t.label.uppercase(), color = T.Muted, fontSize = 10.5.sp)
                        Text(t.value, color = T.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(t.sub, color = T.Muted, fontSize = 11.sp)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
