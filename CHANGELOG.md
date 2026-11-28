# Changelog

Alle nennenswerten Änderungen an Bankbote stehen in dieser Datei. Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen [Semantic Versioning](https://semver.org/lang/de/). Ein Release bekommt einen Abschnitt `## [X.Y.Z] - JJJJ-MM-TT`; der Workflow Release übernimmt ihn in die Release-Notes.

## Unveröffentlicht

## [0.1.1] - 2026-11-28

### Behoben

- **Die Konfigurationsdatei wurde nicht gelesen.** Das Image von 0.1.0 lief im Arbeitsverzeichnis `/home/nonroot`; ein eingehängtes `/config/application.yaml` übersah Spring deshalb, und die Prüfung der Konfiguration meldete `bankbote.hibiscus is missing`, obwohl die Dokumentation das Laden ohne Argument zusagte. Das Image läuft jetzt im Arbeitsverzeichnis `/` und liest die Datei ohne `--spring.config.additional-location`. Die Datei bleibt optional; Umgebungsvariablen und Argumente überschreiben sie wie zuvor.
- **Ein eigener Port für Proben ließ sich nicht einrichten.** [Betrieb](docs/betrieb.md) empfahl `management.server.port` und `management.server.address` für `httpGet`-Proben. Das native Image legt beim Bauen fest, ob der Actuator einen eigenen Server hat, und 0.1.0 ist ohne gebaut: `management.server.port` allein wurde stillschweigend übergangen, zusammen mit `management.server.address` brach der Start mit `Management-specific server address cannot be configured as the management server is not listening on a separate port` ab. Neu ist der Probe-Port `bankbote.probes.port`, als Vorgabe aus, der nur `GET` und `HEAD` von `/actuator/health/liveness` und `/actuator/health/readiness` beantwortet und alles andere, auch `/notify`, `/run` und den Health-Endpunkt mit dem letzten Lauf, mit 404. `management.server.port` und `management.server.address` verhindern jetzt den Start mit einer Meldung, die den Grund nennt.
- **Mit schreibgeschütztem Root-Dateisystem startete das Image nicht**, und die Dokumentation sagte nicht, warum: Der eingebettete Tomcat legt sein Arbeitsverzeichnis unter `/tmp` an. [Betrieb](docs/betrieb.md#das-image) nennt jetzt das beschreibbare `/tmp` (tmpfs, in Kubernetes ein `emptyDir`); der Rauchtest der CI startet das Image auf beiden Plattformen mit `--read-only`, `--tmpfs /tmp`, `--cap-drop ALL` und `--security-opt no-new-privileges`.
- Dokumentation: README und [Betrieb](docs/betrieb.md) sagten, ein veröffentlichtes Image gebe es erst mit dem ersten Release. Neu ist ein Beispiel für Kubernetes mit Bankbote als Container neben dem Hibiscus Server, mit Konfigurationsdatei, Geheimnissen als Dateien, der Liste der Konten aus Umgebungsvariablen, `/tmp` und Proben, und der Hinweis, dass Spring eine Liste wie `bankbote.accounts` ganz aus einer Quelle bindet, sodass IBAN und Ziel eines Kontos aus derselben Quelle kommen müssen.

## [0.1.0] - 2026-11-28

### Hinzugefügt

- Start-Banner: Bankbote meldet sich beim Start mit seinem Namen, der Adresse des Projekts und der encircle360 GmbH als Herausgeberin; der Smoke-Test prüft, dass der Banner auch im nativen Image steckt.
- Integrationstests gegen ein laufendes ERPNext 16.61 aus dem Image von erpnext_germanized mit MariaDB und Redis (`./gradlew erpnextTest`), mit zwei erfundenen Unternehmen und dem Dienstbenutzer mit genau der dokumentierten Rolle, und ein Test der ganzen Kette vom laufenden Hibiscus Server über Bankbote in das laufende ERPNext. Bestätigt sind `transaction_id` beim Anlegen, Einreichen in einer Anfrage, das Unternehmen aus dem Bankkonto, Filter, Seiten, Beträge von 0, Feldlängen, Umlaute und dass die Rolle für alles genügt außer für Entwürfe. Ergebnisse unter [Betrieb](docs/betrieb.md#der-dienstbenutzer-in-erpnext).
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

- Das Java-Paket heißt jetzt `com.encircle360.oss.bankbote` statt `com.encircle360.bankbote`, die Gradle-Gruppe `com.encircle360.oss`: Die Open-Source-Projekte der encircle360 GmbH bekommen einen eigenen Namensraum. Image, Präfix `bankbote.` der Konfiguration und Umgebungsvariablen bleiben, wie sie sind.

- Bankbote reicht keine Entwürfe mehr ein. Hält ERPNext eine `transaction_id` von Bankbote als Entwurf, zählt der Umsatz als fehlgeschlagen mit der Meldung, den Entwurf in ERPNext einzureichen oder zu löschen, und wird nicht doppelt angelegt. Ein echtes ERPNext 16 zeigte, dass das Einreichen eines Entwurfs das Recht Write auf Bank Transaction braucht, das die Rolle des Dienstbenutzers nicht hat und nicht bekommen soll.
- Die Art eines Umsatzes (`transaction_type`) wird auf 50 Zeichen gekürzt statt auf 140: So lang ist das Feld in ERPNext 16, ein längerer Wert ließ das Anlegen mit `CharacterLengthExceededError` scheitern. `reference_number` ist ein Feld vom Typ Small Text und wird nicht mehr gekürzt.

- Ein Lauf, der die Quelle nicht lesen konnte, wird einmal nach `bankbote.notify-delay` wiederholt. Hibiscus Server lehnt direkt nach dem Start erst Verbindungen ab, schließt dann den TLS-Handshake und antwortet seine JSON-Beans für einen Moment mit HTTP 500; alle drei gelten als Quelle, die noch nicht lesbar ist; ein gemeinsam gestartetes Bankbote müsste sonst bis zum nächsten Zeitplan warten.
- Bankbote startet nicht mehr mit leerem `bankbote.hibiscus.username`: Hibiscus Server lehnt einen leeren Benutzernamen mit 401 ab, wie ein falsches Passwort.

### Behoben

- Dokumentation: [Betrieb](docs/betrieb.md#das-zertifikat-des-hibiscus-servers) sagte, Web-Admin zeige den Fingerabdruck des Zertifikats. Das tut es nicht; das Zertifikat kommt jetzt aus dem Schlüsselspeicher des Servers, Eintrag `jameica`, mit dem Master-Passwort.

### Bekannte Grenzen

- Noch nie gegen eine echte Bank oder ein produktives ERPNext gelaufen; was davon abhängt, steht im [README](README.md#was-getestet-ist-und-was-nicht).
