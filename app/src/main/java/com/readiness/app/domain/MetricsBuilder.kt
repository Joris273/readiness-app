package com.readiness.app.domain

import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Baut aus Wellness-Reihe und Einheiten die Tageskennwerte auf.
 *
 * Im Prototyp war das ein Teil der 237-Zeilen-Ladefunktion, die zugleich Netzabruf und
 * Rendering erledigte. Hier ist es eine reine Funktion ohne Seiteneffekte: gleiche
 * Eingaben, gleiches Ergebnis, ohne Emulator testbar.
 */
object MetricsBuilder {

    private fun stdDev(values: List<Double>): Double {
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    /** Neuester Eintrag mit gültigem Wert, höchstens `maxBack` Tage zurück. */
    private fun latestIndex(w: List<WellnessDay>, maxBack: Int = 3, sel: (WellnessDay) -> Double?): Int {
        var i = w.size - 1
        val stop = max(0, w.size - 1 - maxBack)
        while (i >= stop) { val v = sel(w[i]); if (v != null && v != 0.0) return i; i-- }
        return -1
    }

    /** Mittelwert der letzten `days` gültigen Werte VOR endIdx, gestörte Tage ausgenommen. */
    private fun baseline(w: List<WellnessDay>, endIdx: Int, days: Int = 7,
                         exclude: Set<String> = emptySet(), sel: (WellnessDay) -> Double?): Double? {
        val vals = mutableListOf<Double>()
        var i = endIdx - 1
        while (i >= 0 && vals.size < days) {
            if (w[i].date !in exclude) { val v = sel(w[i]); if (v != null && v != 0.0) vals += v }
            i--
        }
        return if (vals.size >= 3) vals.average() else null
    }

    fun build(wellness: List<WellnessDay>, sessions: List<Session>, cfg: AnalysisConfig, today: LocalDate): Metrics {
        if (wellness.isEmpty()) return Metrics()
        val w = wellness.sortedBy { it.date }
        val confounded = cfg.confoundedDays
        val last = w.last()

        var m = Metrics(
            dataDate = last.date, ctl = last.ctl, atl = last.atl,
            tsb = if (last.ctl != null && last.atl != null) last.ctl - last.atl else null,
            napMinutes = cfg.napMinutesByDay[last.date] ?: 0,
        )

        // ---- HRV: Tageswert gegen individuelle SWC-Bandbreite ----
        val hrvIdx = latestIndex(w) { it.hrv }
        if (hrvIdx >= 0) {
            val v = w[hrvIdx].hrv!!
            val base7 = baseline(w, hrvIdx, 7, confounded) { it.hrv }
            val lnVals = mutableListOf<Double>()
            var i = hrvIdx - 1
            while (i >= 0 && lnVals.size < 7) {
                if (w[i].date !in confounded) { val x = w[i].hrv; if (x != null && x > 0) lnVals += ln(x) }
                i--
            }
            m = m.copy(hrv = v, hrvDate = w[hrvIdx].date,
                hrvDeviationPct = base7?.let { (v - it) / it * 100 })

            if (lnVals.size >= 3) {
                val mn = lnVals.average()
                val sd = stdDev(lnVals)
                val swc = 0.5 * max(sd, 0.0488)      // Rausch-Floor ~5 %
                val lnToday = ln(v)
                m = m.copy(
                    hrvLn = lnToday, hrvLnBase = mn, hrvLnSd = sd,
                    hrvBandLo = exp(mn - swc), hrvBandHi = exp(mn + swc),
                    hrvBeyond = max(0.0, (-(lnToday - mn) - swc) / swc),
                    hrvSuppressed = (lnToday - mn) < -swc,
                    hrvAbove = (lnToday - mn) > swc,
                    hrvUnusual = (lnToday - mn) > 2.5 * swc,
                )

                /* WOCHENTREND nach Plews — der eigentliche Standard der Literatur.
                   Der Tagesvergleich oben fragt „ist heute ungewöhnlich?". Plews zeigt
                   aber, dass Wochenmittel trainingsbedingte Veränderungen zuverlässiger
                   abbilden, und im dokumentierten Fall eines überlasteten Athleten fiel
                   das 7-Tage-Mittel graduell ab, während die Tageswerte weiter schwankten.

                   Die Wochenmittel werden NICHT-ÜBERLAPPEND gebildet: benachbarte
                   rollierende Fenster teilen sechs von sieben Tagen und sind hoch
                   korreliert, ihre Streuung unterschätzt die Unsicherheit erheblich. */
                fun weekMean(from: Int, to: Int): Pair<Double, Int>? {
                    val a = mutableListOf<Double>()
                    var k = hrvIdx - from
                    while (k > hrvIdx - to && k >= 0) {
                        if (w[k].date !in confounded) { val x = w[k].hrv; if (x != null && x > 0) a += ln(x) }
                        k--
                    }
                    return if (a.size >= 3) a.average() to a.size else null
                }
                val cur = weekMean(0, 7)
                val refs = listOf(weekMean(7, 14), weekMean(14, 21), weekMean(21, 28), weekMean(28, 35)).filterNotNull()
                if (cur != null && refs.size >= 3) {
                    val refM = refs.map { it.first }.average()
                    var wSd = sqrt(refs.sumOf { (it.first - refM) * (it.first - refM) } / refs.size)
                    wSd = max(wSd, sd / sqrt(cur.second.toDouble()))   // Standardfehler als Untergrenze
                    /* Zwei Schwellen: die Literatur-SWC von 0,5 SD ist als „kleinste
                       bedeutsame Änderung" definiert, nicht als Signifikanztest — bei
                       stabilem Verlauf fällt ein Wochenmittel rein zufällig in rund 32 %
                       der Fälle darunter. Als Anzeige richtig, als Auslöser einer
                       Intensitätsbegrenzung viel zu locker. Gehandelt wird ab 1,5 SD. */
                    val swcWeek = 0.5 * wSd
                    val actWeek = 1.5 * wSd
                    m = m.copy(
                        hrvWeek = exp(cur.first), hrvWeekRef = exp(refM),
                        hrvWeekDevPct = (exp(cur.first) / exp(refM) - 1) * 100,
                        hrvWeekDown = (cur.first - refM) < -swcWeek,
                        hrvWeekAlarm = (cur.first - refM) < -actWeek,
                        hrvWeekUp = (cur.first - refM) > swcWeek,
                    )
                }
            }

            val causes = cfg.confounders[w[hrvIdx].date].orEmpty()
            if (causes.isNotEmpty()) {
                val kinds = causes.mapNotNull { Confounders.byKey(it)?.kind }
                val illness = ConfounderKind.MEDICAL in kinds
                m = m.copy(
                    confounded = true,
                    confounderLabel = causes.joinToString(", ") { Confounders.label(it) },
                    confIllness = illness,
                    confInvalid = ConfounderKind.INVALID in kinds,
                    confExternal = ConfounderKind.EXTERNAL in kinds && !illness,
                )
            }
        }

        // ---- Ruhepuls ----
        val rhrIdx = latestIndex(w) { it.restingHr }
        if (rhrIdx >= 0) {
            val v = w[rhrIdx].restingHr!!
            val b = baseline(w, rhrIdx, 7, confounded) { it.restingHr }
            m = m.copy(restingHr = v, restingHrBase = b, restingHrDiff = b?.let { v - it })
        }

        // ---- Schlaf ----
        latestIndex(w) { it.sleepScore }.takeIf { it >= 0 }?.let { m = m.copy(sleepScore = w[it].sleepScore) }
        val sIdx = latestIndex(w) { it.sleepSeconds }
        if (sIdx >= 0) {
            m = m.copy(sleepHours = w[sIdx].sleepSeconds!! / 3600,
                sleepAvgHours = baseline(w, sIdx, 7) { it.sleepSeconds }?.div(3600))
        }
        /* Naps TAGESGENAU zur jeweiligen Nacht addieren, nicht als pauschaler Zuschlag.
           Ein Durchschnitt würde sie an Tagen ohne Nickerchen erfinden und an Tagen mit
           langem Nap unterschlagen — genau dort, wo die Bilanz entscheidend ist. */
        val s7 = mutableListOf<Double>(); val s30 = mutableListOf<Double>(); val sAll = mutableListOf<Double>()
        w.forEach { d ->
            val sec = d.sleepSeconds ?: return@forEach
            val date = runCatching { LocalDate.parse(d.date) }.getOrNull() ?: return@forEach
            val back = (today.toEpochDay() - date.toEpochDay()).toInt()
            val total = sec / 3600 + cfg.napHoursOn(d.date)
            if (back in 0..6) s7 += total
            if (back in 0..29) s30 += total
            if (back in 0..59) sAll += total
        }
        val hist = if (sAll.size >= 10) sAll.sorted().let {
            if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
        } else null
        val need = cfg.sleepNeedHours ?: hist
        val eff = if (s7.size >= 4) s7.average() else null
        m = m.copy(
            sleep7Effective = eff,
            sleep30 = if (s30.size >= 14) s30.average() else null,
            sleepNeed = need, sleepNeedManual = cfg.sleepNeedHours != null,
            sleepDeficit = if (need != null && eff != null) need - eff else null,
        )

        // ---- ACWR (nur Orientierung, kein Risiko-Gate) ----
        val byDate = HashMap<String, Double>()
        sessions.forEach { byDate[it.localDate] = (byDate[it.localDate] ?: 0.0) + it.trainingLoad }
        var acute = 0.0; var chronic = 0.0
        for (i in 0 until 28) {
            val l = byDate[today.minusDays(i.toLong()).toString()] ?: 0.0
            chronic += l; if (i < 7) acute += l
        }
        m = m.copy(acwr = when {
            chronic > 0 -> (acute / 7) / (chronic / 28)
            (m.ctl ?: 0.0) > 0 && m.atl != null -> m.atl!! / m.ctl!!
            else -> null
        })

        return m
    }
}
