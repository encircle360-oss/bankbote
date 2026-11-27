# Changelog

Alle nennenswerten Änderungen an Bankbote stehen in dieser Datei. Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen [Semantic Versioning](https://semver.org/lang/de/). Ein Release bekommt einen Abschnitt `## [X.Y.Z] - JJJJ-MM-TT`; der Workflow Release übernimmt ihn in die Release-Notes.

## Unveröffentlicht

### Hinzugefügt

- Erste Version von Bankbote: ein Dienst, der gebuchte Umsätze von einem Hibiscus Server liest und je Konto als eingereichte Bankumsätze (`Bank Transaction`) an das ERPNext 16 des Unternehmens liefert, dem das Konto gehört.
- Hibiscus Server als Quelle über die JSON-Beans `hibiscus/konto/list` und `hibiscus/konto/<id>/umsaetze/days/<n>`, mit gepinntem Zertifikat und Master-Passwort aus einer Datei. Vorgemerkte Umsätze (`FLAG_NOTBOOKED`) werden erkannt und nie geliefert. Belege im Quelltext unter [Hibiscus Server](docs/hibiscus.md).
- Mandantenfähig: beliebig viele Ziele mit eigener Adresse, eigenem API-Schlüssel und Geheimnis; jedes Konto per IBAN genau einem Ziel zugeordnet; ein Konto ohne Zuordnung geht nirgendwohin. Jedes Ziel läuft für sich; der Schlüssel eines Ziels geht nie an eine andere Adresse.
- Idempotente Lieferung über die `transaction_id` `<source-key>:<id>`, mit Einreichen liegengebliebener Entwürfe und Prüfung, dass ERPNext die Kennung übernimmt.
- Läufe nach einer Benachrichtigung des Hibiscus Servers (`POST /notify`), auf Anfrage (`POST /run`), nach dem Start und nach Zeitplan, nie zwei gleichzeitig; Nachholen ab einem Tag mit `bankbote.backfill-from`.
- Zusammenfassung jedes Laufs je Ziel und Konto im Log und im Health-Endpunkt, mit maskierten IBANs und ohne Geheimnisse oder persönliche Daten.
- Prüfung der ganzen Konfiguration beim Start, mit allen Fehlern auf einmal.
- Natives Image mit GraalVM im Container für `linux/amd64` und `linux/arm64`; vorbereiteter Release-Workflow mit SBOM und Provenienz.

### Bekannte Grenzen

- Noch nie gegen einen echten Hibiscus Server, eine echte Bank oder ein echtes ERPNext gelaufen; was davon abhängt, steht im [README](README.md#was-getestet-ist-und-was-nicht).
