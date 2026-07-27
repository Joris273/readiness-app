package com.readiness.app

import com.readiness.app.domain.*
import org.junit.Test
import java.time.LocalDate
import kotlin.math.ln

/**
 * Cross-Check gegen die Referenzwerte des HTML-Prototyps.
 *
 * Der Zweck ist nicht, die Kotlin-Fassung „irgendwie" zu testen, sondern zu belegen,
 * dass sie bei identischen Eingaben ZAHLENGLEICH mit der validierten JS-Fassung rechnet.
 * Beim ersten Port hat genau diese Suite drei Fehler gefunden, die der Compiler nicht sah.
 *
 * Läuft als reiner JVM-Test — die Domänenschicht hat keine Android-Abhängigkeiten.
 */
class CrossCheckTest {

    @Test
    fun domainMatchesPrototype() {

    val today = LocalDate.of(2026, 7, 27)
    fun d(n: Int) = today.minusDays(n.toLong()).toString()
    var pass = 0; var fail = 0

    fun wellnessSeries(hrv: Double = 44.0, rhr: Double = 42.0, sleepSec: Double = 23400.0,
                       ctl: Double = 57.0, atl: Double = 55.0, days: Int = 120) =
        (days - 1 downTo 0).map { WellnessDay(d(it), ctl, atl, hrv, rhr, sleepSec, 71.0) }

    fun ride(ago: Int, load: Double = 80.0, ifv: Double = 0.72, dur: Double = 5400.0,
             z: Map<Int, Int> = mapOf(1 to 1800, 2 to 2600), type: String = "Ride",
             torqueWork: Int = 0, tq: TorqueMetrics? = null, np: Double? = 230.0,
             hr: Double? = 140.0, dec: Double? = 4.0, eftp: Double? = null) =
        Session("a$ago", type, false, d(ago), dur, load, ifv, eftp, np, hr, dec, z, z.isNotEmpty(), tq, torqueWork)

    fun check(label: String, sessions: List<Session>, cfg: AnalysisConfig = AnalysisConfig(),
              wl: List<WellnessDay> = wellnessSeries(), expect: String) {
        val r = ReadinessEngine.evaluate(wl, sessions, Thresholds(295, 285, 277), cfg, today)
        val ok = r.recommendation.title == expect
        println("  ${if (ok) "OK  " else "FAIL"} ${label.padEnd(46)} ${r.score.toString().padStart(3)} | ${r.recommendation.title}")
        if (!ok) println("        erwartet: $expect")
        if (ok) pass++ else fail++
    }

    println("=== TEILSCORES (Referenzwerte aus dem JS-Prototyp) ===")
    fun expect(label: String, got: Int?, want: Int?) {
        val ok = got == want
        println("  ${if (ok) "OK  " else "FAIL"} ${label.padEnd(38)} $got (erwartet $want)")
        if (ok) pass++ else fail++
    }
    expect("scoreTsb(-5,6)", ScoreEngine.scoreTsb(-5.6), 88)
    expect("scoreTsb(+5) Peak", ScoreEngine.scoreTsb(5.0), 100)
    expect("scoreTsb(+20)", ScoreEngine.scoreTsb(20.0), 80)
    expect("scoreTsb(+30) Detraining", ScoreEngine.scoreTsb(30.0), 65)
    expect("scoreTsb(null)", ScoreEngine.scoreTsb(null), null)
    expect("scoreTsb(NaN) Guard", ScoreEngine.scoreTsb(Double.NaN), null)
    val b = ln(44.0)
    expect("scoreHrv 44 ms im Band", ScoreEngine.scoreHrv(ln(44.0), b, 0.06), 100)
    expect("scoreHrv 48 ms darueber", ScoreEngine.scoreHrv(ln(48.0), b, 0.06), 100)
    expect("scoreHrv 38 ms (-14 %)", ScoreEngine.scoreHrv(ln(38.0), b, 0.06), 44)
    expect("scoreHrv 34 ms (Boden)", ScoreEngine.scoreHrv(ln(34.0), b, 0.06), 15)
    expect("scoreRestingHr(0)", ScoreEngine.scoreRestingHr(0.0), 100)
    expect("scoreRestingHr(+5) stetig", ScoreEngine.scoreRestingHr(5.0), 50)
    expect("scoreRestingHr(+6) stetig", ScoreEngine.scoreRestingHr(6.0), 45)
    expect("scoreSleep Garmin 71", ScoreEngine.scoreSleep(71.0, 5.9, 6.1, 6.1), 71)
    expect("scoreSleep Bedarf 6,1 / 6,4 h", ScoreEngine.scoreSleep(null, 6.4, 6.1, 6.1), 100)

    println("\n=== ZONENPARSER: Sweet-Spot-Band darf nicht mitzaehlen ===")
    val zt = listOf(
        Zones.ZoneEntry("Z1", null, 3216), Zones.ZoneEntry("Z2", null, 1731),
        Zones.ZoneEntry("Z3", null, 837), Zones.ZoneEntry("Z4", null, 237),
        Zones.ZoneEntry("Z5", null, 85), Zones.ZoneEntry("Z6", null, 50),
        Zones.ZoneEntry("Z7", null, 4), Zones.ZoneEntry("SS", null, 396))
    val parsed = Zones.parseZoneSeconds(zt)
    expect("Z4 (echt 237 s)", Zones.secondsWhere(parsed) { it == 4 }, 237)
    expect("Z5+ (echt 139 s)", Zones.secondsWhere(parsed) { it >= 5 }, 139)
    expect("Z6+ (echt 54 s)", Zones.secondsWhere(parsed) { it >= 6 }, 54)
    expect("Summe = Fahrzeit 6160 s", Zones.secondsWhere(parsed) { it >= 1 }, 6160)

    println("\n=== BELASTUNGSERKENNUNG ===")
    check("Normaltag", emptyList(), expect = "Grünes Licht für Intensität")
    check("Gestern Intervalle (25 min Z4 von 75)",
        listOf(ride(1, 85.0, 0.86, 4500.0, mapOf(4 to 1500, 5 to 60))), expect = "Nur Grundlage / Z2")
    check("Gestern Social Ride (22 min Z4 von 180)",
        listOf(ride(1, 60.0, 0.68, 10800.0, mapOf(2 to 8000, 4 to 1320, 5 to 240))), expect = "Grünes Licht für Intensität")
    check("Gestern E-Bike-Pendeln",
        listOf(ride(1, 9.0, 0.92, 1500.0, mapOf(4 to 400, 5 to 900), "EBikeRide")), expect = "Grünes Licht für Intensität")
    check("Gestern lockerer Dauerlauf (30 min Z4)",
        listOf(ride(1, 55.0, 0.78, 3000.0, mapOf(4 to 1800), "Run")), expect = "Grünes Licht für Intensität")
    check("Gestern Laufintervalle (18 min Z5+)",
        listOf(ride(1, 70.0, 0.92, 3600.0, mapOf(4 to 600, 5 to 1080), "Run")), expect = "Nur Grundlage / Z2")
    check("6 Trainingstage in Folge",
        (1..6).map { ride(it, 90.0, 0.75, 5400.0, mapOf(2 to 5000, 4 to 200)) }, expect = "Ruhetag empfohlen")
    check("Heute Qualitaetsreiz absolviert",
        listOf(ride(0, 72.0, 0.84, 3600.0, mapOf(4 to 1200), "VirtualRide", torqueWork = 1200)),
        expect = "Qualitätseinheit erledigt")

    println("\n=== ERHOLUNGSMARKER ===")
    val supp = wellnessSeries().toMutableList().also { it[it.size - 1] = it.last().copy(hrv = 38.0) }
    check("HRV -14 % allein", emptyList(), wl = supp, expect = "Nur Grundlage / Z2")
    val suppRhr = supp.toMutableList().also { it[it.size - 1] = it.last().copy(restingHr = 46.0) }
    check("HRV -14 % + Ruhepuls +4", emptyList(), wl = suppRhr, expect = "Ruhetag empfohlen")
    check("TSB -25", emptyList(), wl = wellnessSeries(ctl = 60.0, atl = 85.0), expect = "Ruhetag empfohlen")

    println("\n=== STOERFAKTOREN ===")
    val low = wellnessSeries().toMutableList().also { it[it.size - 1] = it.last().copy(hrv = 32.0, restingHr = 47.0, sleepScore = 58.0) }
    check("Alkohol (extern)", emptyList(),
        AnalysisConfig(confounders = mapOf(d(0) to listOf("alcohol"))), low, "Nur Grundlage / Z2")
    check("Krankheit (medizinisch)", emptyList(),
        AnalysisConfig(confounders = mapOf(d(0) to listOf("illness"))), low, "Ruhetag empfohlen")
    check("Krankheit bei sonst gruen", emptyList(),
        AnalysisConfig(confounders = mapOf(d(0) to listOf("illness"))), wellnessSeries(), "Ruhetag empfohlen")
    val hrvOnly = wellnessSeries().toMutableList().also { it[it.size - 1] = it.last().copy(hrv = 32.0) }
    check("Messartefakt: HRV faellt aus der Gewichtung", emptyList(),
        AnalysisConfig(confounders = mapOf(d(0) to listOf("artifact"))), hrvOnly, "Grünes Licht für Intensität")
    check("Gleicher Tag OHNE Markierung", emptyList(), AnalysisConfig(), hrvOnly, "Nur Grundlage / Z2")

    println("\n=== PROGRESSION ===")
    fun progScen(label: String, c0: Double, c1: Double, ef0: Double, ef1: Double,
                 dec0: Double, dec1: Double, lc0: Int? = null, lc1: Int? = null,
                 cycles: Int = 1, expect: String) {
        val wl = (119 downTo 0).map { WellnessDay(d(it), c0 + (c1 - c0) * (119 - it) / 119.0, 50.0, 44.0, 42.0, 23400.0, 71.0) }
        val ss = (83 downTo 0 step 3).map { i ->
            val rec = i < 42
            val tq = if (lc0 != null) TorqueMetrics(
                d60 = if (rec) lc1 else lc0, n60 = 3,
                d300 = if (rec) (lc1!! * 0.8).toInt() else (lc0 * 0.8).toInt(), n300 = 2, p300 = 300,
                efficiency = if (rec) 1.80 * (lc1!!.toDouble() / lc0) else 1.80, peakTorque30s = 44.0) else null
            ride(i, 80.0, 0.72, 5400.0, mapOf(1 to 1800, 2 to 2600, 4 to 400),
                np = (if (rec) ef1 else ef0) * 140, dec = if (rec) dec1 else dec0, tq = tq)
        }
        val p = ProgressionAnalyzer.analyze(wl, ss, AnalysisConfig(cycles = cycles), today)
        val ok = p.verdictTitle == expect
        println("  ${if (ok) "OK  " else "FAIL"} ${label.padEnd(46)} ${p.verdictTitle}")
        if (!ok) println("        erwartet: $expect | drivers=${p.drivers} decliners=${p.decliners}")
        if (ok) pass++ else fail++
    }
    progScen("Aufbau: Last hoch, alles besser", 45.0, 62.0, 1.60, 1.70, 5.1, 3.2, 300, 330, expect = "Produktive Progression")
    progScen("Nur Kraft steigt, Rest flach", 57.0, 57.0, 1.65, 1.65, 4.0, 4.0, 300, 330, expect = "Gezielte Verbesserung")
    progScen("Echtes Plateau", 57.0, 57.0, 1.65, 1.65, 4.0, 4.0, 320, 320, expect = "Plateau")
    progScen("Last hoch, Antwort faellt", 45.0, 62.0, 1.70, 1.58, 3.2, 5.4, 340, 305, expect = "Last steigt, Antwort fällt")
    progScen("Taper", 65.0, 52.0, 1.62, 1.68, 4.2, 3.6, 310, 325, expect = "Entlastung wirkt")
    progScen("Detraining", 65.0, 50.0, 1.70, 1.58, 3.4, 4.6, 340, 305, expect = "Detraining-Tendenz")

    println("\n=== STREAMS: Abtastrate, Pausen, Artefakte ===")
    fun stream(n: Int, step: Int, f: (Int) -> Triple<Double, Double, Double>): Streams.Normalized? {
        val w = ArrayList<Double?>(); val c = ArrayList<Double?>(); val h = ArrayList<Double?>(); val t = ArrayList<Double?>()
        for (i in 0 until n) { val (a, bb, cc) = f(i * step); w += a; c += bb; h += cc; t += (i * step).toDouble() }
        return Streams.normalize(w, c, h, t)
    }
    val s1 = stream(3600, 1) { i -> if (i in 601..1199) Triple(270.0, 60.0, 165.0) else Triple(150.0, 90.0, 140.0) }!!
    val m1 = Streams.metrics(s1)
    expect("1 Hz: 5-min-Kraft", m1.d300, 270)
    expect("1 Hz: resampled=false", if (s1.resampled) 1 else 0, 0)
    val s4 = stream(900, 4) { i -> if (i in 601..1199) Triple(270.0, 60.0, 165.0) else Triple(150.0, 90.0, 140.0) }!!
    expect("4-s-Raster: auf Sekunden normalisiert", if (s4.resampled) 1 else 0, 1)
    expect("4-s-Raster: Laenge in s", s4.n, 3597)
    expect("4-s-Raster: 5-min-Kraft korrekt", Streams.metrics(s4).d300, 270)
    val spike = stream(600, 1) { i -> Triple(if (i == 300) 3000.0 else 200.0, if (i == 301) 250.0 else 80.0, 150.0) }!!
    expect("Artefakt 3000 W verworfen", if (spike.watts[300].isNaN()) 1 else 0, 1)
    expect("Artefakt 250 rpm verworfen", if (spike.cadence[301].isNaN()) 1 else 0, 1)

    println("\n============================================")
    println("  $pass bestanden, $fail fehlgeschlagen")
    println("============================================")
    org.junit.Assert.assertEquals("Es sind $fail Prüfungen fehlgeschlagen", 0, fail)

    }
}
