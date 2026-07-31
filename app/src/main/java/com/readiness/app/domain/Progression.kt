package com.readiness.app.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * FORMAUFBAU: Dosis gegen Antwort.
 *
 * Wissenschaftlicher Kern: CTL ist die DOSIS (Modell-Output aus dem eigenen
 * Trainings-Input), nicht die WIRKUNG. Steigende CTL heißt nicht „fitter". Um
 * Formaufbau zu belegen, braucht es Output-Marker — deshalb werden Dosis und Antwort
 * getrennt erhoben und über gleich lange Fenster verglichen.
 *
 * Deload-Robustheit ist eingebaut, nicht nachgereicht: lange Fenster, Maximum- bzw.
 * Median-Statistik und explizite Erkennung von Entlastungswochen. Im Taper sinkt die
 * Last, während die Leistung steigt — ein lastbasiertes Fortschrittsmaß wäre dort
 * aktiv irreführend.
 */
object ProgressionAnalyzer {

    private const val DEAD = 2.5    // Rauschband je Marker, in Prozent

    private fun pct(now: Double?, prev: Double?): Double? =
        if (now != null && prev != null && prev != 0.0) (now / prev - 1) * 100 else null

    private fun median(a: List<Double>): Double? {
        if (a.isEmpty()) return null
        val s = a.sorted(); val i = s.size / 2
        return if (s.size % 2 == 1) s[i] else (s[i - 1] + s[i]) / 2
    }

    fun analyze(
        wellness: List<WellnessDay>, sessions: List<Session>, cfg: AnalysisConfig,
        today: LocalDate, scan: TorqueScan? = null,
    ): Progression {
        val win = cfg.windowDays
        val confounded = cfg.confoundedDays
        val dayIdx = HashMap<String, Int>()
        fun idxOf(date: String): Int = dayIdx.getOrPut(date) {
            val d = runCatching { LocalDate.parse(date) }.getOrNull()
            if (d == null) Int.MIN_VALUE else (today.toEpochDay() - d.toEpochDay()).toInt()
        }
        fun inWin(date: String, from: Int, to: Int): Boolean { val a = idxOf(date); return a in from until to }

        val bikes = sessions.filter { it.type in Zones.CYCLING }

        // ---- 1) Leistung: bestes eFTP je Fenster ----
        var eNow = 0.0; var ePrev = 0.0; var eNowAt: Int? = null; var ePrevAt: Int? = null
        var eftpCount = 0
        bikes.forEach { s ->
            val e = s.eftp ?: return@forEach
            if (inWin(s.localDate, 0, win)) { eftpCount++; if (e > eNow) { eNow = e; eNowAt = idxOf(s.localDate) } }
            else if (inWin(s.localDate, win, 2 * win)) { eftpCount++; if (e > ePrev) { ePrev = e; ePrevAt = idxOf(s.localDate) } }
        }
        /* Trennschärfe der Maximum-Statistik: der zeitliche Abstand der Bestwerte zweier
           benachbarter Fenster kann zwischen zwei Tagen und der doppelten Fensterlänge
           liegen. Liegen beide nah an der gemeinsamen Grenze, wird die Differenz
           systematisch zu klein — der Fehler ist konservativ, gehört aber ausgewiesen. */
        val eftpSep = if (eNowAt != null && ePrevAt != null) abs(ePrevAt!! - eNowAt!!) else null

        // ---- 2) Aerobe Effizienz: NP/HF bei aeroben Einheiten ----
        val efNow = mutableListOf<Double>(); val efPrev = mutableListOf<Double>()
        var diag = ProgressionDiag(rides = bikes.size)
        bikes.forEach { s ->
            val cur = inWin(s.localDate, 0, win); val prv = inWin(s.localDate, win, 2 * win)
            if (!cur && !prv) return@forEach
            diag = diag.copy(ridesInWindow = diag.ridesInWindow + 1)
            val np = s.normalizedPower; val hr = s.avgHeartRate
            if (np == null || hr == null || hr < 60) return@forEach
            diag = diag.copy(withPowerHr = diag.withPowerHr + 1)
            if (s.movingTimeSec < 1800) return@forEach
            diag = diag.copy(longEnough = diag.longEnough + 1)
            if ((s.intensity ?: 0.0) > 0.85) return@forEach     // keine Intervalle/Rennen
            diag = diag.copy(aerobic = diag.aerobic + 1)
            if (cur) efNow += np / hr else efPrev += np / hr
        }
        diag = diag.copy(eftpValues = eftpCount)
        val efDelta = if (efNow.size >= 2 && efPrev.size >= 2) pct(median(efNow), median(efPrev)) else null

        // ---- 2b) Aerobe Entkopplung (Seiler): fallend ist gut ----
        val dcNow = mutableListOf<Double>(); val dcPrev = mutableListOf<Double>()
        bikes.forEach { s ->
            val v = s.decoupling ?: return@forEach
            if (s.movingTimeSec < 3600) return@forEach
            if ((s.intensity ?: 0.0) > 0.85) return@forEach
            if (inWin(s.localDate, 0, win)) dcNow += v else if (inWin(s.localDate, win, 2 * win)) dcPrev += v
        }
        val decNow = median(dcNow); val decPrev = median(dcPrev)
        // absolute Differenz in Prozentpunkten (Decoupling ist selbst schon eine Prozentzahl)
        val decDelta = if (dcNow.size >= 2 && dcPrev.size >= 2 && decNow != null && decPrev != null) decNow - decPrev else null

        // ---- 2c) Kraft: Leistung bei konditionierter Trittfrequenz, je Dauer getrennt ----
        val durations = Streams.DURATIONS.map { d ->
            var now = 0; var prev = 0; var nNow = 0; var nPrev = 0
            var nowAt: Int? = null; var prevAt: Int? = null
            bikes.forEach { s ->
                val t = s.torque ?: return@forEach
                val cur = inWin(s.localDate, 0, win); val prv = inWin(s.localDate, win, 2 * win)
                if (!cur && !prv) return@forEach
                val v = when (d.key) { "d60" -> t.d60; "d300" -> t.d300; else -> t.d600 }
                val c = when (d.key) { "d60" -> t.n60; "d300" -> t.n300; else -> t.n600 }
                if (cur) { if (v != null && v > now) { now = v; nowAt = idxOf(s.localDate) }; nNow += c }
                else { if (v != null && v > prev) { prev = v; prevAt = idxOf(s.localDate) }; nPrev += c }
            }
            /* Vergleich schon ab EINEM Fenster je Zeitraum berechnen — sonst bleibt bei
               realistisch dünner Datenlage nur ein Strich, obwohl beide Werte vorliegen.
               Dünne Basis wird gekennzeichnet und aus dem Urteil ausgeschlossen: ein
               Maximum aus einem einzigen Fenster ist zu unsicher für eine Entscheidung. */
            DurationProgress(
                key = d.key, label = d.label, note = d.note,
                now = now.takeIf { it > 0 }, prev = prev.takeIf { it > 0 },
                nNow = nNow, nPrev = nPrev,
                deltaPct = if (now > 0 && prev > 0) pct(now.toDouble(), prev.toDouble()) else null,
                thin = !(nNow >= 2 && nPrev >= 2),
                separationDays = if (nowAt != null && prevAt != null) abs(prevAt!! - nowAt!!) else null,
            )
        }

        val lcEfN = mutableListOf<Double>(); val lcEfP = mutableListOf<Double>()
        var tqN = 0.0; var tqP = 0.0
        bikes.forEach { s ->
            val t = s.torque ?: return@forEach
            val cur = inWin(s.localDate, 0, win); val prv = inWin(s.localDate, win, 2 * win)
            if (!cur && !prv) return@forEach
            t.efficiency?.let { if (cur) lcEfN += it else lcEfP += it }
            t.peakTorque30s?.let { if (cur) { if (it > tqN) tqN = it } else { if (it > tqP) tqP = it } }
        }
        val lcEfDelta = if (lcEfN.isNotEmpty() && lcEfP.isNotEmpty()) pct(median(lcEfN), median(lcEfP)) else null
        val lcEfThin = !(lcEfN.size >= 2 && lcEfP.size >= 2)

        // ---- 3) Belastung: CTL-Trend, Rampe, Deload ----
        val wByDate = wellness.associateBy { it.date }
        fun ctlNear(back: Int): Double? {
            for (off in listOf(0, 1, -1, 2, -2, 3, -3)) {
                val k = back + off; if (k < 0) continue
                wByDate[today.minusDays(k.toLong()).toString()]?.ctl?.let { return it }
            }
            return null
        }
        val ctlNow = ctlNear(0); val ctlPrev = ctlNear(win)
        val loadByDay = HashMap<String, Double>()
        sessions.forEach { loadByDay[it.localDate] = (loadByDay[it.localDate] ?: 0.0) + it.trainingLoad }
        fun weekLoad(from: Int, to: Int): Double {
            var s = 0.0; for (i in from until to) s += loadByDay[today.minusDays(i.toLong()).toString()] ?: 0.0
            return s
        }
        val week0 = weekLoad(0, 7); val week4avg = weekLoad(7, 35) / 4
        val deload = week4avg > 0 && week0 < 0.7 * week4avg

        // ---- 4) Intensitätsverteilung 28 Tage ----
        var z12 = 0; var z3 = 0; var z4p = 0
        bikes.forEach { s ->
            if (!s.hasZones || !inWin(s.localDate, 0, 28)) return@forEach
            z12 += Zones.secondsWhere(s.zoneSeconds) { it <= 2 }
            z3 += Zones.secondsWhere(s.zoneSeconds) { it == 3 }
            z4p += Zones.secondsWhere(s.zoneSeconds) { it >= 4 }
        }
        val zTot = z12 + z3 + z4p

        // ---- 5) HRV-Chronik: 28 Tage gegen die 28 davor ----
        val lnNow = mutableListOf<Double>(); val lnPrev = mutableListOf<Double>()
        wellness.forEach { d ->
            val v = d.hrv ?: return@forEach
            if (v <= 0 || d.date in confounded) return@forEach
            val a = idxOf(d.date)
            if (a in 0..27) lnNow += ln(v) else if (a in 28..55) lnPrev += ln(v)
        }
        val hrvChronNow = if (lnNow.size >= 10) exp(lnNow.average()) else null
        val hrvChronPrev = if (lnPrev.size >= 10) exp(lnPrev.average()) else null

        // ---- Verdikt ----
        val dose = pct(ctlNow, ctlPrev)?.let { if (it > 3) 1 else if (it < -3) -1 else 0 }

        /* Antwortseite MEHRGLEISIG und bewusst NICHT nur als Mittelwert. Würde nur
           gemittelt, verschwände eine echte Verbesserung in EINER Dimension zwischen
           flachen anderen — ein gezielter Kraftaufbau bei sonst gehaltener Form sähe wie
           ein Plateau aus, obwohl genau das trainiert wurde. Ein Plateau liegt
           physiologisch erst dann vor, wenn sich NIRGENDS etwas bewegt. */
        val markers = mutableListOf<MarkerDelta>()
        pct(if (eNow > 0) eNow else null, if (ePrev > 0) ePrev else null)?.let { markers += MarkerDelta("eFTP", it) }
        efDelta?.let { markers += MarkerDelta("aerobe Effizienz", it) }
        /* Entkopplung fällt ohne Verstärkungsfaktor ein: Ein Prozentpunkt zählt wie ein
           Prozent, nicht wie zwei. Der frühere Faktor 2 war willkürlich gesetzt und hat
           sich an einem Jahr echter Daten als klar zu großzügig erwiesen — die Entkopplung
           streut dort mit 6,4 Prozentpunkten um einen Median von 1,5 %, ist also der mit
           Abstand verrauschteste der vier Marker (die aerobe Effizienz kommt auf 7,5 %
           relative Streuung). Verdoppelt trug sie damit wiederholt allein ein Verdikt,
           obwohl der Unterschied im Rauschen lag. */
        decDelta?.let { markers += MarkerDelta("Entkopplung", -it) }             // fallend = gut
        durations.forEach { d -> if (!d.thin && d.deltaPct != null) markers += MarkerDelta(d.label, d.deltaPct) }
        if (!lcEfThin && lcEfDelta != null) markers += MarkerDelta("Kraft-Effizienz", lcEfDelta)

        val up = markers.filter { it.deltaPct > DEAD }
        val down = markers.filter { it.deltaPct < -DEAD }
        val respAvg = if (markers.isNotEmpty()) markers.map { it.deltaPct }.average() else null
        val rsp: Int? = when {
            markers.isEmpty() -> null
            up.isNotEmpty() && down.isEmpty() -> 1
            down.isNotEmpty() && up.isEmpty() -> -1
            up.isNotEmpty() && down.isNotEmpty() -> if (respAvg!! > DEAD) 1 else if (respAvg < -DEAD) -1 else 0
            else -> 0
        }

        var title: String; var text: String; var color: String
        var diagText: String? = null
        if (dose == null || rsp == null) {
            val miss = mutableListOf<String>()
            if (dose == null) miss += "CTL-Vergleich fehlt (heute ${ctlNow?.roundToInt() ?: "–"}, vor $win Tagen ${ctlPrev?.roundToInt() ?: "–"})"
            if (markers.none { it.name == "eFTP" }) miss += "eFTP-Werte: $eftpCount in beiden Fenstern — intervals.icu setzt sie nur bei maximalen Antritten"
            if (efDelta == null) miss += "aerobe Vergleichseinheiten: ${efPrev.size} im älteren / ${efNow.size} im aktuellen Fenster, mindestens 2 je Zeitraum nötig"
            title = "Noch zu wenig vergleichbare Daten"; color = "#8A97A8"; text = miss.joinToString(". ") + "."
            diagText = "Radeinheiten geladen: ${diag.rides} · in den beiden Fenstern: ${diag.ridesInWindow} · " +
                "mit Leistung und Herzfrequenz: ${diag.withPowerHr} · davon ≥30 min: ${diag.longEnough} · davon aerob: ${diag.aerobic}"
        } else if (dose >= 0 && rsp == 1) {
            val focused = up.size < markers.size
            color = "#3DDC97"
            title = if (focused) "Gezielte Verbesserung" else "Produktive Progression"
            text = if (focused)
                "Einzelne Antwortmarker steigen bei gleicher oder höherer Last, die übrigen halten — genau das Bild eines gezielten Schwerpunkts. Solange nichts nachgibt, ist das produktiv."
            else "Die Antwortmarker steigen bei gleicher oder höherer Last — Volumen und Intensität passen zum Formaufbau. Kurs halten."
        } else if (dose == 1 && rsp == 0) {
            color = "#FFC53D"; title = "Reiz kommt an, Antwort steht noch aus"
            text = "Last steigt, Leistung und Effizienz stagnieren. Das ist im Aufbau normal und noch kein Warnzeichen: messbare aerobe " +
                "Anpassungen brauchen typischerweise sechs bis acht Wochen, bei bereits gut Trainierten eher länger. Erst wenn auch der " +
                "nächste Vergleich nichts zeigt, fehlt Erholung oder der Reiz ist zu monoton."
        } else if (dose == 1 && rsp == -1) {
            color = "#FF5C5C"; title = "Last steigt, Antwort fällt"
            text = "Klassisches Muster unzureichender Erholung: mehr Dosis, weniger Wirkung. Eine Entlastungswoche ist hier meist produktiver als weiteres Draufsatteln."
        } else if (dose == -1 && rsp >= 0) {
            color = "#3DDC97"; title = "Entlastung wirkt"
            text = "Last gesunken, Leistung gehalten oder verbessert — genau das erwartete Bild in Deload oder Taper."
        } else if (dose == -1 && rsp == -1) {
            color = "#FFC53D"; title = "Detraining-Tendenz"
            text = "Last und Antwort fallen gemeinsam. Wenn das keine geplante Pause ist, fehlt Reiz."
        } else {
            color = "#FFC53D"; title = "Plateau"
            text = "Weder Last noch einer der Antwortmarker bewegt sich über das Rauschband hinaus. Für einen bereits gut Trainierten sind " +
                "sechs Wochen ohne messbare Änderung nicht ungewöhnlich — substanzielle Sprünge liegen eher bei acht bis zwölf Wochen."
        }
        if (up.isNotEmpty()) text += " Verbessert: ${up.joinToString(", ") { it.name }}."
        if (down.isNotEmpty()) text += " Rückläufig: ${down.joinToString(", ") { it.name }}."
        if (markers.isNotEmpty()) text += " Ausgewertete Marker: ${markers.joinToString(", ") { it.name }}."
        if (deload) text += " Aktuelle Woche ist eine Entlastungswoche (< 70 % der letzten vier Wochen) — im Trend berücksichtigt."
        val chronDelta = pct(hrvChronNow, hrvChronPrev)
        if (chronDelta != null && dose == 1 && chronDelta < -3)
            text += " Zusätzliche Warnung: HRV-Chronik ${String.format("%.1f", chronDelta).replace('.', ',')} % bei steigender Last — Zeichen für Maladaptation."

        return Progression(
            ok = true, windowDays = win,
            verdictTitle = title, verdictText = text, verdictColorHex = color,
            eftpNow = eNow.takeIf { it > 0 }?.roundToInt(), eftpPrev = ePrev.takeIf { it > 0 }?.roundToInt(),
            eftpDeltaPct = pct(if (eNow > 0) eNow else null, if (ePrev > 0) ePrev else null),
            eftpSeparationDays = eftpSep,
            efNow = median(efNow), efPrev = median(efPrev), efDeltaPct = efDelta,
            efN = efNow.size, efNPrev = efPrev.size,
            ctlNow = ctlNow, ctlPrev = ctlPrev, ctlDeltaPct = pct(ctlNow, ctlPrev),
            rampPerWeek = if (ctlNow != null && ctlPrev != null) (ctlNow - ctlPrev) / (win / 7.0) else null,
            deloadNow = deload,
            share12 = if (zTot > 0) z12 * 100.0 / zTot else null,
            share3 = if (zTot > 0) z3 * 100.0 / zTot else null,
            share4 = if (zTot > 0) z4p * 100.0 / zTot else null,
            zoneHours = if (zTot > 0) zTot / 3600.0 else null,
            hrvChronNow = hrvChronNow, hrvChronPrev = hrvChronPrev, hrvChronDeltaPct = chronDelta,
            decNow = decNow, decPrev = decPrev, decDeltaPp = decDelta,
            decN = dcNow.size, decNPrev = dcPrev.size,
            durations = durations,
            lcEfNow = median(lcEfN), lcEfPrev = median(lcEfP), lcEfDeltaPct = lcEfDelta,
            lcEfN = lcEfN.size, lcEfNPrev = lcEfP.size, lcEfThin = lcEfThin,
            peakTorqueNow = tqN.takeIf { it > 0 }, peakTorquePrev = tqP.takeIf { it > 0 },
            markersUsed = markers.map { it.name }, drivers = up.map { it.name }, decliners = down.map { it.name },
            diagText = diagText, diag = diag, torqueScan = scan,
        )
    }
}
