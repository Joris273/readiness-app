package com.readiness.app.domain

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Belastungshistorie: Trainingstage in Folge, Foster-Monotonie, 48-Stunden-Regel.
 */
object LoadHistoryAnalyzer {

    /**
     * Harter Intensitätsreiz — KONZENTRATION statt reiner Summe.
     *
     * Die 48-h-Regel zielt auf gezielte, gebündelte Intensitätsarbeit. Über eine lange
     * Ausfahrt summieren sich Zonenzeiten aber nebenbei: 20 Minuten Z4, verteilt als
     * 30-Sekunden-Antritte über drei Stunden, sind physiologisch etwas völlig anderes als
     * 4×5 min an der Schwelle. Deshalb zählt zusätzlich der ANTEIL an der Fahrzeit. Sehr
     * große Absolutmengen gelten unabhängig davon als Reiz, weil sie auch eingebettet in
     * eine lange Ausfahrt einen echten Trainingsreiz darstellen.
     */
    private data class Rule(val secs: Int, val share: Double, val abs: Int)
    private val Z5 = Rule(360, 0.05, 900)      // ≥6 min UND ≥5 % — oder ≥15 min absolut
    private val Z6 = Rule(180, 0.025, 480)     // ≥3 min UND ≥2,5 % — oder ≥8 min absolut
    private val Z4 = Rule(1200, 0.15, 2700)    // ≥20 min UND ≥15 % — oder ≥45 min absolut
    private const val TORQUE_MIN_SEC = 480     // ab 8 min Drehmomentarbeit gilt als Qualitätsreiz

    fun analyze(sessions: List<Session>, ctl: Double?, hrvSuppressed: Boolean, today: LocalDate): LoadHistory {
        val byDay = HashMap<Int, DayLoad>()
        sessions.forEach { s ->
            val date = runCatching { LocalDate.parse(s.localDate) }.getOrNull() ?: return@forEach
            val ago = (today.toEpochDay() - date.toEpochDay()).toInt()
            if (ago < 0 || ago > 10) return@forEach
            val o = byDay.getOrPut(ago) { DayLoad() }
            o.load += s.trainingLoad
            o.durationSec += s.movingTimeSec
            val isBike = s.type in Zones.CYCLING
            val isRun = s.type in Zones.RUNNING
            /* Zonen und Intensität nur aus Sportarten mit belastbaren Zonen. E-Bike,
               Wandern usw. fließen nur über die Trainingslast ein — die steckt ohnehin
               in CTL/ATL. Sonst würden unkalibrierte Zonen harmlose Pendelfahrten als
               harten Reiz flaggen. */
            if (isBike || isRun) {
                o.maxIf = max(o.maxIf, s.intensity ?: 0.0)
                o.z5plus += Zones.secondsWhere(s.zoneSeconds) { it >= 5 }
                o.z6plus += Zones.secondsWhere(s.zoneSeconds) { it >= 6 }
                o.zonedSec += s.movingTimeSec
                o.hasZones = o.hasZones || s.hasZones
                if (isBike) {
                    o.z4 += Zones.secondsWhere(s.zoneSeconds) { it == 4 }
                    o.torque += s.torqueWorkSec
                }
            }
        }

        val ctlSafe = if (ctl != null && ctl > 0) ctl else 40.0
        fun isTrain(o: DayLoad?) = o != null && o.load >= max(20.0, 0.4 * ctlSafe)

        fun reasons(o: DayLoad?): List<String> {
            if (o == null) return emptyList()
            val r = mutableListOf<String>()
            if (!o.hasZones) {
                if (o.maxIf >= 0.85) r += "IF ${String.format("%.2f", o.maxIf).replace('.', ',')} ohne Zonendaten"
                return r
            }
            fun add(label: String, secs: Int, rule: Rule) {
                val share = if (o.zonedSec > 0) secs / o.zonedSec else 0.0
                if (secs >= rule.abs) r += "${(secs / 60.0).roundToInt()} min $label (absolut ≥ ${rule.abs / 60} min)"
                else if (secs >= rule.secs && share >= rule.share)
                    r += "${(secs / 60.0).roundToInt()} min $label = ${(share * 100).roundToInt()} % der Fahrzeit"
            }
            add("Z5+", o.z5plus, Z5); add("Z6+", o.z6plus, Z6); add("Z4", o.z4, Z4)
            // Kraftausdauer: von Zonenzeit allein nicht erfasst — gleiche Watt bei 60 rpm
            // sind ein anderer Reiz als bei 90 rpm.
            if (o.torque >= TORQUE_MIN_SEC) r += "${(o.torque / 60.0).roundToInt()} min Kraftausdauer (≤70 rpm über 85 % FTP)"
            return r
        }

        /* Plausibilitätsregel: ein Tag, der nicht einmal als Trainingstag zählt, kann kein
           harter Trainingstag sein. */
        fun isHard(o: DayLoad?) = isTrain(o) && reasons(o).isNotEmpty()
        fun isBig(o: DayLoad?) = o != null && o.load >= 1.5 * ctlSafe

        /* Reiztyp bestimmen. Ein VO2max-Block und eine Schwelleneinheit belasten
           unterschiedliche Systeme — das ist die Grundlage dafür, am Folgetag einen
           ERGÄNZENDEN statt eines gleichartigen Reizes vorzuschlagen. */
        fun typeOf(o: DayLoad?): StimulusType {
            if (o == null || !isHard(o)) return StimulusType.NONE
            /* Verglichen werden NORMIERTE Anteile, nicht rohe Sekunden.
               Sechs Minuten Z5+ und zwanzig Minuten Z4 sind nach unseren eigenen
               Kriterien gleichwertige Reize — ein direkter Sekundenvergleich benachteiligt
               deshalb systematisch die kurzen, harten Intervalle. An einer echten
               VO2max-Einheit mit 581 s Z5+ und 407 s Z4 fiel das auf: der Sekundenvergleich
               ergab „gemischt", obwohl der Z5+-Anteil gemessen an seiner Referenz fast
               viermal so hoch liegt. */
            val vo2 = (o.z5plus / 900.0)
            val thr = ((o.z4 + o.torque) / 2400.0)
            val vol = if (isBig(o)) 1.0 else 0.0
            return when {
                vo2 == 0.0 && thr == 0.0 -> if (vol > 0) StimulusType.VOLUME else StimulusType.MIXED
                vo2 >= 1.5 * thr -> StimulusType.VO2MAX
                thr >= 1.5 * vo2 -> StimulusType.THRESHOLD
                else -> StimulusType.MIXED
            }
        }

        /* Schweregrad des Reizes als relatives Maß statt eines pauschalen Abzugs.
           Zehn Minuten Z5+ sind ein anderer Reiz als vierzig Minuten Schwelle, und ein
           Flachabzug behandelte beide gleich. Die Bezugsgrößen entsprechen dem, was in
           der Praxis eine volle Qualitätseinheit ausmacht. */
        fun severityOf(o: DayLoad?): Double {
            if (o == null || !isHard(o)) return 0.0
            val vo2 = (o.z5plus / 900.0).coerceAtMost(1.0)          // 15 min Z5+ = voll
            val thr = ((o.z4 + o.torque) / 2400.0).coerceAtMost(1.0) // 40 min Schwelle = voll
            val vol = (o.load / (1.5 * ctlSafe)).coerceAtMost(1.0)
            return (maxOf(vo2, thr) * 0.75 + vol * 0.25).coerceIn(0.0, 1.0)
        }
        fun stat(o: DayLoad?) = o?.let {
            DayStat(it.z5plus, it.z6plus, it.z4, it.torque, it.zonedSec, it.load.roundToInt(), it.maxIf, it.hasZones)
        }

        val todayO = byDay[0]
        val yO = byDay[1]
        val hardY = isHard(yO)
        val bigY = isBig(yO)

        var consec = 0
        for (i in 1..10) { if (isTrain(byDay[i])) consec++ else break }

        /* Aufeinanderfolgende Qualitätstage — Grundlage der Blockbegrenzung.
           Gezählt wird ab dem letzten Tag MIT Reiz: solange heute noch nicht trainiert
           wurde, beginnt die Serie bei gestern. Andernfalls stünde die Serie den ganzen
           Vormittag auf null und die Blockgrenze griffe nie. */
        var qStreak = 0
        val qStart = if (isHard(byDay[0])) 0 else 1
        for (i in qStart..10) { if (isHard(byDay[i])) qStreak++ else break }

        val daily = (0..6).map { byDay[it]?.load ?: 0.0 }
        val mean = daily.sum() / 7
        val sd = sqrt(daily.sumOf { (it - mean) * (it - mean) } / 7)
        val monotony = if (sd > 0) mean / sd else if (mean > 0) 3.0 else 0.0
        val weekLoad = daily.sum()
        val chronic = ctlSafe * 7

        var ded = 0
        val notes = mutableListOf<String>()
        when {
            consec >= 6 -> { ded += 14; notes += "$consec Trainingstage ohne Pause" }
            consec == 5 -> { ded += 6; notes += "5 Trainingstage in Folge" }
            consec == 4 && monotony > 2 -> { ded += 4; notes += "4 Trainingstage bei hoher Monotonie" }
        }
        val hardWhy = if (hardY) reasons(yO) else emptyList()
        val severity = severityOf(yO)
        if (hardY) {
            /* Abzug proportional zum Reiz statt pauschal: eine kurze VO2max-Serie kostet
               weniger Erholung als ein voller Schwellenblock. Der Score bildet damit ab,
               wie viel von gestern noch verarbeitet wird — die Entscheidung, ob heute
               trotzdem Qualität möglich ist, trifft die Empfehlung anhand der Marker. */
            val d = (4 + 10 * severity).roundToInt()
            ded += d
            notes += "gestern intensiv (${hardWhy.joinToString(", ")})"
        }
        if (monotony > 2 && weekLoad > chronic) {
            ded += 6; notes += "hohe Monotonie ${String.format("%.1f", monotony).replace('.', ',')} bei hoher Wochenlast"
        }
        ded = min(26, ded)

        return LoadHistory(
            deduction = ded, notes = notes, consecutiveDays = consec,
            hardYesterday = hardY, bigYesterday = bigY, monotony = monotony, weekLoad = weekLoad,
            capIntensity = hardY || bigY,
            // Ruhetag nur bei konvergenter Evidenz, nicht mechanisch
            forceRest = consec >= 6 || (hardY && hrvSuppressed) || (monotony > 2 && consec >= 5 && weekLoad > chronic),
            hardReasons = hardWhy, yesterday = stat(yO),
            trainedToday = isTrain(todayO), hardToday = isHard(todayO),
            todayReasons = if (isHard(todayO)) reasons(todayO) else emptyList(), today = stat(todayO),
            qualityStreak = qStreak, yesterdayType = typeOf(yO), todayType = typeOf(todayO),
            yesterdaySeverity = severity,
        )
    }
}
