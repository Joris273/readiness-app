package com.readiness.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readiness.app.data.Snapshot

/**
 * Verlaufsdiagramme, direkt mit Canvas gezeichnet.
 *
 * Bewusst ohne Diagramm-Bibliothek: die beiden Darstellungen sind einfach genug, und
 * eine zusätzliche Abhängigkeit brächte Versionsrisiko in den Build, ohne etwas zu
 * lösen, was hier nicht in dreißig Zeilen machbar wäre.
 */

private fun DrawScope.polyline(values: List<Double?>, min: Double, max: Double, color: Color, width: Float = 3f) {
    if (values.count { it != null } < 2) return
    val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
    val stepX = size.width / (values.size - 1).coerceAtLeast(1)
    val path = Path()
    var started = false
    values.forEachIndexed { i, v ->
        if (v == null) { started = false; return@forEachIndexed }
        val x = i * stepX
        val y = size.height - ((v - min) / span * size.height).toFloat()
        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.zeroLine(min: Double, max: Double, color: Color) {
    if (min > 0 || max < 0) return
    val span = (max - min).takeIf { it > 1e-9 } ?: return
    val y = size.height - ((0.0 - min) / span * size.height).toFloat()
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
}

@Composable
private fun ChartCard(title: String, legend: List<Pair<String, Color>>, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(14.dp)) {
        Text(title, color = T.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(top = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            legend.forEach { (label, c) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
                    Text(" $label", color = T.Muted, fontSize = 11.sp)
                }
            }
        }
        content()
    }
}

/** Form (TSB) gegen HRV. Zwei Größen mit völlig verschiedenen Einheiten — deshalb
 *  jeweils auf die eigene Spannweite skaliert; verglichen wird der VERLAUF, nicht die Höhe. */
@Composable
fun TsbHrvChart(points: List<Snapshot.ChartPoint>) {
    if (points.size < 2) return
    val tsb = points.map { it.tsb }
    val hrv = points.map { it.hrv }
    val tMin = (tsb.filterNotNull().minOrNull() ?: -10.0).coerceAtMost(-5.0)
    val tMax = (tsb.filterNotNull().maxOrNull() ?: 10.0).coerceAtLeast(5.0)
    val hMin = hrv.filterNotNull().minOrNull() ?: 0.0
    val hMax = hrv.filterNotNull().maxOrNull() ?: 1.0
    ChartCard("Form (TSB) und HRV · 30 Tage",
        listOf("TSB" to T.hex("#A78BFA"), "HRV" to T.hex("#4CC3FF"))) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            zeroLine(tMin, tMax, T.Line)
            polyline(hrv, hMin - (hMax - hMin) * 0.2, hMax + (hMax - hMin) * 0.2, T.hex("#4CC3FF"), 2.5f)
            polyline(tsb, tMin, tMax, T.hex("#A78BFA"), 3f)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().date.substring(5).replace('-', '.'), color = T.Faint, fontSize = 10.sp)
            Text("TSB ${fmt(tMin)} … ${fmt(tMax)} · HRV ${fmt(hMin)}–${fmt(hMax)} ms", color = T.Faint, fontSize = 10.sp)
            Text(points.last().date.substring(5).replace('-', '.'), color = T.Faint, fontSize = 10.sp)
        }
    }
}

/** Fitness (CTL), Ermüdung (ATL) und die Tageslast als Balken. */
@Composable
fun LoadChart(points: List<Snapshot.ChartPoint>) {
    if (points.size < 2) return
    val ctl = points.map { it.ctl }
    val atl = points.map { it.atl }
    val loads = points.map { it.load }
    val yMax = maxOf(
        ctl.filterNotNull().maxOrNull() ?: 0.0,
        atl.filterNotNull().maxOrNull() ?: 0.0,
        loads.maxOrNull() ?: 0.0, 1.0) * 1.1
    ChartCard("Fitness, Ermüdung und Tageslast · 30 Tage",
        listOf("CTL" to T.Green, "ATL" to T.Amber, "Last" to T.hex("#5B6C85"))) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            val barW = (stepX * 0.55f).coerceAtLeast(2f)
            loads.forEachIndexed { i, l ->
                if (l <= 0) return@forEachIndexed
                val h = (l / yMax * size.height).toFloat()
                drawRect(T.hex("#5B6C85"), Offset(i * stepX - barW / 2, size.height - h),
                    androidx.compose.ui.geometry.Size(barW, h))
            }
            polyline(atl, 0.0, yMax, T.Amber, 2.5f)
            polyline(ctl, 0.0, yMax, T.Green, 3f)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().date.substring(5).replace('-', '.'), color = T.Faint, fontSize = 10.sp)
            Text("Skala 0 … ${fmt(yMax)}", color = T.Faint, fontSize = 10.sp)
            Text(points.last().date.substring(5).replace('-', '.'), color = T.Faint, fontSize = 10.sp)
        }
    }
}

private fun fmt(v: Double) = String.format("%.0f", v)
