package com.readiness.app.data


import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.domain.Progression
import com.readiness.app.domain.ReadinessResult
import com.readiness.app.domain.ScoreEngine
import com.readiness.app.domain.Streams
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Übersetzt das Domänenergebnis in das speicher- und anzeigbare Snapshot-Modell.
 * Alle Formatierung liegt hier, damit die Domäne keine Darstellungsfragen kennt.
 */
object SnapshotMapper {

    private fun f(v: Double?, d: Int = 1) =
        if (v == null || v.isNaN()) "–" else String.format("%.${d}f", v).replace('.', ',')
    private fun sgn(v: Double?) = if (v != null && v >= 0) "+" else ""

    fun map(r: ReadinessResult, cfg: AnalysisConfig): Snapshot {
        val m = r.metrics
        val lh = r.loadHistory

        val band = if (m.hrvBandLo != null && m.hrvBandHi != null)
            "${f(m.hrvBandLo, 0)}–${f(m.hrvBandHi, 0)} ms" else null
        val weekPart = m.hrvWeekDevPct?.let {
            " · Woche ${sgn(it)}${f(it)} %" + when {
                m.hrvWeekAlarm -> " ⚠"; m.hrvWeekDown -> " ↓"; m.hrvWeekUp -> " ↑"; else -> ""
            }
        } ?: ""
        val hrvSub = when {
            m.hrv == null -> "keine Messung"
            band == null -> "zu wenig Verlauf für dein Normalband"
            m.hrvSuppressed -> "↓ unter deinem Normalband $band$weekPart"
            m.hrvAbove -> "↑ über deinem Normalband $band$weekPart"
            else -> "→ in deinem Normalband $band$weekPart"
        }

        val loadValue = when {
            lh.trainedToday && lh.hardToday -> "heute Qualitätsreiz"
            lh.trainedToday -> "heute trainiert"
            lh.forceRest -> "⚠ Ruhetag fällig"
            else -> "${lh.consecutiveDays} Trainingstag${if (lh.consecutiveDays == 1) "" else "e"} in Folge" +
                if (lh.hardYesterday) " · gestern hart" else ""
        }
        /* Einheiten konsistent halten: Dauer als h:mm, Zonenzeiten gebündelt mit EINER
           gemeinsamen Einheit. „Belastung" statt „Last" oder „TSS" — intervals.icu liefert
           icu_training_load, das bei Leistungsdaten dem TSS entspricht, bei reiner
           Herzfrequenz aber einem HRSS/TRIMP-Wert. „TSS" wäre also zu eng. */
        fun hm(sec: Double): String {
            val m = (sec / 60).roundToInt()
            return if (m >= 60) "${m / 60}:${(m % 60).toString().padStart(2, '0')} h" else "$m min"
        }
        fun zoneLine(z4: Int, z5: Int, z6: Int, torque: Int): String {
            val parts = mutableListOf("Z4/Z5+/Z6+ ${z4 / 60}/${z5 / 60}/${z6 / 60} min")
            if (torque > 0) parts += "Kraft ${torque / 60} min"
            return parts.joinToString(" · ")
        }
        val loadSub = when {
            lh.trainedToday && lh.today != null -> lh.today.let {
                "heute ${hm(it.zonedSec)} · ${zoneLine(it.z4, it.z5, it.z6, it.torque)} · Belastung ${it.load}" +
                    " · davor ${lh.consecutiveDays} Trainingstag${if (lh.consecutiveDays == 1) "" else "e"} in Folge"
            }
            lh.yesterday != null -> lh.yesterday.let {
                (if (it.hasZones)
                    "gestern ${hm(it.zonedSec)} · ${zoneLine(it.z4, it.z5, it.z6, it.torque)} · Belastung ${it.load}"
                else "gestern ${hm(it.zonedSec)} · keine Zonendaten · IF ${f(it.maxIf, 2)} · Belastung ${it.load}") +
                    " · Monotonie ${f(lh.monotony)}"
            }
            else -> "Monotonie ${f(lh.monotony)} (Foster)"
        }

        val tiles = listOf(
            Snapshot.Tile("Form · TSB", f(m.tsb), "CTL ${f(m.ctl, 0)} · ATL ${f(m.atl, 0)}"),
            Snapshot.Tile("HRV (rMSSD)", m.hrv?.let { "${f(it, 0)} ms" } ?: "–", hrvSub),
            Snapshot.Tile("Ruhepuls", m.restingHr?.let { "${f(it, 0)} bpm" } ?: "–",
                m.restingHrDiff?.let { "${sgn(it)}${f(it)} bpm vs. ${f(m.restingHrBase)} (7-T-Schnitt)" } ?: "keine Messung"),
            Snapshot.Tile("Schlaf", m.sleepScore?.let { "${it.roundToInt()}/100" } ?: (m.sleepHours?.let { "${f(it)} h" } ?: "–"),
                m.sleep7Effective?.let { "7-T-Mittel ${f(it)} h" + (m.sleepNeed?.let { n -> " · Bedarf ${f(n)} h" } ?: "") } ?: ""),
            Snapshot.Tile("Belastungsverlauf", loadValue, loadSub),
            Snapshot.Tile("ACWR", f(m.acwr, 2), "grobe Orientierung · kein Risiko-Gate"),
        )

        return Snapshot(
            score = r.score, baseScore = r.baseScore, deduction = r.deduction,
            word = ScoreEngine.scoreWord(r.recommendation.verdict),
            colorHex = r.recommendation.colorHex,
            recoTitle = r.recommendation.title, recoText = r.recommendation.text,
            dataDate = m.dataDate ?: "", updatedAt = System.currentTimeMillis(),
            renormalized = r.components.any { it.sub == null },
            components = r.components.map {
                Snapshot.Component(it.id, it.name,
                    ((if (it.effectiveWeight > 0) it.effectiveWeight else it.weight) * 100).roundToInt(),
                    it.sub, it.explanation, it.colorHex)
            },
            tiles = tiles,
            thresholds = Snapshot.Thresholds(r.thresholds.outdoorFtp, r.thresholds.indoorFtp,
                r.thresholds.eftp, r.thresholds.lthr, r.thresholds.maxHr, r.thresholds.staleMessage),
            limits = r.limitingFactors.map { it.label },
            loadNote = lh.notes.joinToString("; "),
            hrvDate = m.hrvDate,
            confounders = cfg.confounders[m.hrvDate].orEmpty(),
            napMinutesToday = cfg.napMinutesByDay[m.hrvDate] ?: 0,
            progression = mapProgression(r.progression, cfg),
            chart = r.chart.map {
                Snapshot.ChartPoint(it.date, it.ctl, it.atl, it.tsb, it.hrv, it.load)
            },
        )
    }

    private fun deltaKind(v: Double?, goodUp: Boolean, dead: Double): String = when {
        v == null -> "none"
        (if (goodUp) v > dead else v < -dead) -> "good"
        (if (goodUp) v < -dead else v > dead) -> "bad"
        else -> "flat"
    }

    private fun deltaText(v: Double?, unit: String, thin: Boolean = false) =
        v?.let { "${sgn(it)}${f(it)}$unit" + if (thin) "°" else "" }

    private fun mapProgression(p: Progression, cfg: AnalysisConfig): Snapshot.Progression {
        if (!p.ok) return Snapshot.Progression(false, p.windowDays, cfg.cycles, p.verdictTitle, p.verdictText, p.verdictColorHex)

        val rows = mutableListOf<Snapshot.Row>()

        val eftpReason = when {
            p.eftpNow == null && p.eftpPrev == null -> "keine eFTP-Werte — setzt intervals.icu nur bei maximalen Antritten"
            p.eftpPrev == null -> "kein eFTP im älteren Zeitraum"
            p.eftpNow == null -> "kein eFTP im aktuellen Zeitraum"
            else -> null
        }
        rows += Snapshot.Row("Leistung", "bestes eFTP je ${p.windowDays} Tage",
            if (p.eftpPrev != null) "${p.eftpPrev} → ${p.eftpNow} W" else (p.eftpNow?.let { "$it W" } ?: ""),
            deltaText(p.eftpDeltaPct, " %"), deltaKind(p.eftpDeltaPct, true, 1.5), eftpReason)

        rows += Snapshot.Row("Aerobe Effizienz", "NP/HF, aerobe Einheiten (n=${p.efNPrev}→${p.efN})",
            if (p.efDeltaPct != null) "${f(p.efPrev, 2)} → ${f(p.efNow, 2)}" else "",
            deltaText(p.efDeltaPct, " %"), deltaKind(p.efDeltaPct, true, 1.5),
            if (p.efDeltaPct == null) (if (p.efNPrev < 2) "zu wenige aerobe Einheiten im älteren Zeitraum"
                                       else "zu wenige aerobe Einheiten im aktuellen Zeitraum") else null)

        rows += Snapshot.Row("Belastung", "CTL, Rampe ${f(p.rampPerWeek)}/Woche",
            if (p.ctlPrev != null) "${f(p.ctlPrev, 0)} → ${f(p.ctlNow, 0)}" else "",
            deltaText(p.ctlDeltaPct, " %"), deltaKind(p.ctlDeltaPct, true, 3.0),
            if (p.ctlDeltaPct == null) "kein CTL-Vergleichswert" else null)

        val openOld = p.torqueScan?.openOlder ?: 0
        fun lcReason(now: Int?, prev: Int?): String? = when {
            now == null && prev == null -> "keine Krafteinheit im Zeitraum"
            prev == null -> if (openOld > 0) "wird gerade geladen …" else "kein Vergleichswert im älteren Zeitraum"
            now == null -> "im aktuellen Zeitraum keine solche Einheit"
            else -> null
        }
        p.durations.forEach { d ->
            if (d.now == null && d.prev == null) return@forEach
            rows += Snapshot.Row(d.label, "${d.note}, ≤${Streams.LC_RPM} rpm (n=${d.nPrev}→${d.nNow})",
                if (d.prev != null) "${d.prev} → ${d.now} W" else (d.now?.let { "$it W" } ?: ""),
                deltaText(d.deltaPct, " %", d.thin), deltaKind(d.deltaPct, true, 1.5), lcReason(d.now, d.prev))
        }
        if (p.lcEfNow != null || p.lcEfPrev != null) {
            rows += Snapshot.Row("Kraft-Effizienz", "W/bpm bei ≤${Streams.LC_RPM} rpm, ab 5 min (n=${p.lcEfNPrev}→${p.lcEfN})",
                if (p.lcEfPrev != null) "${f(p.lcEfPrev, 2)} → ${f(p.lcEfNow, 2)}" else (p.lcEfNow?.let { f(it, 2) } ?: ""),
                deltaText(p.lcEfDeltaPct, " %", p.lcEfThin), deltaKind(p.lcEfDeltaPct, true, 1.5),
                if (p.lcEfDeltaPct == null) (if (openOld > 0) "wird gerade geladen …" else "kein Vergleichswert") else null)
        }
        if (p.peakTorqueNow != null) {
            rows += Snapshot.Row("Spitzendrehmoment", "bestes 30-s-Mittel — Orientierung, geht nicht ins Urteil ein",
                if (p.peakTorquePrev != null) "${f(p.peakTorquePrev, 0)} → ${f(p.peakTorqueNow, 0)} Nm" else "${f(p.peakTorqueNow, 0)} Nm",
                null, "none", "nur Orientierung")
        }
        if (p.decliners.isNotEmpty() || p.hrvChronDeltaPct != null) {
            p.hrvChronDeltaPct?.let {
                rows += Snapshot.Row("HRV-Chronik", "28-Tage-Mittel Ln-rMSSD",
                    "${f(p.hrvChronPrev, 0)} → ${f(p.hrvChronNow, 0)} ms",
                    deltaText(it, " %"), deltaKind(it, true, 3.0), null)
            }
        }

        val chips = buildList {
            p.ctlDeltaPct?.let { add(Snapshot.Chip("Belastung · CTL", deltaText(it, " %")!!, deltaKind(it, true, 3.0))) }
            p.eftpDeltaPct?.let { add(Snapshot.Chip("Leistung · eFTP", deltaText(it, " %")!!, deltaKind(it, true, 1.5))) }
            if (size < 3) p.efDeltaPct?.let { add(Snapshot.Chip("Effizienz · NP/HF", deltaText(it, " %")!!, deltaKind(it, true, 1.5))) }
            val lc = p.durations.firstOrNull { !it.thin && it.deltaPct != null }
            if (size < 3 && lc != null) add(Snapshot.Chip("Kraft", deltaText(lc.deltaPct, " %")!!, deltaKind(lc.deltaPct, true, 1.5)))
            if (size < 3) p.hrvChronDeltaPct?.let { add(Snapshot.Chip("HRV-Chronik", deltaText(it, " %")!!, deltaKind(it, true, 3.0))) }
        }.take(3)

        val distNote = p.share12?.let {
            when {
                it < 70 -> " — zu wenig niedrige Intensität, Gefahr von Grauzonentraining"
                it > 93 -> " — fast nur Grundlage, Schwellen-/VO2max-Reiz fehlt"
                (p.share4 ?: 0.0) < 4 -> " — sehr wenig Z4+, für Schwellenentwicklung knapp"
                else -> " — im Rahmen der Referenz"
            }
        } ?: ""

        val sepMin = p.windowDays / 2
        val weak = buildList {
            if (p.eftpSeparationDays != null && p.eftpSeparationDays < sepMin) add("eFTP (${p.eftpSeparationDays} Tage)")
            p.durations.forEach { d ->
                if (d.deltaPct != null && d.separationDays != null && d.separationDays < sepMin)
                    add("${d.label} (${d.separationDays} Tage)")
            }
        }
        val hint = buildString {
            append(p.diagText ?: ("CTL ist die Dosis, nicht die Wirkung — deshalb werden Leistung, aerobe Effizienz und Kraft " +
                "getrennt als Antwort geführt. Kraft misst Watt bei konditionierter Trittfrequenz (≤${Streams.LC_RPM} rpm), " +
                "nicht rohes Drehmoment: Nm allein bildet vor allem die Gangwahl ab. Die HF-Kopplung (W/bpm) gilt erst ab " +
                "fünf Minuten — darunter misst man die Anlaufkurve der Herzfrequenz, nicht die Beanspruchung."))
            if (weak.isNotEmpty()) append(" Geringe zeitliche Trennung bei ${weak.joinToString(", ")} — " +
                "echte Veränderung wird dadurch eher unter- als überschätzt.")
            p.torqueScan?.let { s ->
                if (s.missing > 0) append(" Kraftdaten werden gerade geladen: ${s.total - s.missing} von ${s.total} " +
                    "Einheiten ausgewertet" + (if (s.openOlder > 0) ", ${s.openOlder} davon noch offen im älteren Zeitraum" else "") +
                    ". Die Karte aktualisiert sich von selbst; ausgewertete Einheiten werden dauerhaft gespeichert.")
            }
        }

        return Snapshot.Progression(
            ok = true, windowDays = p.windowDays, cycles = cfg.cycles,
            title = p.verdictTitle, text = p.verdictText, colorHex = p.verdictColorHex,
            chips = chips, rows = rows,
            share12 = p.share12, share3 = p.share3, share4 = p.share4,
            zoneHours = p.zoneHours, distributionNote = distNote, hint = hint,
            anyThin = p.durations.any { it.thin && it.deltaPct != null } || (p.lcEfThin && p.lcEfDeltaPct != null),
        )
    }
}
