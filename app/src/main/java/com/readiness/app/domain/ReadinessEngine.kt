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
        val reco = ScoreEngine.buildRecommendation(score, metrics, load, limits)
        val prog = ProgressionAnalyzer.analyze(wellness, sessions, cfg, today, torqueScan)

        return ReadinessResult(
            score = score, baseScore = base.total, deduction = load.deduction,
            metrics = metrics, components = base.components, limitingFactors = limits,
            loadHistory = load, recommendation = reco, thresholds = thresholds, progression = prog,
        )
    }
}
