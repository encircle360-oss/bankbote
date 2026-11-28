# Bankbote

Bankbote liefert Kontoumsätze von einem [Hibiscus Server](https://github.com/willuhn/hibiscus.server) an ERPNext 16: Jeder gebuchte Umsatz eines Kontos wird ein eingereichter Bankumsatz (`Bank Transaction`) in der ERPNext-Installation des Unternehmens, dem das Konto gehört. Bankbote läuft dauerhaft neben dem Hibiscus Server, holt die neuen Umsätze nach jeder Synchronisierung und nach Zeitplan und schiebt sie über die REST-API von ERPNext an das richtige Unternehmen. ERPNext braucht dafür keine App und bekommt nie einen Zugang zum Hibiscus Server.

> **Status: in Entwicklung, noch nicht einsatzfähig.** Bankbote ist gegen nachgebaute Server getestet, gegen einen laufenden Hibiscus Server mit Konten und Umsätzen, die der Test selbst in dessen Datenbank schreibt, und gegen ein laufendes ERPNext 16 mit erfundenen Unternehmen, auch in der ganzen Kette. **Gegen eine echte Bank und ein produktives ERPNext ist Bankbote noch nie gelaufen.** Was davon abhängt, steht unter [Was getestet ist und was nicht](#was-getestet-ist-und-was-nicht). Bitte noch nicht mit echten Konten betreiben, außer zum Ausprobieren mit einer Kopie von ERPNext.

## Für wen

Für Unternehmen und Unternehmensgruppen, die ihre Konten mit Hibiscus Server über FinTS/HBCI abrufen und mit ERPNext buchen, eine ERPNext-Installation je Unternehmen. Bankbote ist mandantenfähig: Ein Hibiscus Server mit den Konten mehrerer Unternehmen beliefert mit einer Bankbote-Instanz beliebig viele ERPNext-Installationen, jede mit eigener Adresse und eigenem API-Schlüssel.

## Wie es funktioniert

```text
 ┌───────────────── gemeinsamer Netzwerk-Namespace (Pod) ─────────────────┐
 │                                                                         │
 │  Hibiscus Server ── nach jeder Synchronisierung: POST /notify ──▶ Bankbote
 │       ▲                                                          │      │
 │       └──── HTTPS, Zertifikat gepinnt, Master-Passwort ──────────┘      │
 │             GET /webadmin/rest/hibiscus/konto/…/umsaetze/days/n         │
 └───────────────────────────────────────────────────────────────┬────────┘
                                                                 │ je Konto genau ein Ziel,
                                                                 │ je Ziel eigener API-Schlüssel
                        ┌────────────────────────────────────────┼────────────────────────┐
                        ▼                                        ▼                        ▼
             ERPNext Unternehmen A                    ERPNext Unternehmen B     ERPNext Unternehmen C
             POST /api/resource/Bank Transaction      (nur die Konten von B)    (nur die Konten von C)
```

1. **Auslösen.** Der Hibiscus Server ruft nach dem Abruf eines Kontos seine `notify.url` auf; Bankbote wartet eine einstellbare Zeit (vorgegeben 60 s), damit die Umsätze gespeichert sind, und startet dann einen Lauf. Dazu gibt es einen Lauf nach dem Start und einen nach Zeitplan (vorgegeben stündlich). Es läuft nie mehr als ein Lauf gleichzeitig.
2. **Lesen.** Bankbote liest die Konten und je zugeordnetem Konto die Umsätze der letzten Tage (vorgegeben 14). Vorgemerkte Umsätze, die die Bank noch nicht gebucht hat, werden erkannt und nie geliefert.
3. **Zuordnen.** Jedes Konto ist per IBAN genau einem Ziel zugeordnet. Bankbote sucht in diesem ERPNext das eine Bankkonto des Unternehmens mit dieser IBAN und liefert nicht, wenn es keines oder mehrere gibt. Konten ohne Zuordnung gehen nirgendwohin und stehen in der Zusammenfassung.
4. **Liefern.** Jeder Umsatz bekommt eine feste `transaction_id`, etwa `hibiscus:4711`. Was ERPNext mit dieser Kennung schon hat, wird übersprungen; ein Entwurf mit dieser Kennung wird gemeldet, nicht doppelt angelegt. Zweimal laufen, überlappende Zeiträume und ein Absturz mitten im Lauf erzeugen keine Dubletten.
5. **Berichten.** Jeder Lauf endet mit einer Zusammenfassung je Ziel und Konto (gefunden, geliefert, schon vorhanden, vorgemerkt, fehlgeschlagen) im Log und im Health-Endpunkt, mit maskierten IBANs und ohne Verwendungszwecke oder Namen.

Einzelheiten: [Architektur](docs/architektur.md).

## Sicherheitsmodell

Der Hibiscus Server kennt genau eine Identität und eine Zugangsart: den Benutzer `admin` mit dem **Master-Passwort**, das zugleich die gespeicherten PINs der Bankzugänge schützt. Dienstkonten oder API-Schlüssel gibt es nicht (`JameicaLoginService` in jameica.webadmin prüft nur das Master-Passwort). Ein ERPNext, das selbst beim Hibiscus Server abholt, hielte damit den Schlüssel zu allen Konten aller Unternehmen.

Bankbote dreht die Richtung um: Es läuft neben dem Hibiscus Server, ist außer ihm das einzige, das das Master-Passwort kennt, und schiebt die Umsätze jedes Kontos mit einem eng berechtigten API-Schlüssel in das ERPNext des Unternehmens, dem das Konto gehört.

- Kein ERPNext hält je einen Zugang zum Hibiscus Server.
- Ein ERPNext bekommt nur die Umsätze der Konten, die ihm zugeordnet sind, und Bankbote schickt den Schlüssel eines ERPNext nie an eine andere Adresse, auch nicht bei Weiterleitungen oder Fehlern.
- Bankbote vertraut beim Hibiscus Server genau dem konfigurierten Zertifikat, liest alle Geheimnisse aus Dateien und schreibt weder Geheimnisse noch Verwendungszwecke, Namen oder volle IBANs in Logs, Fehlermeldungen oder die Health-Ausgabe.
- Bankbote lauscht nur auf der Loopback-Adresse; die Endpunkte, die einen Lauf auslösen, nehmen keine Eingaben an.

Was das voraussetzt und wo die Grenzen liegen: [Sicherheit](docs/sicherheit.md).

## Mandanten: was ein Unternehmen sieht und was nicht

Jedes Ziel ist ein Mandant: ein ERPNext mit eigener Adresse, eigenem API-Schlüssel und eigenem Geheimnis, jeweils aus eigenen Dateien.

**Ein Unternehmen sieht** in seinem ERPNext die gebuchten Umsätze genau der Konten, die ihm zugeordnet sind, als eingereichte Bankumsätze an seinem eigenen Bankkonto, mit Datum, Betrag, Verwendungszweck, Referenz, Gegenkonto und Art, dazu die `transaction_id` mit der Kennung des Hibiscus Servers.

**Ein Unternehmen sieht nicht**: die Konten und Umsätze anderer Unternehmen, auch nicht deren Anzahl; das Master-Passwort oder irgendeinen Zugang zum Hibiscus Server; die Adressen und Schlüssel der anderen Ziele; vorgemerkte Umsätze. Der API-Schlüssel eines Unternehmens erlaubt Bankbote nur, was die Rolle seines Dienstbenutzers erlaubt, siehe [Betrieb](docs/betrieb.md#der-dienstbenutzer-in-erpnext).

**Der Betreiber von Bankbote** sieht im Log und im Health-Endpunkt die Namen aller Ziele, maskierte IBANs wie `DE17…0001` und Zähler, aber keine Beträge, Zwecke oder Namen.

Eine Konfiguration mit zwei Zielen, bei der ein falsch zugeordnetes Konto den Start verhindert:

```yaml
bankbote:
  hibiscus:
    url: https://127.0.0.1:8080
    certificate: /run/secrets/hibiscus/certificate.pem
    password-file: /run/secrets/hibiscus/master-password
  targets:
    - name: unternehmen-a
      url: https://erp.unternehmen-a.example.com
      api-key-file: /run/secrets/unternehmen-a/api-key
      api-secret-file: /run/secrets/unternehmen-a/api-secret
    - name: unternehmen-b
      url: https://erp.unternehmen-b.example.com
      api-key-file: /run/secrets/unternehmen-b/api-key
      api-secret-file: /run/secrets/unternehmen-b/api-secret
  accounts:
    - iban: DE17 1234 5678 0000 0000 01   # Geschäftskonto von A
      target: unternehmen-a
    - iban: DE87 1234 5678 0000 0000 02   # Tagesgeld von A
      target: unternehmen-a
    - iban: DE60 1234 5678 0000 0000 03   # Geschäftskonto von B
      target: unternehmen-b
```

Zwei Ziele mit demselben Namen, zwei Ziele mit derselben Adresse, ein Konto mit unbekanntem Ziel oder dasselbe Konto zweimal zugeordnet: Bankbote startet nicht und nennt jeden Fehler einzeln.

## Schnellstart

> Nur zum Ausprobieren gegen eine Kopie von ERPNext, siehe Status oben.

1. In jedem ERPNext einen Dienstbenutzer mit der Rolle aus [Betrieb](docs/betrieb.md#der-dienstbenutzer-in-erpnext) anlegen, API-Schlüssel und -Geheimnis erzeugen und je in eine Datei schreiben. Für jedes Konto muss es in ERPNext genau ein Bankkonto des Unternehmens mit der IBAN geben.
2. Im Hibiscus Server den Dienst prüfen: Die JSON-Beans unter `/webadmin/rest/` brauchen das Web-Admin von Jameica mit HTTPS und Anmeldung (Vorgabe). Das Zertifikat des Servers als PEM-Datei ablegen, siehe [Betrieb](docs/betrieb.md#das-zertifikat-des-hibiscus-servers), und das Master-Passwort in eine Datei schreiben.
3. Eine `application.yaml` wie oben nach `/config/` legen und Bankbote im Netzwerk-Namespace des Hibiscus Servers starten:

   ```bash
   docker run --network container:hibiscus \
     --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
     -v ./config:/config:ro -v ./secrets:/run/secrets:ro \
     ghcr.io/encircle360-oss/bankbote:<version>
   ```

   Bankbote liest `/config/application.yaml` ohne weiteres Argument. Das Root-Dateisystem darf schreibgeschützt sein; nur `/tmp` muss beschreibbar sein. Für Kubernetes steht ein Beispiel als Container neben dem Hibiscus Server, mit Proben, unter [Betrieb](docs/betrieb.md#kubernetes-neben-dem-hibiscus-server).
4. Im Hibiscus Server die Benachrichtigungs-URL auf `http://127.0.0.1:8089/notify` setzen.
5. Den Lauf prüfen: `curl -s http://127.0.0.1:8089/actuator/health` im selben Namespace, oder das Log.

Alles Weitere: [Betrieb](docs/betrieb.md) und [Konfiguration](docs/konfiguration.md).

## Konfiguration

Bankbote wird über Spring-Konfiguration unter `bankbote.*` eingerichtet, aus `/config/application.yaml`, aus Umgebungsvariablen oder Argumenten; eine Liste wie `bankbote.accounts` kommt dabei ganz aus einer Quelle. Geheimnisse stehen nie in der Konfiguration selbst und nie auf der Kommandozeile, sondern in Dateien, deren Pfade die Konfiguration nennt. Beim Start prüft Bankbote die ganze Konfiguration und liest jede Datei; ist etwas falsch, startet es nicht und nennt jeden Fehler.

| Schlüssel | Vorgabe | Bedeutung |
|---|---|---|
| `bankbote.hibiscus.url` | – | Adresse des Hibiscus Servers, nur `https` |
| `bankbote.hibiscus.certificate` | – | PEM-Datei mit dem Zertifikat des Servers, dem einzigen, dem Bankbote dort vertraut |
| `bankbote.hibiscus.password-file` | – | Datei mit dem Master-Passwort |
| `bankbote.targets[n].name` | – | Name des Ziels, auf den sich die Konten beziehen |
| `bankbote.targets[n].url` | – | Adresse der ERPNext-Site |
| `bankbote.targets[n].api-key-file`, `api-secret-file` | – | Dateien mit API-Schlüssel und -Geheimnis des Dienstbenutzers |
| `bankbote.accounts[n].iban`, `target` | – | IBAN eines Kontos und Name seines Ziels |
| `bankbote.lookback-days` | `14` | wie viele Tage ein Lauf zurückschaut |
| `bankbote.backfill-from` | – | erster Buchungstag des ersten Laufs nach dem Start, zum Nachholen |
| `bankbote.schedule` | `0 0 * * * *` | Zeitplan als Cron-Ausdruck, `-` für keinen |
| `bankbote.notify-delay` | `60s` | Wartezeit nach einer Benachrichtigung |
| `bankbote.probes.port` | – | Probe-Port für Liveness und Readiness von außerhalb des Namespaces, etwa für das kubelet |

Alle Schlüssel mit Prüfregeln, Zeitüberschreitungen und eigenen Zertifizierungsstellen: [Konfiguration](docs/konfiguration.md).

## Was getestet ist und was nicht

Bankbote wird testgetrieben entwickelt; die CI verlangt 100 % Abdeckung von Zeilen und Zweigen. Getestet sind, mit nachgebauten Servern auf dem eigenen Rechner:

- das Lesen der JSON-Beans eines Hibiscus Servers über HTTPS mit gepinntem Zertifikat und Master-Passwort, auch mit fremdem Zertifikat, Zeitüberschreitung, abgelehntem Passwort, Weiterleitung, abgeschnittener Antwort und Zeichensätzen;
- das Erkennen vorgemerkter Umsätze und das exakte Lesen von Beträgen und Daten;
- die REST-API von ERPNext für Bankkonten und Bankumsätze mit Seiten, Filtern, Einreichen, Fehlern und Weiterleitungen;
- die Mandantentrennung mit zwei und drei ERPNext-Installationen: jedes Konto nur an sein Ziel, jeder Schlüssel nur an seine Adresse, ein ausgefallenes, langsames oder den Schlüssel ablehnendes Ziel ohne Folgen für die anderen;
- die Idempotenz: zweimal laufen, überlappende Zeiträume, Entwürfe, stornierte Umsätze;
- der ganze Dienst als Spring-Anwendung gegen einen nachgebauten Hibiscus Server und drei nachgebaute ERPNext, mit Benachrichtigung, Health-Ausgabe und Log, das keine Geheimnisse, IBANs, Zwecke oder Namen enthält;
- der Start mit falscher Zuordnung;
- in der CI das native Image im Container für `linux/amd64` und `linux/arm64`, gestartet mit erfundenen Zugangsdaten aus einer eingehängten `application.yaml` ohne Argumente, mit schreibgeschütztem Root-Dateisystem, ohne Capabilities und ohne neue Rechte; von außerhalb des Containers beantwortet der Probe-Port nur Liveness und Readiness, und der Port 8089 ist dort nicht erreichbar.

Gegen einen **laufenden Hibiscus Server 2.12.4** mit MariaDB in Containern, mit erfundenen Konten und Umsätzen in seiner Datenbank, in jedem Pull Request:

- Anmeldung mit beliebigem, nicht leerem Benutzernamen, falsches, fehlendes Passwort und leerer Benutzername;
- Konten mit IBAN und Währung, Datum, Beträge auch ab zehn Millionen in Exponentenschreibweise und mit einer Nachkommastelle, das Kennzeichen „vorgemerkt“ gesetzt, nicht gesetzt und leer, Umlaute und Eurozeichen, Inhaltstyp und Zeichensatz, die Ränder des Fensters, Reihenfolge und das Fehlen einer Grenze bei über 10 000 Umsätzen;
- das Zertifikat aus dem Schlüsselspeicher des Servers, gepinnt;
- der ganze Dienst gegen diesen Server und zwei nachgebaute ERPNext.

Was dabei herauskam, steht unter [Hibiscus Server](docs/hibiscus.md#gegen-einen-laufenden-server-geprüft).

Gegen ein **laufendes ERPNext 16.61** mit MariaDB und Redis in Containern, mit zwei erfundenen Unternehmen auf einer Site und dem Dienstbenutzer mit genau der Rolle aus [Betrieb](docs/betrieb.md#der-dienstbenutzer-in-erpnext), wenn ein Pull Request ERPNext-Client, Lieferung, Konfiguration oder die Suite berührt, auf `main` und wöchentlich:

- `transaction_id` wird beim Anlegen über die API übernommen und kommt in der Antwort zurück, ein Anlegen mit `docstatus` 1 reicht in derselben Anfrage ein, das Unternehmen folgt aus dem Bankkonto;
- Filter und Seiten beim Lesen der Bankkonten und vorhandener Umsätze, ein zweiter Lauf liefert nichts doppelt, ein stornierter Umsatz zählt als vorhanden, Beträge von 0, die Längen der Felder, lange mehrzeilige Zwecke mit Umlauten;
- was die Rolle darf und was nicht, auch dass sie keinen Entwurf einreichen kann;
- die ganze Kette: laufender Hibiscus Server mit Fixtures → Bankbote → laufendes ERPNext.

**Nicht getestet**, weil es nur mit echten Systemen geht:

- gegen eine echte Bank: der FinTS-Dialog, TAN-Verfahren, echte Abrufe und wie vorgemerkte Umsätze, Referenzen, Gegenkonten und Arten bei einer bestimmten Bank aussehen;
- die Benachrichtigung `notify.url` nach einer echten Synchronisierung;
- ein ERPNext mit echten Daten, eigenen Rechten, User Permissions auf Unternehmen, weiteren Apps oder einem Reverse Proxy davor; ERPNext-Versionen außer 16.61.

## Bekannte Grenzen

- **Nur Hibiscus Server als Quelle.** Die Quelle ist eine Schnittstelle, eine zweite gibt es noch nicht.
- **Konten-IDs bis 9999 und Zeiträume bis 9999 Tage.** Mehr lassen die Pfade der JSON-Beans nicht zu.
- **Eine Instanz je Hibiscus Server.** Zwei Bankbote-Instanzen am selben Hibiscus Server und denselben Zielen könnten sich überholen und Dubletten anlegen; die Sperre gegen gleichzeitige Läufe gilt je Prozess.
- **Kein Löschen, kein Ändern.** Was einmal in ERPNext ist, ändert Bankbote nicht, auch nicht, wenn es im Hibiscus Server geändert oder gelöscht wird. Ein in ERPNext stornierter Umsatz bleibt storniert.
- **Die Benachrichtigung des Hibiscus Servers ist ein Hinweis.** Sie kommt je Konto nach dem Saldenabruf, nicht nach dem Umsatzabruf, und nur, wenn der Saldo mit synchronisiert wird. Der Zeitplan fängt auf, was sie verpasst.
- **Verwendungszwecke, wie Hibiscus sie speichert.** Die Zeilen werden zusammengesetzt, SEPA-Kennzeichen wie `EREF+` bleiben stehen.
- **Kein Abgleich.** Bankbote legt Bankumsätze an; den Abgleich mit Zahlungen und Rechnungen macht ERPNext.

## Dokumentation

- [Architektur](docs/architektur.md): Bausteine, Ablauf eines Laufs, Entscheidungen
- [Hibiscus Server](docs/hibiscus.md): welche Schnittstelle Bankbote liest und warum, vorgemerkte Umsätze, Benachrichtigung, mit Belegen im Quelltext
- [Betrieb](docs/betrieb.md): Container, Netzwerk-Namespace, Dienstbenutzer in ERPNext, Zertifikat, Nachholen, Health und Log
- [Konfiguration](docs/konfiguration.md): alle Schlüssel und ihre Prüfung
- [Sicherheit](docs/sicherheit.md): Sicherheitsmodell, Annahmen, Grenzen
- [Entwicklung](docs/entwicklung.md): Aufbau, Tests, Stil, CI
- [Mitwirken](CONTRIBUTING.md), [Hinweise für Coding-Agenten](AGENTS.md), [Sicherheitslücken melden](SECURITY.md), [Änderungen](CHANGELOG.md)

## Mitwirken und Community

Beiträge sind willkommen: Pull Requests, Tests mit anderen Banken und ERPNext-Einrichtungen, Dokumentation, Fehlerberichte und Vorschläge. Wie ein Beitrag abläuft, steht in [Mitwirken](CONTRIBUTING.md).

- **Matrix:** [#oss:encircle360.com](https://matrix.to/#/#oss:encircle360.com)
- **Issues:** [Fehler und Wünsche](https://github.com/encircle360-oss/bankbote/issues)
- **Discussions:** [Fragen und Austausch](https://github.com/encircle360-oss/bankbote/discussions)
- **Fehler in Hibiscus oder ERPNext selbst** bitte beim jeweiligen Projekt melden: [willuhn/hibiscus](https://github.com/willuhn/hibiscus/issues), [frappe/erpnext](https://github.com/frappe/erpnext/issues)

Für Einführung, Betrieb und Anpassung: **hello@encircle360.com**

## Haftungsausschluss

Bankbote wird „wie besehen“ („AS IS“) und ohne jede Gewährleistung bereitgestellt, weder ausdrücklich noch stillschweigend, einschließlich, aber nicht beschränkt auf die Gewährleistung der Marktgängigkeit, der Eignung für einen bestimmten Zweck und der Nichtverletzung von Rechten Dritter.

Bankbote ist Software, keine Buchhaltung und keine Beratung. Mit der Nutzung erkennst du an:

- Du nutzt Bankbote auf eigenes Risiko. Für deine Bücher bleibst du selbst verantwortlich, für ihre Richtigkeit, Vollständigkeit und Ordnungsmäßigkeit.
- Übertragungen können fehlen, doppelt sein, beim falschen Konto landen oder falsche Werte tragen, auch wenn alle Prüfungen grün sind. Gleiche die Bankumsätze in ERPNext regelmäßig mit den Kontoauszügen der Bank ab.
- Hibiscus Server, die Banken und ERPNext ändern sich. Bankbote kann dem hinterherhinken.
- Die Maintainer, die encircle360 GmbH und alle Mitwirkenden haften nicht für Schäden oder Verluste jeder Art, die aus der Nutzung oder der Unmöglichkeit der Nutzung entstehen, insbesondere nicht für falsche Buchungen, verpasste Fristen, Mahngebühren, Zinsen oder die Kosten einer Nachbearbeitung. Das gilt, soweit ein Haftungsausschluss gesetzlich zulässig ist.
- Bankbote ist kein Angebot von Olaf Willuhn oder der Frappe Technologies Pvt. Ltd. Hibiscus und Hibiscus Server sind Software von Olaf Willuhn, Frappe und ERPNext Projekte der Frappe Technologies Pvt. Ltd. Bankbote steht mit keinem davon in Verbindung.

Für den Einsatz mit zugesichertem Support und Reaktionszeiten sprich uns an: **hello@encircle360.com**

## Lizenz

Bankbote steht unter der [GNU Affero General Public License v3.0 oder später](LICENSE) (AGPL-3.0-or-later). Bankbote enthält keinen Code von Hibiscus oder ERPNext und spricht mit beiden nur über ihre Schnittstellen.

## Maintainer

Bankbote wird von der **[encircle360 GmbH](https://encircle360.com)** entwickelt und gepflegt.

## Credits und Mitwirkende

Danke an alle Mitwirkenden und die Open-Source-Community, insbesondere an Olaf Willuhn für [Hibiscus](https://github.com/willuhn/hibiscus) (GPL-2.0-or-later) und [Hibiscus Server](https://github.com/willuhn/hibiscus.server) (GPL laut `plugin.xml`), von denen Bankbote die Umsätze bekommt, und an das [Frappe/ERPNext-Projekt](https://github.com/frappe/erpnext) (GPL-3.0), in das Bankbote sie liefert. Alle Mitwirkenden stehen in der [Contributors-Liste](https://github.com/encircle360-oss/bankbote/graphs/contributors).
