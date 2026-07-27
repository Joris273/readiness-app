# Readiness — Zustandsscore & Formaufbau (Android)

Native Kotlin/Compose-App auf Basis der intervals.icu-API. Portierung des validierten
HTML-Prototyps mit den Befunden aus dem Architektur- und Formel-Audit.

## Bauen über GitHub Actions

1. Repo anlegen und **alle Dateien** dieses Ordners hochladen — inklusive des versteckten
   `.github/`-Ordners, sonst läuft kein Build.
2. Der Workflow startet bei jedem Push auf `main`/`master` (oder manuell über *Run workflow*).
3. Er führt **zuerst den Cross-Check-Test** aus und baut erst danach. Schlägt die
   wissenschaftliche Prüfung fehl, entsteht keine APK.
4. APK herunterladen unter **Actions → letzter Lauf → Artifacts → readiness-debug-apk**.

## Signierung

`app/debug.keystore` gehört **in das Repo** und darf nicht gelöscht werden. Ohne ihn
erzeugt jeder Actions-Lauf einen neuen Debug-Schlüssel; Android lehnt die Aktualisierung
dann mit `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ab und die App müsste vor jedem Update
deinstalliert werden — mitsamt API-Key, Einstellungen und Kraftdaten-Cache.

Es handelt sich um einen reinen Debug-Schlüssel für den Eigengebrauch (Sideload). Für eine
Veröffentlichung im Play Store bräuchte es einen separaten, geheim gehaltenen Release-Schlüssel.

## Erststart

- API-Key unter ⚙ eintragen (intervals.icu → Settings → Developer).
- Optional: Schlafbedarf und durchschnittliche Powernap-Minuten. Leer gelassen wird der
  Bedarf aus der eigenen Historie abgeleitet.
- Widget: langer Druck auf den Homescreen → Widgets → „Readiness". Es passt sich der
  Größe an und liest den gespeicherten Snapshot, funkt also nicht selbst.

## Architektur

```
domain/   reines Kotlin, keine Android-Abhängigkeit, ohne Emulator testbar
          AnalysisConfig · Streams · ScoreEngine · LoadHistory · MetricsBuilder
          Progression · ReadinessEngine
data/     API-Transportmodelle, HTTP-Client, verschlüsselte Einstellungen, Caches
repo/     Beschaffung und Übersetzung; rechnet nichts aus, was zur Auswertung gehört
ui/       Compose-Oberfläche, ViewModel, Mapper auf das Darstellungsmodell
widget/   Glance-Homescreen-Widget
work/     WorkManager: morgendlicher Lauf mit vollständigem Nachladen
```

Die Konfiguration wird als unveränderliches `AnalysisConfig` durchgereicht statt global
gehalten — das verhindert Race Conditions zwischen Vordergrund-Refresh und
Hintergrund-Worker und hält die Domäne testbar.

## Wissenschaftliche Grundlagen

- **HRV**: Ln-rMSSD gegen individuelle SWC-Bandbreite; zusätzlich Wochentrend gegen vier
  unabhängige Wochenblöcke (Plews et al.). Anzeige ab 0,5 SD, Handlung erst ab 1,5 SD.
- **Belastung**: CTL/ATL/TSB und Zonenzeiten kommen fertig von intervals.icu — dort
  werden sie sportartspezifisch gegen die richtige FTP gerechnet.
- **Harte Reize**: Konzentrationskriterien (Anteil an der Fahrzeit), nicht reine Summen.
- **Kraft**: Leistung bei konditionierter Trittfrequenz (≤70 rpm), nicht rohes Drehmoment.
- **Formaufbau**: Dosis (CTL) getrennt von Antwort (eFTP, Effizienz, Entkopplung, Kraft);
  Vergleichszeitraum wählbar (6/12/18 Wochen).

Kein Medizinprodukt.
