package com.readiness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readiness.app.domain.Confounders

@Composable
fun SettingsDialog(s: SettingsState, onDismiss: () -> Unit, onSave: (SettingsState) -> Unit) {
    var key by remember { mutableStateOf(s.apiKey) }
    var athlete by remember { mutableStateOf(if (s.athlete == "0") "" else s.athlete) }
    var need by remember { mutableStateOf(s.sleepNeed) }
    var nap by remember { mutableStateOf(s.napMinutes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button({ onSave(s.copy(apiKey = key.trim(), athlete = athlete.trim().ifBlank { "0" },
                sleepNeed = need.trim(), napMinutes = nap.trim())) }) { Text("Speichern & laden") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } },
        title = { Text("Einstellungen") },
        text = {
            Column {
                OutlinedTextField(key, { key = it }, label = { Text("intervals.icu API-Key") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(athlete, { athlete = it }, label = { Text("Athlete-ID (leer = 0)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(need, { need = it }, label = { Text("Schlafbedarf h/Nacht (leer = aus Historie)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(nap, { nap = it }, label = { Text("Powernaps Ø min/Tag") }, singleLine = true)
                Text("FTP-Werte werden automatisch aus intervals.icu gelesen.",
                    color = T.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        })
}

@Composable
fun ConfounderCard(date: String, active: List<String>, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel)
        .clickable(onClick = onOpen).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (active.isEmpty()) "Störfaktor angeben (Alkohol, Krankheit …)"
            else "Störfaktor aktiv: " + active.joinToString(", ") { Confounders.label(it) },
            color = if (active.isEmpty()) T.Text else T.Amber, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("▾", color = T.Muted)
    }
}

@Composable
fun ConfounderDialog(date: String, active: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val sel = remember { mutableStateListOf<String>().apply { addAll(active) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button({ onSave(sel.toList()) }) { Text("Speichern") } },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } },
        title = { Text("Störfaktor für $date") },
        text = {
            Column {
                Text("Bekannte, nicht trainingsbedingte Ursachen einer HRV-Absenkung. Der Wert bleibt im " +
                    "Score sichtbar, wird aber nicht als Trainingsüberlastung gedeutet und aus Baseline und " +
                    "Bandbreite ausgeschlossen.", color = T.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Confounders.ALL.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (sel.contains(c.key)) sel.remove(c.key) else sel.add(c.key)
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sel.contains(c.key), { if (it) sel.add(c.key) else sel.remove(c.key) })
                        Text(c.label, fontSize = 13.sp,
                            fontWeight = if (sel.contains(c.key)) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        })
}
