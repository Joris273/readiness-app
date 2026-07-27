package com.readiness.app.repo

import com.readiness.app.data.ActivityDto
import com.readiness.app.data.IcuClient
import com.readiness.app.data.SportSettingsDto
import com.readiness.app.data.WellnessDto
import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.domain.Session
import com.readiness.app.domain.Thresholds
import com.readiness.app.domain.WellnessDay
import com.readiness.app.domain.Zones
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Beschafft Rohdaten und übersetzt sie in Domänenmodelle. Rechnet selbst nichts aus,
 * was zur Auswertung gehört — diese Trennung ersetzt die Ladefunktion des Prototyps,
 * die Abruf, Berechnung und Darstellung in einer einzigen Prozedur vermischt hatte.
 */
class IcuRepository(private val client: IcuClient) {

    data class RawData(
        val wellness: List<WellnessDay>,
        val sessions: List<Session>,
        val thresholds: Thresholds,
        val athleteName: String?,
    )

    fun load(cfg: AnalysisConfig, today: LocalDate = LocalDate.now()): RawData {
        val newest = today.toString()
        val oldest = today.minusDays(cfg.historyDays.toLong()).toString()

        val wellness = client.wellness(oldest, newest).sortedBy { it.id }
        var activities = client.activities(oldest, newest)
        val settings = client.sportSettings()
        val name = client.profile()?.name

        /* Selbstheilung: fehlen Leistungs- oder HF-Feld vollständig, heißen sie
           vermutlich anders als erwartet. Dann einmalig eine kleine Stichprobe OHNE
           Feldfilter holen und die Namen am echten Objekt ermitteln. Der Regelfall
           bleibt der schlanke, gefilterte Abruf. */
        if (activities.none { it.normalizedPower != null } || activities.none { it.heartRate != null }) {
            runCatching {
                val sample = client.activities(today.minusDays(30).toString(), newest, fields = null)
                val byId = sample.associateBy { it.id }
                activities = activities.map { a -> byId[a.id] ?: a }
            }
        }

        return RawData(
            wellness = wellness.map { it.toDomain() },
            sessions = activities.mapNotNull { it.toDomain() },
            thresholds = parseThresholds(settings, activities, today),
            athleteName = name,
        )
    }

    private fun WellnessDto.toDomain() = WellnessDay(
        date = id, ctl = ctl, atl = atl, hrv = hrv,
        restingHr = restingHR, sleepSeconds = sleepSecs, sleepScore = sleepScore)

    private fun ActivityDto.toDomain(): Session? {
        val date = startDateLocal?.take(10) ?: return null
        val zones = parseZones(zoneTimes)
        return Session(
            id = id, type = type ?: "", trainer = trainer == true, localDate = date,
            movingTimeSec = movingTime ?: 0.0, trainingLoad = trainingLoad ?: 0.0,
            intensity = intensityFraction, eftp = eftp,
            normalizedPower = normalizedPower, avgHeartRate = heartRate,
            decoupling = decouplingValue, zoneSeconds = zones, hasZones = zones.isNotEmpty())
    }

    private fun parseZones(el: kotlinx.serialization.json.JsonElement?): Map<Int, Int> {
        val arr = el as? JsonArray ?: return emptyMap()
        val entries = arr.mapIndexed { i, e ->
            when (e) {
                is JsonObject -> Zones.ZoneEntry(
                    id = e["id"]?.jsonPrimitive?.content,
                    zone = e["zone"]?.jsonPrimitive?.content?.toIntOrNull(),
                    seconds = (e["secs"] ?: e["time"] ?: e["x"])?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0)
                is JsonPrimitive -> Zones.ZoneEntry(null, i + 1, e.content.toDoubleOrNull()?.toInt() ?: 0)
                else -> Zones.ZoneEntry(null, i + 1, 0)
            }
        }
        return Zones.parseZoneSeconds(entries)
    }

    private fun parseThresholds(settings: List<SportSettingsDto>, activities: List<ActivityDto>, today: LocalDate): Thresholds {
        var outdoor: Int? = null; var indoor: Int? = null; var eftp: Int? = null
        var lthr: Int? = null; var maxHr: Int? = null
        settings.forEach { s ->
            val types = s.types ?: s.type?.let { listOf(it) } ?: emptyList()
            if (types.any { it == "Ride" || it == "VirtualRide" }) {
                (s.ftp ?: s.icuFtp)?.let { outdoor = it.roundToInt() }
                s.indoorFtp?.let { indoor = it.roundToInt() }
                if (s.eFTPSupported == true) s.mmpModel?.ftp?.let { eftp = it.roundToInt() }
                s.lthr?.let { lthr = it.roundToInt() }
                s.maxHr?.let { maxHr = it.roundToInt() }
            }
        }
        /* Rückfall nur aus RADaktivitäten und nur aus den letzten 42 Tagen: die
           Lauf-eFTP hat eine eigene, deutlich höhere Schwelle und würde sonst gegen die
           Rad-FTP verglichen — eine Falschwarnung „FTP veraltet". */
        if (eftp == null) {
            val cut = today.minusDays(42).toString()
            activities.filter { (it.type ?: "") in Zones.CYCLING && (it.startDateLocal ?: "") >= cut }
                .mapNotNull { it.eftp }.maxOrNull()?.let { eftp = it.roundToInt() }
        }
        var stale: String? = null
        val ref = outdoor?.toDouble()
        val e = eftp
        if (ref != null && e != null && e > ref * 1.03) {
            val pct = ((e / ref - 1) * 100).roundToInt()
            stale = "eFTP $e W liegt $pct % über deiner Outdoor-FTP (${ref.roundToInt()} W) — evtl. FTP in intervals.icu anheben."
        }
        return Thresholds(outdoor, indoor, eftp, lthr, maxHr, stale)
    }
}
