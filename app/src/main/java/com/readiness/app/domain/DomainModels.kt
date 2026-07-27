package com.readiness.app.domain

/**
 * Domänenmodelle als typisierte data classes.
 *
 * Im Prototyp waren dies ungetypte Objektbeutel mit über vierzig Feldern — beim Port
 * die häufigste Fehlerquelle, weil ein Tippfehler im Feldnamen still zu `undefined`
 * wird statt zu einem Fehler. Hier fängt das der Compiler ab.
 *
 * Bewusst OHNE Serialisierungs-Annotationen und ohne Android-Importe: die Domäne bleibt
 * damit reines Kotlin und ist ohne Emulator testbar. Die Übersetzung in ein speicherbares
 * Format übernimmt die Datenschicht.
 */

/** Eine Trainingseinheit, bereits auf das reduziert, was die Auswertung braucht. */
data class Session(
    val id: String,
    val type: String,
    val trainer: Boolean = false,
    val localDate: String,          // ISO yyyy-MM-dd, lokales Datum der Einheit
    val movingTimeSec: Double = 0.0,
    val trainingLoad: Double = 0.0,
    val intensity: Double? = null,  // bereits normalisiert (Bruch, nicht Prozent)
    val eftp: Double? = null,
    val normalizedPower: Double? = null,
    val avgHeartRate: Double? = null,
    val decoupling: Double? = null,
    val zoneSeconds: Map<Int, Int> = emptyMap(),   // echte Zonen Z1..Z7, ohne Bänder
    val hasZones: Boolean = false,
    /** Aus den Roh-Streams gewonnene Kraftkennwerte; null bis ausgewertet. */
    val torque: TorqueMetrics? = null,
    /** Sekunden Drehmomentarbeit heute/gestern (≥85 % FTP bei ≤70 rpm). */
    val torqueWorkSec: Int = 0,
)

/** Ein Tageseintrag aus der Wellness-Reihe. */
data class WellnessDay(
    val date: String,
    val ctl: Double? = null,
    val atl: Double? = null,
    val hrv: Double? = null,
    val restingHr: Double? = null,
    val sleepSeconds: Double? = null,
    val sleepScore: Double? = null,
)

/** Schwellenwerte aus den Sport-Einstellungen. */
data class Thresholds(
    val outdoorFtp: Int? = null,
    val indoorFtp: Int? = null,
    val eftp: Int? = null,
    val lthr: Int? = null,
    val maxHr: Int? = null,
    val staleMessage: String? = null,
)

/** Aufbereitete Tageskennwerte, die in den Score fließen. */
data class Metrics(
    val dataDate: String? = null,
    val ctl: Double? = null,
    val atl: Double? = null,
    val tsb: Double? = null,
    val hrv: Double? = null,
    val hrvDate: String? = null,
    val hrvDeviationPct: Double? = null,
    val hrvLn: Double? = null,
    val hrvLnBase: Double? = null,
    val hrvLnSd: Double? = null,
    val hrvBandLo: Double? = null,
    val hrvBandHi: Double? = null,
    val hrvBeyond: Double = 0.0,      // SWC-Einheiten unter der unteren Bandkante
    val hrvSuppressed: Boolean = false,
    val hrvAbove: Boolean = false,
    val hrvUnusual: Boolean = false,
    // Wochentrend nach Plews: 7-Tage-Mittel gegen vier unabhängige Wochenblöcke
    val hrvWeek: Double? = null,
    val hrvWeekRef: Double? = null,
    val hrvWeekDevPct: Double? = null,
    val hrvWeekDown: Boolean = false,     // Anzeige ab 0,5 SD
    val hrvWeekAlarm: Boolean = false,    // Handlung erst ab 1,5 SD
    val hrvWeekUp: Boolean = false,
    val restingHr: Double? = null,
    val restingHrBase: Double? = null,
    val restingHrDiff: Double? = null,
    val sleepScore: Double? = null,
    val sleepHours: Double? = null,
    val sleepAvgHours: Double? = null,
    val sleep7Effective: Double? = null,  // inkl. Powernaps
    val sleep30: Double? = null,
    val sleepNeed: Double? = null,
    val sleepNeedManual: Boolean = false,
    val napMinutes: Int = 0,
    val sleepDeficit: Double? = null,
    val acwr: Double? = null,
    // Störfaktor-Status des HRV-Messtags
    val confounded: Boolean = false,
    val confounderLabel: String? = null,
    val confIllness: Boolean = false,
    val confInvalid: Boolean = false,
    val confExternal: Boolean = false,
)

/** Tagesaggregat für die Belastungshistorie. */
data class DayLoad(
    var load: Double = 0.0,
    var maxIf: Double = 0.0,
    var z5plus: Int = 0,
    var z6plus: Int = 0,
    var z4: Int = 0,
    var torque: Int = 0,
    var durationSec: Double = 0.0,
    var zonedSec: Double = 0.0,     // nur Einheiten, die Zonen beisteuern
    var hasZones: Boolean = false,
)

data class DayStat(
    val z5: Int, val z6: Int, val z4: Int, val torque: Int,
    val zonedSec: Double, val load: Int, val maxIf: Double, val hasZones: Boolean,
)

data class LoadHistory(
    val deduction: Int,
    val notes: List<String>,
    val consecutiveDays: Int,
    val hardYesterday: Boolean,
    val bigYesterday: Boolean,
    val monotony: Double,
    val weekLoad: Double,
    val capIntensity: Boolean,
    val forceRest: Boolean,
    val hardReasons: List<String>,
    val yesterday: DayStat?,
    val trainedToday: Boolean,
    val hardToday: Boolean,
    val todayReasons: List<String>,
    val today: DayStat?,
)

data class ScoreComponent(
    val id: String,
    val name: String,
    val weight: Double,
    val sub: Int?,
    val explanation: String,
    val colorHex: String,
    val effectiveWeight: Double = 0.0,
)

data class BaseScore(val total: Int?, val components: List<ScoreComponent>, val renormalized: Boolean)

enum class Severity { AMBER, RED }
data class LimitingFactor(val label: String, val severity: Severity)

enum class Verdict { GREEN, AMBER, RED, DONE, UNKNOWN }
data class Recommendation(
    val verdict: Verdict,
    val colorHex: String,
    val title: String,
    val text: String,
)

/** Kraftkennwerte einer Einheit, aus den Roh-Streams gewonnen. */
data class TorqueMetrics(
    val d60: Int? = null, val n60: Int = 0,
    val d300: Int? = null, val n300: Int = 0,
    val d600: Int? = null, val n600: Int = 0,
    val p300: Int? = null,
    val efficiency: Double? = null,     // W/bpm bei ≤70 rpm, ab 5 min
    val efficiencyW: Int? = null,
    val efficiencyHr: Int? = null,
    val peakTorque30s: Double? = null,  // Nm, nur Orientierung
    val resampled: Boolean = false,
)

data class MarkerDelta(val name: String, val deltaPct: Double)

data class DurationProgress(
    val key: String, val label: String, val note: String,
    val now: Int?, val prev: Int?, val nNow: Int, val nPrev: Int,
    val deltaPct: Double?, val thin: Boolean, val separationDays: Int?,
)

data class ProgressionDiag(
    val rides: Int = 0, val ridesInWindow: Int = 0, val withPowerHr: Int = 0,
    val longEnough: Int = 0, val aerobic: Int = 0, val eftpValues: Int = 0,
    val npKey: String? = null, val hrKey: String? = null,
)

data class TorqueScan(val total: Int, val missing: Int, val openOlder: Int)

data class Progression(
    val ok: Boolean,
    val windowDays: Int,
    val verdictTitle: String,
    val verdictText: String,
    val verdictColorHex: String,
    val eftpNow: Int? = null, val eftpPrev: Int? = null,
    val eftpDeltaPct: Double? = null, val eftpSeparationDays: Int? = null,
    val efNow: Double? = null, val efPrev: Double? = null,
    val efDeltaPct: Double? = null, val efN: Int = 0, val efNPrev: Int = 0,
    val ctlNow: Double? = null, val ctlPrev: Double? = null,
    val ctlDeltaPct: Double? = null, val rampPerWeek: Double? = null,
    val deloadNow: Boolean = false,
    val share12: Double? = null, val share3: Double? = null, val share4: Double? = null,
    val zoneHours: Double? = null,
    val hrvChronNow: Double? = null, val hrvChronPrev: Double? = null, val hrvChronDeltaPct: Double? = null,
    val durations: List<DurationProgress> = emptyList(),
    val lcEfNow: Double? = null, val lcEfPrev: Double? = null,
    val lcEfDeltaPct: Double? = null, val lcEfN: Int = 0, val lcEfNPrev: Int = 0, val lcEfThin: Boolean = true,
    val peakTorqueNow: Double? = null, val peakTorquePrev: Double? = null,
    val markersUsed: List<String> = emptyList(),
    val drivers: List<String> = emptyList(),
    val decliners: List<String> = emptyList(),
    val diagText: String? = null,
    val diag: ProgressionDiag = ProgressionDiag(),
    val torqueScan: TorqueScan? = null,
)

/** Das vollständige Auswertungsergebnis der Domänenschicht. */
data class ReadinessResult(
    val score: Int?,
    val baseScore: Int?,
    val deduction: Int,
    val metrics: Metrics,
    val components: List<ScoreComponent>,
    val limitingFactors: List<LimitingFactor>,
    val loadHistory: LoadHistory,
    val recommendation: Recommendation,
    val thresholds: Thresholds,
    val progression: Progression,
)
