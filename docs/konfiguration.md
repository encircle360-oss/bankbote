# Konfiguration

Bankbote liest seine Konfiguration wie jede Spring-Boot-Anwendung, aus drei Quellen, von der niedrigsten zur höchsten:

1. aus `/config/application.yaml` im Container. Das Image läuft im Arbeitsverzeichnis `/`, und Spring lädt `./config/application.yaml` des Arbeitsverzeichnisses von selbst, ohne Argument. Die Datei ist optional; fehlt sie, startet Bankbote bis zur Prüfung der Konfiguration und nennt, was fehlt;
2. aus Umgebungsvariablen wie `BANKBOTE_LOOKBACK_DAYS=30`;
3. aus `--bankbote.…=…` beim Start.

Eine höhere Quelle überschreibt einen einzelnen Schlüssel einer niedrigeren. **Listen** wie `bankbote.targets` und `bankbote.accounts` überschreibt sie dagegen ganz: Spring bindet eine Liste vollständig aus der höchsten Quelle, die einen ihrer Einträge nennt, und mischt nicht. IBAN und Ziel eines Kontos müssen deshalb aus derselben Quelle kommen; am einfachsten steht jede Liste ganz in der Datei oder ganz in Umgebungsvariablen:

```bash
BANKBOTE_ACCOUNTS_0_IBAN=DE17123456780000000001
BANKBOTE_ACCOUNTS_0_TARGET=unternehmen-a
BANKBOTE_ACCOUNTS_1_IBAN=DE60123456780000000003
BANKBOTE_ACCOUNTS_1_TARGET=unternehmen-b
```

Geheimnisse gehören nie in die Konfiguration, in Umgebungsvariablen oder auf die Kommandozeile, sondern in Dateien, deren Pfade die Konfiguration nennt.

## Prüfung beim Start

`ConfigurationCheck` prüft die ganze Konfiguration, bevor Bankbote startet, liest jede Datei mit einem Geheimnis und jedes Zertifikat und nennt jeden Fehler auf einmal:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

The configuration of Bankbote has 3 problem(s):
  - bankbote.targets: the name unternehmen-a is used 2 times
  - bankbote.accounts[0] (DE17…0001) is mapped to the unknown target unternehmen-x
  - bankbote.accounts: the account DE17…0001 is mapped 2 times

Action:

Correct the configuration under bankbote.*; every key is described in docs/konfiguration.md.
```

Die Meldungen nennen Schlüssel, Pfade und maskierte IBANs, nie den Inhalt einer Datei. Fehler am Probe-Port und ein gesetzter `management.server.*` werden schon geprüft, bevor der Server angelegt wird, und erscheinen deshalb für sich, vor den übrigen.

## Allgemein

| Schlüssel | Vorgabe | Bedeutung und Prüfung |
|---|---|---|
| `bankbote.lookback-days` | `14` | wie viele Tage ein Lauf zurückschaut, 1 bis 9999. Ein Fenster, das länger ist als die längste Pause des Hibiscus Servers, verpasst nichts |
| `bankbote.backfill-from` | – | erster Buchungstag des ersten Laufs nach dem Start, `JJJJ-MM-TT`, nicht in der Zukunft. Danach gilt wieder `lookback-days`. Siehe [Nachholen](betrieb.md#nachholen) |
| `bankbote.time-zone` | `Europe/Berlin` | Zeitzone, die bestimmt, welcher Tag heute ist, für Fenster und Zeitplan |
| `bankbote.schedule` | `0 0 * * * *` | Zeitplan als Cron-Ausdruck von Spring (Sekunde, Minute, Stunde, Tag, Monat, Wochentag), `-` für keinen |
| `bankbote.notify-delay` | `60s` | Wartezeit nach einer Benachrichtigung des Hibiscus Servers und vor der einmaligen Wiederholung eines Laufs, der die Quelle nicht lesen konnte; nicht negativ |

`bankbote.source-key` (Umgebungsvariable `BANKBOTE_SOURCE_KEY`) gibt es seit 0.2.0 nicht mehr: Die `transaction_id` hängt nicht mehr von der Quelle ab, siehe [Architektur](architektur.md#die-kennung-transaction_id). Eine Konfiguration, die den Schlüssel noch setzt, startet weiter; Bankbote ignoriert ihn und schreibt beim Start eine Warnung `bankbote.source-key has no effect since 0.2.0 …` ins Log. Der Schlüssel kann entfernt werden.

## Hibiscus Server

| Schlüssel | Vorgabe | Bedeutung und Prüfung |
|---|---|---|
| `bankbote.hibiscus.url` | – | Basisadresse, etwa `https://127.0.0.1:8080`. Nur `https`, ohne Benutzer, Abfrage oder Fragment. Ein Pfad bleibt erhalten |
| `bankbote.hibiscus.certificate` | – | PEM-Datei mit genau einem Zertifikat, dem des Servers. Bankbote vertraut nur ihm und prüft den Namen darin nicht |
| `bankbote.hibiscus.username` | `admin` | Benutzername; der Hibiscus Server nimmt jeden außer einem leeren und prüft nur das Passwort. Nicht leer |
| `bankbote.hibiscus.password-file` | – | Datei mit dem Master-Passwort in einer Zeile; ein Zeilenende am Schluss wird ignoriert |
| `bankbote.hibiscus.connect-timeout` | `10s` | Zeitlimit für den Verbindungsaufbau, positiv |
| `bankbote.hibiscus.request-timeout` | `120s` | Zeitlimit für eine Antwort, positiv |

## Ziele

Ein Ziel ist eine ERPNext-Installation eines Unternehmens, ein Mandant.

| Schlüssel | Vorgabe | Bedeutung und Prüfung |
|---|---|---|
| `bankbote.targets[n].name` | – | Name, auf den sich die Konten beziehen. 1 bis 63 Kleinbuchstaben, Ziffern oder Bindestriche, eindeutig |
| `bankbote.targets[n].url` | – | Adresse der Site, etwa `https://erp.unternehmen-a.example.com`. `https`, ohne Benutzer, Abfrage oder Fragment. Zwei Ziele dürfen nicht dieselbe Adresse haben: Ein ERPNext mit mehreren Unternehmen ist ein Ziel, dem die Konten aller seiner Unternehmen zugeordnet sind |
| `bankbote.targets[n].api-key-file` | – | Datei mit dem API-Schlüssel des Dienstbenutzers |
| `bankbote.targets[n].api-secret-file` | – | Datei mit dem API-Geheimnis des Dienstbenutzers |
| `bankbote.targets[n].certificate-authorities` | – | PEM-Datei mit Zertifizierungsstellen, denen Bankbote für dieses Ziel statt denen des Systems vertraut, etwa einer eigenen. Der Name im Zertifikat wird geprüft |
| `bankbote.targets[n].allow-plain-http` | `false` | `http` statt `https` zulassen, für eine Site im selben vertrauenswürdigen Netz. Schlüssel und Umsätze gehen dann unverschlüsselt |
| `bankbote.targets[n].connect-timeout` | `10s` | Zeitlimit für den Verbindungsaufbau, positiv |
| `bankbote.targets[n].request-timeout` | `60s` | Zeitlimit für eine Antwort, positiv. Ein langsames Ziel hält nur sich selbst auf |

Mindestens ein Ziel ist nötig.

## Konten

| Schlüssel | Bedeutung und Prüfung |
|---|---|
| `bankbote.accounts[n].iban` | IBAN des Kontos, mit oder ohne Leerzeichen; muss gültig sein |
| `bankbote.accounts[n].target` | Name eines konfigurierten Ziels |

Jede IBAN darf nur einmal vorkommen, auch nicht einmal je Ziel. Mindestens ein Konto ist nötig. Konten des Hibiscus Servers, die hier fehlen, liefert Bankbote nirgendwohin.

## Beispiel mit zwei Zielen

```yaml
bankbote:
  lookback-days: 14
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
      certificate-authorities: /run/secrets/unternehmen-b/ca.pem
  accounts:
    - iban: DE17 1234 5678 0000 0000 01
      target: unternehmen-a
    - iban: DE87 1234 5678 0000 0000 02
      target: unternehmen-a
    - iban: DE60 1234 5678 0000 0000 03
      target: unternehmen-b
```

Unternehmen A bekommt die Umsätze der ersten beiden Konten, Unternehmen B die des dritten. Hätte der Hibiscus Server ein viertes Konto, ginge es nirgendwohin und stünde in der Zusammenfassung jedes Laufs.

## Server und Health

Die ersten drei Schlüssel sind die von Spring Boot; die Vorgaben stehen in `src/main/resources/application.yaml`.

| Schlüssel | Vorgabe | Bedeutung und Prüfung |
|---|---|---|
| `server.address` | `127.0.0.1` | Bankbote lauscht nur auf Loopback, siehe [Sicherheit](sicherheit.md#die-endpunkte) |
| `server.port` | `8089` | Port für `/notify`, `/run` und `/actuator/health` |
| `management.endpoint.health.show-details` | `always` | der letzte Lauf im Health-Endpunkt; enthält keine Geheimnisse und keine persönlichen Daten. Die Proben `liveness` und `readiness` zeigen davon nichts, nur ihren Status |
| `bankbote.probes.port` | – | schaltet den Probe-Port ein, der nur `GET` und `HEAD` von `/actuator/health/liveness` und `/actuator/health/readiness` beantwortet, siehe [Proben](betrieb.md#proben). 1 bis 65535, nicht gleich `server.port` |
| `bankbote.probes.address` | alle Adressen | IP-Adresse, auf der der Probe-Port lauscht, etwa `0.0.0.0` oder `::`. Nur eine IP-Adresse, kein Name; nur zusammen mit `bankbote.probes.port` |
| `management.server.port`, `management.server.address` | – | **nicht unterstützt**; Bankbote startet damit nicht. Das native Image ist ohne eigenen Management-Server gebaut, siehe [Proben](betrieb.md#proben) |
