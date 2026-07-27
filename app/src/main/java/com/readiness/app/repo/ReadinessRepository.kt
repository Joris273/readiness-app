package com.readiness.app.repo

import android.content.Context
import com.readiness.app.data.IcuClient
import com.readiness.app.data.RawBundle
import com.readiness.app.data.RawStore
import com.readiness.app.data.SecurePrefs
import com.readiness.app.data.Snapshot
import com.readiness.app.data.SnapshotMapper
import com.readiness.app.data.SnapshotStore
import com.readiness.app.data.TorqueStore
import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.domain.ReadinessEngine
import com.readiness.app.domain.ReadinessResult
import java.time.LocalDate

/**
 * Orchestriert Beschaffung, Auswertung und Zwischenspeicherung.
 *
 * Leitgedanke der Zwischenspeicherung: Rohdaten werden EINMAL je Tag geholt, in voller
 * Tiefe, und danach nur noch neu ausgewertet. Ein Wechsel des Vergleichszeitraums oder
 * ein erneuter App-Start kommt damit ohne Netzabruf aus.
 */
class ReadinessRepository(context: Context) {

    private val prefs = SecurePrefs(context)
    private val client = IcuClient { prefs.apiKey to prefs.athlete }
    private val icu = IcuRepository(client)
    private val torqueStore = TorqueStore(context)
    private val torque = TorqueRepository(client, torqueStore)
    private val snapshots = SnapshotStore(context)
    private val rawStore = RawStore(context)

    private var memRaw: RawBundle? = null

    companion object {
        /** Rohdaten gelten vier Stunden als aktuell genug. */
        const val MAX_AGE_MS = 4 * 60 * 60 * 1000L
    }

    val settings: SecurePrefs get() = prefs

    fun cached(): Snapshot? = snapshots.load()
    fun cacheSizeKb(): Long = rawStore.sizeKb()

    /** Rohdaten aus Arbeitsspeicher, Platte oder Netz — in dieser Reihenfolge. */
    private fun raw(cfg: AnalysisConfig, today: LocalDate, forceNetwork: Boolean): RawBundle {
        if (!forceNetwork) {
            val candidate = memRaw ?: rawStore.load()?.also { memRaw = it }
            /* Wiederverwenden nur, wenn die Daten vom selben Tag stammen, tief genug
               reichen UND nicht zu alt sind. Ohne Altersgrenze würde die App den ganzen
               Tag auf dem Morgenstand hängen bleiben, obwohl Garmin und Karoo laufend
               nachliefern. */
            val ageOk = candidate != null && System.currentTimeMillis() - candidate.savedAt < MAX_AGE_MS
            if (candidate != null && ageOk && candidate.day == today.toString() &&
                candidate.fetchDays >= cfg.fetchDays) return candidate
        }
        val fresh = icu.fetchRaw(cfg, today)
        memRaw = fresh
        rawStore.save(fresh)
        return fresh
    }

    private fun evaluate(bundle: RawBundle, cfg: AnalysisConfig, today: LocalDate,
                         streamBudget: Int): Pair<Snapshot, ReadinessResult> {
        val mapped = icu.map(bundle, today)
        val withWork = torque.detectRecentTorqueWork(mapped.sessions, mapped.thresholds, today, streamBudget > 0)
        val enriched = torque.enrich(withWork, mapped.thresholds, cfg, today, streamBudget)
        val result = ReadinessEngine.evaluate(
            mapped.wellness, enriched.sessions, mapped.thresholds, cfg, today, enriched.scan)
        val snap = SnapshotMapper.map(result, cfg)
        snapshots.save(snap)
        return snap to result
    }

    /** Vollständiger Durchlauf mit Netzabruf. Muss außerhalb des Hauptthreads laufen. */
    fun refresh(streamBudget: Int? = null, today: LocalDate = LocalDate.now(),
                forceNetwork: Boolean = true): Pair<Snapshot, ReadinessResult> {
        val cfg = prefs.config()
        return evaluate(raw(cfg, today, forceNetwork), cfg, today, streamBudget ?: cfg.streamBudget)
    }

    /** Nur neu auswerten — ohne Netz, für den Wechsel des Vergleichszeitraums. */
    fun reevaluate(today: LocalDate = LocalDate.now()): Pair<Snapshot, ReadinessResult> {
        val cfg = prefs.config()
        return evaluate(raw(cfg, today, forceNetwork = false), cfg, today, streamBudget = 0)
    }

    /**
     * Schlanker Lauf für das Widget: frische Rohdaten, neu bewerten, KEINE Stream-Abrufe.
     * Damit bleibt der Hintergrundaufwand auf wenige HTTP-Anfragen begrenzt.
     */
    fun refreshLight(today: LocalDate = LocalDate.now()): Snapshot {
        val cfg = prefs.config()
        return evaluate(raw(cfg, today, forceNetwork = true), cfg, today, streamBudget = 0).first
    }

    /** Kraftdaten weiter auffüllen und neu bewerten — für den Hintergrundlauf. */
    fun fillTorqueStep(today: LocalDate = LocalDate.now()): Pair<Snapshot, Boolean> {
        val cfg = prefs.config()
        val bundle = raw(cfg, today, forceNetwork = false)
        val (snap, result) = evaluate(bundle, cfg, today, streamBudget = 6)
        return snap to ((result.progression.torqueScan?.missing ?: 0) > 0)
    }
}
