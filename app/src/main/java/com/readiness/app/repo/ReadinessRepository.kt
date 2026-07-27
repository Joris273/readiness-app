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

    val settings: SecurePrefs get() = prefs

    fun cached(): Snapshot? = snapshots.load()

    /**
     * Vollständiger Durchlauf. Muss außerhalb des Hauptthreads laufen.
     * @param streamBudget Anzahl neuer Stream-Abrufe; 0 nutzt nur den Cache.
     */
    fun refresh(streamBudget: Int? = null, today: LocalDate = LocalDate.now()): Pair<Snapshot, ReadinessResult> {
        val cfg = prefs.config()
        val raw = icu.load(cfg, today)
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
        val raw = icu.load(cfg, today)
        val enriched = torque.enrich(raw.sessions, raw.thresholds, cfg, today, budget = 6)
        val result = ReadinessEngine.evaluate(
            raw.wellness, enriched.sessions, raw.thresholds, cfg, today, enriched.scan)
        val snap = SnapshotMapper.map(result, cfg)
        snapshots.save(snap)
        return snap to (enriched.scan.missing > 0)
    }
}
