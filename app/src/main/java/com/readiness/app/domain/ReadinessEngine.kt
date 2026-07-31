package com.readiness.app.domain

import java.time.LocalDate

/**
 * Setzt die Domänenschritte zusammen. Reine Funktion: keine Netzzugriffe, kein
 * Speicherzugriff, keine Konfigurationsquelle — alles kommt als Argument herein.
 *
 * Damit ist die gesamte wissenschaftliche Logik ohne Android-Umgebung testbar und der
 * Cross-Check gegen den JS-Prototyp reproduzierbar.
 */
object ReadinessEngine {

    fun evaluate(
        wellness: List<WellnessDay>,
        sessions: List<Session>,
        thresholds: Thresholds,
        cfg: AnalysisConfig,
        today: LocalDate = LocalDate.now(),
        torqueScan: TorqueScan? = null,
    ): ReadinessResult {
        val metrics = MetricsBuilder.build(wellness, sessions, cfg, today)
        val load = LoadHistoryAnalyzer.analyze(
            sessions, metrics.ctl, metrics.hrvSuppressed && !metrics.confounded, today)
        val base = ScoreEngine.buildScore(metrics)
        val limits = ScoreEngine.limitingFactors(base.components, metrics)
        val score = base.total?.let { maxOf(0, it - load.deduction) }
        val reco = ScoreEngine.buildRecommendation(score, metrics, load, limits, base.components, base.total)
        val prog = ProgressionAnalyzer.analyze(wellness, sessions, cfg, today, torqueScan)
        val chart = buildChart(wellness, sessions, today)

        return ReadinessResult(
            score = score, baseScore = base.total, deduction = load.deduction,
            metrics = metrics, components = base.components, limitingFactors = limits,
            loadHistory = load, recommendation = reco, thresholds = thresholds, progression = prog,
            chart = chart,
        )
    }

    /** Tagesreihe der letzten 30 Tage für die Verlaufsdiagramme. */
    private fun buildChart(wellness: List<WellnessDay>, sessions: List<Session>, today: LocalDate): List<ChartPoint> {
        val loadByDay = HashMap<String, Double>()
        sessions.forEach { loadByDay[it.localDate] = (loadByDay[it.localDate] ?: 0.0) + it.trainingLoad }
        val byDate = wellness.associateBy { it.date }
        return (29 downTo 0).map { back ->
            val d = today.minusDays(back.toLong()).toString()
            val w = byDate[d]
            ChartPoint(
                date = d, ctl = w?.ctl, atl = w?.atl,
                tsb = if (w?.ctl != null && w.atl != null) w.ctl - w.atl else null,
                hrv = w?.hrv, load = loadByDay[d] ?: 0.0,
            )
        }
    }
}
