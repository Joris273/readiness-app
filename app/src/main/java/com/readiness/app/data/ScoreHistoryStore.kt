package com.readiness.app.data

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.math.roundToInt

/**
 * Verlauf der Tageswerte, um den Score einordnen zu können.
 *
 * Das Problem, das damit gelöst wird: Alle Teilscores messen gegen die EIGENE Baseline.
 * Wer stabil und gut erholt ist, liegt deshalb fast immer im oberen Bereich — der Wert
 * wirkt dann pauschal hoch, obwohl er korrekt ist. Eine Zahl von 84 auf einer Skala bis
 * 100 liest sich wie „sehr bereit", tatsächlich kann sie für diese Person ein
 * unterdurchschnittlicher Tag sein.
 *
 * Statt die Skala künstlich zu spreizen — was kleine Schwankungen dramatisieren würde —
 * wird der Wert gegen die eigene Verteilung eingeordnet. Das ist dieselbe Logik, die
 * auch bei HRV und Ruhepuls gilt, nur eine Ebene höher.
 */
class ScoreHistoryStore(context: Context) {
    private val file = File(context.filesDir, "scores.json")
    private val ser = MapSerializer(String.serializer(), Int.serializer())

    fun load(): Map<String, Int> = runCatching {
        if (file.exists()) AppJson.decodeFromString(ser, file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    fun record(date: String, score: Int?, keepFrom: String) {
        if (score == null || date.isBlank()) return
        val all = load().toMutableMap()
        all[date] = score
        runCatching { file.writeText(AppJson.encodeToString(ser, all.filterKeys { it >= keepFrom })) }
    }

    /** Einordnung des heutigen Werts in die eigene Verteilung der letzten Tage. */
    fun classify(score: Int?, today: String, window: Int = 60): ScoreContext? {
        if (score == null) return null
        val hist = load().filterKeys { it != today }.values.toList()
        if (hist.size < 14) return null      // zu wenig Verlauf für eine Einordnung
        val below = hist.count { it < score }
        val pct = (below * 100.0 / hist.size).roundToInt()
        val median = hist.sorted().let {
            if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
        }
        val label = when {
            pct >= 80 -> "einer deiner besten Tage"
            pct >= 60 -> "überdurchschnittlich für dich"
            pct >= 40 -> "ein durchschnittlicher Tag"
            pct >= 20 -> "unterdurchschnittlich für dich"
            else -> "einer deiner schwächsten Tage"
        }
        return ScoreContext(pct, median, hist.size, label)
    }

    data class ScoreContext(val percentile: Int, val median: Int, val days: Int, val label: String)
}
