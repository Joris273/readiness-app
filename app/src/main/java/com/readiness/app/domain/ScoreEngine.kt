package com.readiness.app.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Teilscores und Gesamtbewertung.
 *
 * Gewichtung: HRV 33 %, Form 28 %, Schlaf 28 %, Ruhepuls 11 %. Fehlende Komponenten
 * fallen heraus und die Gewichte werden neu normiert. Die Ruhepuls-Gewichtung ist
 * bewusst niedrig: HRV und Ruhepuls sind beide autonome Marker und teilredundant, der
 * Ruhepuls ist dabei der langsamere und verrauschtere von beiden.
 */
object ScoreEngine {

    /** Ungültige Einzelwerte dürfen nicht in den Score wandern — sie gelten als „nicht gemessen". */
    private fun num(v: Double?): Double? =
        if (v == null || v.isNaN() || v.isInfinite()) null else v

    fun scoreTsb(value: Double?): Int? {
        val tsb = num(value) ?: return null
        return when {
            tsb in -5.0..15.0 -> (100 - abs(tsb - 5)).roundToInt()
            tsb > 15 && tsb <= 25 -> (90 - (tsb - 15) * 2).roundToInt()
            tsb > 25 -> 65
            tsb >= -15 -> (90 + (tsb + 5) * 3).roundToInt()
            tsb >= -30 -> max(20.0, 60 + (tsb + 15) * 2.7).roundToInt()
            else -> 15
        }
    }

    /**
     * HRV gegen die individuelle SWC-Bandbreite (Plews et al.): Ln-rMSSD, Baseline als
     * 7-Tage-Mittel, SWC = 0,5 × SD mit einem Rausch-Floor von etwa 5 %.
     *
     * Werte OBERHALB des Bandes werden nicht abgewertet — ein hoher rMSSD-Einzelwert ist
     * zunächst gute vagale Erholung. Unterhalb fällt die Kurve linear in SWC-Einheiten;
     * die Steigung ist bewusst flach genug, dass zwischen moderatem und schwerem Einbruch
     * noch unterschieden wird. Für die ENTSCHEIDUNG sorgt ohnehin das binäre Flag
     * `hrvSuppressed`, nicht die Punktzahl.
     */
    fun scoreHrv(lnToday: Double?, lnBase: Double?, lnSd: Double?): Int? {
        val t = num(lnToday) ?: return null
        val b = num(lnBase) ?: return null
        val diff = t - b
        val swc = 0.5 * max(lnSd ?: 0.0, 0.0488)
        if (diff >= -swc) return 100
        val beyond = (-diff - swc) / swc
        return max(15.0, 95 - beyond * 13).roundToInt()
    }

    /**
     * Schlaf gegen den INDIVIDUELLEN Bedarf, nicht gegen eine Populationszahl: der
     * Schlafbedarf streut zwischen Personen erheblich, eine feste Stundenzahl wäre eine
     * Fremdannahme über den Nutzer.
     */
    fun scoreSleep(sleepScore: Double?, durationH: Double?, avgH: Double?, need: Double?): Int? {
        val sc = num(sleepScore)
        if (sc != null) return sc.roundToInt().coerceIn(0, 100)
        val dur = num(durationH) ?: return null
        val target = num(need) ?: num(avgH) ?: 7.5
        var s = min(100.0, dur / target * 100).roundToInt()
        val avg = num(avgH)
        if (avg != null && dur < avg - 1) s -= 15
        return max(0, s)
    }

    fun scoreRestingHr(diffValue: Double?): Int? {
        val diff = num(diffValue) ?: return null
        return when {
            diff <= 0 -> 100
            diff <= 3 -> (100 - diff * 10).roundToInt()
            diff <= 5 -> (70 - (diff - 3) * 10).roundToInt()
            // stetig an den Wert 50 bei +5 bpm anschließen
            else -> max(20.0, 50 - (diff - 5) * 5).roundToInt()
        }
    }

    private fun f1(v: Double?) = v?.let { String.format("%.1f", it).replace('.', ',') } ?: "–"
    private fun f0(v: Double?) = v?.roundToInt()?.toString() ?: "–"
    private fun sgn(v: Double?) = if (v != null && v >= 0) "+" else ""

    fun buildScore(m: Metrics): BaseScore {
        val tsbWord = m.tsb?.let {
            when { it > 15 -> "sehr frisch"; it >= -5 -> "im optimalen Bereich"; it >= -15 -> "ermüdet"; else -> "stark ermüdet" }
        }
        val band = if (m.hrvBandLo != null && m.hrvBandHi != null)
            "Dein Normalband liegt derzeit bei ${f0(m.hrvBandLo)}–${f0(m.hrvBandHi)} ms. " else null

        val comps = listOf(
            ScoreComponent("tsb", "Form (TSB)", 0.28, scoreTsb(m.tsb),
                if (m.tsb == null) "Kein CTL/ATL verfügbar."
                else "TSB ${f1(m.tsb)} — $tsbWord (CTL ${f0(m.ctl)}, ATL ${f0(m.atl)}).", "#A78BFA"),

            ScoreComponent("hrv", "HRV vs. Baseline", 0.33,
                if (m.confInvalid) null else scoreHrv(m.hrvLn, m.hrvLnBase, m.hrvLnSd),
                when {
                    m.confInvalid -> "Messung vom ${m.hrvDate} als Artefakt markiert — nicht bewertet, Gewicht auf die übrigen Komponenten verteilt."
                    m.hrv == null -> "Keine HRV-Messung in den letzten Tagen."
                    band == null -> "rMSSD ${f0(m.hrv)} ms. Zu wenig Verlauf, um deine persönliche Normalbandbreite zu bestimmen."
                    else -> "rMSSD ${f0(m.hrv)} ms (${sgn(m.hrvDeviationPct)}${f1(m.hrvDeviationPct)} % vs. 7-T-Schnitt). " + band +
                        when {
                            m.hrvSuppressed && m.confounded -> "Heute darunter — Ursache extern angegeben: ${m.confounderLabel}, kein Trainingsstress-Signal."
                            m.hrvSuppressed -> "Heute darunter — Erholungssignal, heute keine harte Intensität."
                            m.hrvUnusual -> "Heute deutlich darüber. Das ist zunächst gute vagale Erholung und wird nicht abgewertet. " +
                                "Ungewöhnlich hohe Einzelwerte können aber auch durch Messartefakte entstehen — falls die Messung unsauber war, markiere den Tag als Störfaktor."
                            m.hrvAbove -> "Heute darüber — gute Erholung, volle Punktzahl."
                            else -> "Heute mittendrin, also normale Erholungslage."
                        }
                }, "#4CC3FF"),

            ScoreComponent("sleep", "Schlaf & Erholung", 0.28,
                scoreSleep(m.sleepScore, m.sleepHours, m.sleepAvgHours, m.sleepNeed),
                (when {
                    m.sleepScore != null -> "Garmin-Schlafscore ${m.sleepScore.roundToInt()}/100" +
                        (m.sleepHours?.let { ", ${f1(it)} h geschlafen" } ?: "") + "."
                    m.sleepHours != null -> "${f1(m.sleepHours)} h Schlaf (Wochenmittel ${f1(m.sleepAvgHours)} h)."
                    else -> "Keine Schlafdaten übertragen."
                }) + (m.sleep7Effective?.let {
                    " 7-Tage-Mittel ${f1(it)} h (inkl. eingetragener Powernaps)" +
                        (m.sleepNeed?.let { n -> " gegenüber deinem Bedarf ${f1(n)} h" +
                            (if (m.sleepNeedManual) " (selbst gesetzt)." else " (aus deiner Historie).") } ?: ".")
                } ?: ""), "#3DDC97"),

            ScoreComponent("rhr", "Ruhepuls-Trend", 0.11, scoreRestingHr(m.restingHrDiff),
                if (m.restingHrDiff == null) "Kein Ruhepuls verfügbar."
                else "RHR ${f0(m.restingHr)} bpm, ${sgn(m.restingHrDiff)}${f1(m.restingHrDiff)} bpm gegenüber dem 7-Tage-Schnitt (${f1(m.restingHrBase)} bpm).",
                "#FFC53D"),
        )

        val available = comps.filter { it.sub != null }
        if (available.isEmpty()) return BaseScore(null, comps, true)
        val wSum = available.sumOf { it.weight }
        val withWeights = comps.map { c ->
            if (c.sub != null) c.copy(effectiveWeight = c.weight / wSum) else c
        }
        val total = withWeights.filter { it.sub != null }
            .sumOf { it.sub!! * it.effectiveWeight }.roundToInt()
        return BaseScore(total, withWeights, available.size < comps.size)
    }

    /**
     * Nicht-kompensatorische Sicherung („limitierender Faktor").
     *
     * Ein gewichteter Mittelwert erlaubt, dass eine starke Domäne eine kritisch schwache
     * überdeckt — physiologisch ist Erholung aber kein Mittelwert: der schwächste
     * Teilbereich begrenzt die Belastbarkeit.
     *
     * Autonome Marker dürfen einen Ruhetag allerdings nicht im Alleingang erzwingen. Ein
     * einzelner HRV-Einbruch ist ein Signal, aber tagesvariabel; erst wenn ein zweiter,
     * unabhängiger Marker mitzieht, ist die Evidenz konvergent.
     */
    fun limitingFactors(comps: List<ScoreComponent>, m: Metrics): List<LimitingFactor> {
        val out = mutableListOf<LimitingFactor>()
        val sleepC = comps.firstOrNull { it.id == "sleep" }

        comps.forEach { c ->
            val sub = c.sub ?: return@forEach
            val autonomic = c.id == "hrv" || c.id == "rhr"
            val corroborated =
                (c.id == "hrv" && m.restingHrDiff != null && m.restingHrDiff >= 2) ||
                (c.id == "rhr" && m.hrvSuppressed && !m.confounded) ||
                (sleepC?.sub != null && sleepC.sub <= 50) ||
                (m.tsb != null && m.tsb < -15)
            val cap = autonomic && (m.confounded || !corroborated)

            if (c.id == "hrv") {
                if (m.confInvalid) return@forEach            // keine gültige Messung → kein Befund
                /* HRV nach der Abweichung in SWC-Einheiten einstufen, nicht nach der Punktzahl:
                   die Punkteskala ist eine Darstellungsentscheidung, die Bandabweichung dagegen
                   das physiologische Maß. */
                val bey = m.hrvBeyond
                val suffix = when {
                    m.confounded -> " — extern verursacht (${m.confounderLabel})"
                    cap -> " — Einzelbefund, von den übrigen Markern nicht bestätigt"
                    else -> " — durch weitere Marker bestätigt"
                }
                if (bey >= 4.5) out += LimitingFactor(
                    "${c.name} ${if (cap) "deutlich reduziert" else "stark unterdrückt"} (${f1(bey)}× unter deinem Normalband)$suffix",
                    if (cap) Severity.AMBER else Severity.RED)
                else if (bey >= 2.5) out += LimitingFactor(
                    "${c.name} unter dem Normalband (${f1(bey)}× Bandbreite)", Severity.AMBER)
                return@forEach
            }

            val suffix = if (!autonomic) "" else when {
                m.confounded -> " — extern verursacht (${m.confounderLabel})"
                cap -> " — Einzelbefund, von den übrigen Markern nicht bestätigt"
                else -> " — durch weitere Marker bestätigt"
            }
            if (sub <= 25) out += LimitingFactor(
                "${c.name} ${if (cap) "deutlich reduziert" else "kritisch niedrig"} ($sub/100)$suffix",
                if (cap) Severity.AMBER else Severity.RED)
            else if (sub <= 40) out += LimitingFactor("${c.name} deutlich reduziert ($sub/100)$suffix", Severity.AMBER)
        }

        /* Schlaf: individuell statt normativ. Eine absolute Stundenzahl als Verbotsgrenze
           wäre eine Populationsannahme, die die tatsächlich GEMESSENE Physiologie
           überstimmt. Ein Defizit deckelt deshalb nur bei konvergenter Evidenz. */
        val deficit = m.sleepDeficit
        if (deficit != null && deficit >= 0.75) {
            val corr = mutableListOf<String>()
            if (m.hrvSuppressed && !m.confounded) corr += "HRV unter SWC"
            if (m.restingHrDiff != null && m.restingHrDiff >= 2) corr += "Ruhepuls +${f1(m.restingHrDiff)}"
            if (corr.isNotEmpty()) {
                val lbl = "Schlaf ${f1(deficit)} h unter deinem Bedarf (${f1(m.sleep7Effective)} h im Wochenschnitt vs. ${f1(m.sleepNeed)} h)"
                out += LimitingFactor("$lbl — bestätigt durch ${corr.joinToString(", ")}",
                    if (deficit >= 1.5 && corr.size >= 2) Severity.RED else Severity.AMBER)
            }
        }
        /* Absoluter Sicherheitsboden: unterhalb etwa fünf Stunden ist die Evidenz für
           Leistungs- und Immuneinbußen so konsistent, dass sie nicht von Gewöhnung
           aufgehoben wird. */
        val s7 = m.sleep7Effective
        if (s7 != null && s7 < 5.0) out += LimitingFactor("Wochenschnitt unter 5 h Schlaf (${f1(s7)} h)", Severity.AMBER)

        return out
    }

    private data class Level(val verdict: Verdict, val color: String, val title: String, val text: String)

    private val RED = Level(Verdict.RED, "#FF5C5C", "Ruhetag empfohlen",
        "Heute komplett pausieren oder maximal lockeres Ausrollen (< 45 min, Z1).")
    private val AMBER = Level(Verdict.AMBER, "#FFC53D", "Nur Grundlage / Z2",
        "Lockere Grundlagenfahrt in Z1–Z2, keine Intervalle, kein Krafttraining an der Grenze.")
    private val GREEN = Level(Verdict.GREEN, "#3DDC97", "Grünes Licht für Intensität",
        "Erholung, Form und Belastungsmuster passen. Schwellenintervalle, VO2max oder ein langer Grundlagenblock sind heute gut platziert.")

    /**
     * @param score angezeigter Tageswert (Basis abzüglich Belastung) — nur für die Texte
     * @param baseScore reiner Erholungszustand aus den Messwerten, OHNE Belastungsabzug
     *
     * Die Ampel richtet sich nach dem BASISWERT, nicht nach dem angezeigten Score.
     * Andernfalls wirkt die Belastung doppelt: einmal, indem sie den Score senkt, und
     * ein zweites Mal über die Belastungsregeln unten. Genau das hätte einen zweiten
     * Qualitätstag allein rechnerisch verhindert, obwohl die Marker ihn erlauben — und
     * damit wieder die starre Abstandsregel durch die Hintertür eingeführt.
     *
     * Die Aufgabenteilung ist deshalb: die Messwerte sagen, wie erholt du BIST; die
     * Belastungsregeln sagen, was angesichts des zuletzt Trainierten SINNVOLL ist.
     */
    fun buildRecommendation(score: Int?, m: Metrics, lh: LoadHistory, limits: List<LimitingFactor>,
                            comps: List<ScoreComponent> = emptyList(), baseScore: Int? = null): Recommendation {
        if (score == null) return Recommendation(Verdict.UNKNOWN, "#8A97A8", "Zu wenig Daten",
            "Es liegen nicht genug Messwerte vor, um eine Empfehlung abzuleiten.")

        val band = baseScore ?: score
        var r = if (band >= 78) GREEN else if (band >= 58) AMBER else RED
        val notes = mutableListOf<String>()

        if (m.tsb != null && m.tsb < -20) { r = RED; notes += "TSB unter −20 (tiefe Ermüdung)" }
        if (lh.forceRest) { r = RED; notes += "Belastungsmuster + Erholungslage sprechen für Pause (${lh.notes.joinToString(", ")})" }
        else if (lh.capIntensity && r == GREEN) {
            /* Hier stand zuvor eine starre 48-Stunden-Regel: gestern hart, also heute
               höchstens Grundlage. Die Trainingsliteratur trägt das für gut Trainierte
               nicht. Rønnestad und Kollegen zeigen, dass Blöcke mit HIT an
               aufeinanderfolgenden Tagen bei gleichem Gesamtumfang GRÖSSERE Zuwächse in
               VO2max und Leistung bringen als die klassische Verteilung auf zwei
               getrennte Tage pro Woche. Und die Studien zur HRV-gesteuerten Steuerung
               (Vesterinen, Meta-Analyse Düking) treffen die Entscheidung nicht am
               Kalender, sondern an den Erholungsmarkern: liegt die HRV im individuellen
               Normalband, ist ein Qualitätsreiz erlaubt — auch am Folgetag.

               Deshalb entscheidet jetzt die gemessene Erholung, nicht der Abstand. Ein
               zweiter Qualitätstag ist zulässig, wenn die Marker unauffällig sind; er
               wird nur gedeckelt, wenn sie es nicht sind. Als Sicherung bleibt die
               Blockgrenze: nach drei Qualitätstagen in Folge ist Erholung fällig, denn
               genau so sind die Blöcke in den Studien aufgebaut — Verdichtung UND
               anschließende echte Entlastung. */
            val recoveryClear = !m.hrvSuppressed && !m.hrvWeekAlarm &&
                (m.restingHrDiff == null || m.restingHrDiff < 2.0) &&
                (comps.firstOrNull { it.id == "sleep" }?.sub ?: 100) >= 60 &&
                (m.tsb == null || m.tsb > -15) &&
                limits.none { it.severity == Severity.RED }

            if (lh.qualityStreak >= 3) {
                r = AMBER
                notes += "dritter Qualitätstag in Folge — hier endet auch in der Blockmethodik der " +
                    "Verdichtungsblock; jetzt Entlastung, sonst kippt der Reiz in unproduktive Ermüdung"
            } else if (!lh.hardYesterday) {
                r = AMBER
                notes += "gestern sehr großer Umfangstag — heute Grundlage statt Intensität"
            } else if (!recoveryClear) {
                r = AMBER
                notes += "gestern harter Reiz und die Erholungsmarker sind nicht unauffällig — " +
                    "heute Grundlage; ein zweiter Qualitätstag wäre erst bei klarer Erholungslage sinnvoll"
            } else {
                // Marker unauffällig: Qualität bleibt möglich, aber mit anderem Schwerpunkt
                val suggestion = when (lh.yesterdayType) {
                    StimulusType.VO2MAX -> "Gestern lag der Reiz bei VO2max. Heute ist ein zweiter Qualitätstag " +
                        "vertretbar, sinnvollerweise mit anderem Schwerpunkt — Schwelle oder Tempo statt erneut " +
                        "kurzer Maximalintervalle."
                    StimulusType.THRESHOLD -> "Gestern lag der Reiz an der Schwelle. Heute wären kurze VO2max-Intervalle " +
                        "die sinnvollere Ergänzung als ein zweiter Schwellenblock."
                    StimulusType.VOLUME -> "Gestern war vor allem Umfang. Ein Intensitätsreiz ist heute vertretbar."
                    else -> "Ein zweiter Qualitätstag ist heute vertretbar."
                }
                notes += "$suggestion Danach ist ein Entlastungstag fällig " +
                    "(${lh.qualityStreak + 1}. Qualitätstag in Folge)"
            }
        }
        if (m.hrvSuppressed && !m.confounded && r == GREEN) { r = AMBER; notes += "HRV unter der individuellen Normalbandbreite" }

        /* Abfallender Wochentrend: der validierte Frühindikator für nicht-funktionelle
           Überlastung (Plews). Er deckelt die Intensität, erzwingt aber keine Pause —
           ein Trend über Wochen ist ein Planungssignal, kein Tagesbefund. */
        if (m.hrvWeekAlarm && !m.confounded) {
            if (r == GREEN) r = AMBER
            notes += "HRV-Wochenmittel ${f1(m.hrvWeekDevPct)} % unter den vier Wochen davor — gradueller Abfall des " +
                "7-Tage-Mittels ist der Frühindikator für Überlastung; Belastung in den nächsten Tagen eher zurücknehmen"
        }

        limits.forEach { l ->
            if (l.severity == Severity.RED) { r = RED; notes += "limitierender Faktor: ${l.label}" }
            else if (r == GREEN) { r = AMBER; notes += "limitierender Faktor: ${l.label}" }
        }

        if (m.tsb != null && m.tsb > 20 && r == GREEN) notes += "TSB sehr hoch — Form gut, aber Fitness sinkt bei weiterer Pause"
        if (m.acwr != null && m.acwr > 1.5) notes += "ACWR ${String.format("%.2f", m.acwr).replace('.', ',')} (grobe Orientierung, kein Risiko-Gate)"

        when {
            m.confIllness -> {
                r = RED
                notes += "Krankheit/Infekt angegeben — Training pausieren, bis du symptomfrei bist, und danach schrittweise wieder aufbauen"
            }
            m.confInvalid && !m.confExternal ->
                notes += "HRV-Messung als Artefakt markiert — sie fließt heute nicht in den Score ein, die übrigen Komponenten tragen ihn"
            m.confExternal -> {
                val trainingReason = lh.forceRest || (m.tsb != null && m.tsb < -20) || limits.any { it.severity == Severity.RED }
                if (r == RED && !trainingReason) r = AMBER
                if (r == GREEN) r = AMBER
                notes += "HRV-Absenkung extern verursacht (${m.confounderLabel}) — kein Signal für Trainingsüberlastung; " +
                    "Wert aus Baseline und SWC ausgeschlossen. Heute reduzierte Kapazität, den Trainingsplan aber nicht umbauen"
            }
        }

        val morning = Recommendation(r.verdict, r.color, r.title,
            r.text + if (notes.isNotEmpty()) " Hinweis: " + notes.joinToString("; ") + "." else "")

        /* Ist heute bereits trainiert worden, wechselt die Fragestellung: der Score wurde
           aus den Werten der Nacht gebildet und beschreibt die Bereitschaft VOR der
           Einheit. Eine Empfehlung „grünes Licht für Intensität" wäre danach sinnlos. */
        if (lh.trainedToday) {
            val parts = mutableListOf<String>()
            val mins = lh.today?.let { (it.zonedSec / 60).roundToInt() }
            parts += if (lh.hardToday)
                "Heute wurde bereits ein Qualitätsreiz gesetzt" +
                    (if (lh.todayReasons.isNotEmpty()) " (${lh.todayReasons.joinToString(", ")})" else "") +
                    (if (mins != null) ", $mins min" else "") + "."
            else "Heute wurde bereits trainiert" + (if (mins != null) ": $mins min" else "") +
                    (lh.today?.let { ", Last ${it.load}" } ?: "") + "."
            parts += "Der Score $score stammt aus den Nachtwerten und beschreibt deine Bereitschaft von heute früh, also vor dieser Einheit."
            parts += if (lh.hardToday) "Für morgen greift damit die 48-h-Regel: kein zweiter Intensitätstag, sondern Grundlage oder Ruhe."
                     else "Ein zusätzlicher harter Reiz heute wäre nur sinnvoll, wenn die Einheit wirklich locker war und die Erholungslage passt."
            if (r == RED) parts += "Beachte: Die Erholungsmarker sprachen heute früh gegen eine Belastung — beobachte die Reaktion morgen besonders genau."
            else if (r == AMBER && lh.hardToday) parts += "Beachte: Die Morgenlage sprach für Grundlage statt Intensität — plane morgen entsprechend konservativ."
            if (notes.isNotEmpty()) parts += "Aus der Morgenlage: " + notes.joinToString("; ") + "."
            return Recommendation(Verdict.DONE, "#6EA8FF",
                if (lh.hardToday) "Qualitätseinheit erledigt" else "Einheit erledigt", parts.joinToString(" "))
        }
        return morning
    }

    fun scoreWord(verdict: Verdict): String = when (verdict) {
        Verdict.DONE -> "heute früh"
        Verdict.GREEN -> "Bereit"
        Verdict.AMBER -> "Angeschlagen"
        Verdict.RED -> "Erholung nötig"
        Verdict.UNKNOWN -> ""
    }
}
