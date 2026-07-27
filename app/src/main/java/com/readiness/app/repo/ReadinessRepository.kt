package com.readiness.app.repo

import android.content.Context
import com.readiness.app.data.IcuClient
import com.readiness.app.data.SecurePrefs
import com.readiness.app.data.Snapshot
import com.readiness.app.data.SnapshotStore
import com.readiness.app.data.TorqueStore
import com.readiness.app.domain.ReadinessEngine
import com.readiness.app.domain.ReadinessResult
import com.readiness.app.data.SnapshotMapper
import java.time.LocalDate

/**
 * Orchestriert Beschaffung, Auswertung und Zwischenspeicherung. Bewusst dünn: alle
 * Entscheidungen liegen in der Domäne, alle Netzzugriffe im IcuRepository.
 */
class ReadinessRepository(context: Context) {

    private val prefs = SecurePrefs(context)
    private val client = IcuClient { prefs.apiKey to prefs.athlete }
    private val icu = IcuRepository(client)
    private val torque = TorqueRepository(client, TorqueStore(context))
    private val snapshots = SnapshotStore(context)

    /* Rohdaten des letzten Abrufs zwischenhalten.
       Ein Wechsel des Vergleichszeitraums ändert nur die AUSWERTUNG, nicht die Daten —
       solange die vorhandene Historie tief genug reicht. Ohne diesen Zwischenspeicher
       hätte jeder Klick auf „2 Zyklen" einen vollständigen Neuabruf über bis zu 288 Tage
       ausgelöst, was die spürbare Verzögerung erklärt. */
    private data class RawCache(val raw: IcuRepository.RawData, val days: Int, val day: LocalDate, val at: Long)
    private var rawCache: RawCache? = null

    val settings: SecurePrefs get() = prefs

    fun cached(): Snapshot? = snapshots.load()

    /** Reicht der Zwischenspeicher für diese Konfiguration? */
    private fun usableCache(cfg: com.readiness.app.domain.AnalysisConfig, today: LocalDate): IcuRepository.RawData? {
        val c = rawCache ?: return null
        val fresh = System.currentTimeMillis() - c.at < 10 * 60_000
        return if (c.day == today && c.days >= cfg.historyDays && fresh) c.raw else null
    }

    /**
     * Vollständiger Durchlauf. Muss außerhalb des Hauptthreads laufen.
     * @param streamBudget Anzahl neuer Stream-Abrufe; 0 nutzt nur den Cache.
     */
    fun refresh(streamBudget: Int? = null, today: LocalDate = LocalDate.now(),
                allowCache: Boolean = false): Pair<Snapshot, ReadinessResult> {
        val cfg = prefs.config()
        val raw = (if (allowCache) usableCache(cfg, today) else null) ?: icu.load(cfg, today).also {
            rawCache = RawCache(it, cfg.historyDays, today, System.currentTimeMillis())
        }
        val withTorqueWork = torque.detectRecentTorqueWork(raw.sessions, raw.thresholds, today)
        val enriched = torque.enrich(withTorqueWork, raw.thresholds, cfg, today, streamBudget ?: cfg.streamBudget)
        val result = ReadinessEngine.evaluate(
            raw.wellness, enriched.sessions, raw.thresholds, cfg, today, enriched.scan)
        val snap = SnapshotMapper.map(result, cfg)
        snapshots.save(snap)
        return snap to result
    }

    /** Nur die Kraftdaten weiter auffüllen und neu bewerten — für den Hintergrundlauf. */
    fun fillTorqueStep(today: LocalDate = LocalDate.now()): Pair<Snapshot, Boolean> {
        val cfg = prefs.config()
        val raw = usableCache(cfg, today) ?: icu.load(cfg, today).also {
            rawCache = RawCache(it, cfg.historyDays, today, System.currentTimeMillis())
        }
        val enriched = torque.enrich(raw.sessions, raw.thresholds, cfg, today, budget = 6)
        val result = ReadinessEngine.evaluate(
            raw.wellness, enriched.sessions, raw.thresholds, cfg, today, enriched.scan)
        val snap = SnapshotMapper.map(result, cfg)
        snapshots.save(snap)
        return snap to (enriched.scan.missing > 0)
    }
}
