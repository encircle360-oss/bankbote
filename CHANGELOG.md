# Changelog

Alle nennenswerten Änderungen an Bankbote stehen in dieser Datei. Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen [Semantic Versioning](https://semver.org/lang/de/). Ein Release bekommt einen Abschnitt `## [X.Y.Z] - JJJJ-MM-TT`; der Workflow Release übernimmt ihn in die Release-Notes.

## Unveröffentlicht

### Hinzugefügt

- Start-Banner: Bankbote meldet sich beim Start mit seinem Namen, der Adresse des Projekts und der encircle360 GmbH als Herausgeberin; der Smoke-Test prüft, dass der Banner auch im nativen Image steckt.
- Integrationstests gegen einen laufenden Hibiscus Server 2.12.4 mit MariaDB 11.8 in Containern (`./gradlew hibiscusTest`), mit erfundenen Konten und Umsätzen in der Datenbank des Servers. Sie bestätigen Anmeldung, Kontenliste, Datum, Beträge auch in Exponentenschreibweise, das Kennzeichen „vorgemerkt“, Umlaute und Eurozeichen, Inhaltstyp, Fenster, Reihenfolge und das Fehlen einer Grenze; der ganze Dienst läuft gegen den Server und zwei nachgebaute ERPNext. Die Suite läuft in jedem Pull Request. Ergebnisse unter [Hibiscus Server](docs/hibiscus.md#gegen-einen-laufenden-server-geprüft).

- Erste Version von Bankbote: ein Dienst, der gebuchte Umsätze von einem Hibiscus Server liest und je Konto als eingereichte Bankumsätze (`Bank Transaction`) an das ERPNext 16 des Unternehmens liefert, dem das Konto gehört.
- Hibiscus Server als Quelle über die JSON-Beans `hibiscus/konto/list` und `hibiscus/konto/<id>/umsaetze/days/<n>`, mit gepinntem Zertifikat und Master-Passwort aus einer Datei. Vorgemerkte Umsätze (`FLAG_NOTBOOKED`) werden erkannt und nie geliefert. Belege im Quelltext unter [Hibiscus Server](docs/hibiscus.md).
- Mandantenfähig: beliebig viele Ziele mit eigener Adresse, eigenem API-Schlüssel und Geheimnis; jedes Konto per IBAN genau einem Ziel zugeordnet; ein Konto ohne Zuordnung geht nirgendwohin. Jedes Ziel läuft für sich; der Schlüssel eines Ziels geht nie an eine andere Adresse.
- Idempotente Lieferung über die `transaction_id` `<source-key>:<id>`, mit Einreichen liegengebliebener Entwürfe und Prüfung, dass ERPNext die Kennung übernimmt.
- Läufe nach einer Benachrichtigung des Hibiscus Servers (`POST /notify`), auf Anfrage (`POST /run`), nach dem Start und nach Zeitplan, nie zwei gleichzeitig; Nachholen ab einem Tag mit `bankbote.backfill-from`.
- Zusammenfassung jedes Laufs je Ziel und Konto im Log und im Health-Endpunkt, mit maskierten IBANs und ohne Geheimnisse oder persönliche Daten.
- Prüfung der ganzen Konfiguration beim Start, mit allen Fehlern auf einmal.
- Natives Image mit GraalVM im Container für `linux/amd64` und `linux/arm64`; vorbereiteter Release-Workflow mit SBOM und Provenienz.

### Geändert

- Ein Lauf, der die Quelle nicht lesen konnte, wird einmal nach `bankbote.notify-delay` wiederholt. Hibiscus Server lehnt direkt nach dem Start erst Verbindungen ab, schließt dann den TLS-Handshake und antwortet seine JSON-Beans für einen Moment mit HTTP 500; alle drei gelten als Quelle, die noch nicht lesbar ist; ein gemeinsam gestartetes Bankbote müsste sonst bis zum nächsten Zeitplan warten.
- Bankbote startet nicht mehr mit leerem `bankbote.hibiscus.username`: Hibiscus Server lehnt einen leeren Benutzernamen mit 401 ab, wie ein falsches Passwort.

### Behoben

- Dokumentation: [Betrieb](docs/betrieb.md#das-zertifikat-des-hibiscus-servers) sagte, Web-Admin zeige den Fingerabdruck des Zertifikats. Das tut es nicht; das Zertifikat kommt jetzt aus dem Schlüsselspeicher des Servers, Eintrag `jameica`, mit dem Master-Passwort.

### Bekannte Grenzen

- Noch nie gegen eine echte Bank oder ein echtes ERPNext gelaufen; was davon abhängt, steht im [README](README.md#was-getestet-ist-und-was-nicht).
