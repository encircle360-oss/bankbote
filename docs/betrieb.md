# Betrieb

> Bankbote ist noch nicht gegen einen echten Hibiscus Server oder ein echtes ERPNext gelaufen. Diese Seite beschreibt den vorgesehenen Betrieb; die Schritte, die nur mit echten Systemen prüfbar sind, sind als solche genannt.

## Das Image

`ghcr.io/encircle360-oss/bankbote:<version>` enthält nur das native Image `/bankbote` auf einer Basis ohne Shell, die nichts außer glibc, CA-Zertifikaten und Zeitzonen hat. Es läuft als Benutzer `nonroot` (UID 65532), für `linux/amd64` und `linux/arm64`. Ein Release bringt dazu je Plattform eine SBOM (SPDX) und eine Provenienz-Attestierung, prüfbar mit `gh attestation verify oci://ghcr.io/encircle360-oss/bankbote:<version> --owner encircle360-oss`. Bis zum ersten Release baut `docker build -f Containerfile -t bankbote .` das Image selbst; das native Image braucht beim Bauen mehrere GB Arbeitsspeicher.

Das native Image startet in Sekundenbruchteilen und braucht wenig Speicher; eine JVM gibt es im Image nicht.

## Netzwerk-Namespace

Bankbote lauscht auf `127.0.0.1:8089` und ist dafür gedacht, im **Netzwerk-Namespace des Hibiscus Servers** zu laufen, sodass beide sich über Loopback erreichen und sonst niemand Bankbote:

- **Kubernetes:** Bankbote als zweiter Container im Pod des Hibiscus Servers. Beide teilen den Namespace; `https://127.0.0.1:8080` ist der Hibiscus Server, `http://127.0.0.1:8089` ist Bankbote.
- **docker:** `docker run --network container:<hibiscus>` oder in compose `network_mode: "service:hibiscus"`.

Ausgehend braucht Bankbote HTTPS zu jeder ERPNext-Site, sonst nichts. Eine Netzwerkrichtlinie kann den ausgehenden Verkehr auf genau diese Adressen beschränken.

## Dateien

| Pfad im Container | Inhalt |
|---|---|
| `/config/application.yaml` | die Konfiguration, siehe [Konfiguration](konfiguration.md) |
| frei wählbar, etwa `/run/secrets/…` | Master-Passwort, Zertifikat des Hibiscus Servers, je Ziel API-Schlüssel und -Geheimnis, je eine Datei |

Die Dateien muss der Benutzer 65532 lesen können; nur lesend einhängen. In Kubernetes passen Secrets als Volume; das Master-Passwort sollte ein eigenes Secret sein, das nur dieser Pod einhängt.

Bankbote liest Geheimnisse nur beim Start. Nach einem Wechsel eines Schlüssels Bankbote neu starten.

## Das Zertifikat des Hibiscus Servers

Bankbote vertraut beim Hibiscus Server genau einem Zertifikat. Jameica erzeugt es beim ersten Start selbst, selbst signiert, mit `CN=<Name>, O=Jameica Certificate` und ohne subjectAlternativeName, und hält es unter dem Eintrag `jameica` im Schlüsselspeicher `/data/cfg/jameica.keystore`, den das Master-Passwort öffnet.

Am sichersten kommt das Zertifikat **aus dem Schlüsselspeicher**, ohne der Verbindung trauen zu müssen. Mit dem Image `ghcr.io/encircle360-oss/hibiscus-server` und dem `keytool` eines JDK:

```bash
docker cp hibiscus:/data/cfg/jameica.keystore ./jameica.keystore
keytool -exportcert -rfc -alias jameica -keystore jameica.keystore \
  -storepass:file /run/secrets/hibiscus/master-password > certificate.pem
rm jameica.keystore
```

Die Kopie des Schlüsselspeichers enthält den privaten Schlüssel des Servers; sie gehört sofort wieder gelöscht.

Wer das Zertifikat über die Verbindung holt, aus dem gemeinsamen Namespace, vergleicht seinen Fingerabdruck mit dem aus dem Schlüsselspeicher (`keytool -list -v -alias jameica -keystore …`), bevor die Datei in Bankbote kommt:

```bash
openssl s_client -connect 127.0.0.1:8080 -showcerts </dev/null 2>/dev/null \
  | openssl x509 -outform PEM > certificate.pem
openssl x509 -in certificate.pem -noout -fingerprint -sha256
# or without openssl: keytool -printcert -sslserver 127.0.0.1:8080 -rfc > certificate.pem
```

Web-Admin zeigt den Fingerabdruck des eigenen Zertifikats nicht: Die Liste der Zertifikate dort (`/webadmin/`, ebenso `/webadmin/rest/certs/list`) enthält nur die Zertifikate, denen Jameica vertraut, nicht das eigene. Die Integrationstests holen das Zertifikat aus dem Schlüsselspeicher und prüfen, dass der Server genau dieses vorlegt.

Erzeugt Jameica ein neues Zertifikat, etwa auf einem neuen Volume, verweigert Bankbote die Verbindung, bis die neue Datei eingehängt und Bankbote neu gestartet ist.

## Der Hibiscus Server

- Das Web-Admin von Jameica muss laufen, mit HTTPS und Anmeldung, wie vorgegeben (`listener.http.ssl=true`, `listener.http.auth=true`). Bankbote liest die JSON-Beans unter `/webadmin/rest/hibiscus/…`.
- `json.indent` größer als 0 lassen (Vorgabe 2), damit der Server `application/json` sendet; er sendet ihn ohne `charset`, schreibt aber UTF-8, und so liest Bankbote ihn. Ohne Inhaltstyp liest Bankbote die Antwort als ISO-8859-1.
- Direkt nach dem Start lehnt der Server für einen Moment Verbindungen ab, schließt den TLS-Handshake oder antwortet mit HTTP 500, weil er die JSON-Beans erst nach dem Webserver registriert. Startet Bankbote zusammen mit ihm, kann der erste Lauf daran scheitern; Bankbote wiederholt einen Lauf, der die Quelle nicht lesen konnte, einmal nach `bankbote.notify-delay`.
- Der Benutzername ist beliebig, darf aber nicht leer sein; mit leerem Namen antwortet der Server 401, und Bankbote startet mit leerem `bankbote.hibiscus.username` gar nicht.
- **Benachrichtigung:** In den Einstellungen des Payment-Servers die Benachrichtigungs-URL (`notify.url`) auf `http://127.0.0.1:8089/notify` setzen. Sie kommt je Konto nach dem Saldenabruf; ist „Saldo synchronisieren“ für ein Konto aus, kommt sie nicht, und der Zeitplan holt dessen Umsätze. Einzelheiten unter [Hibiscus Server](hibiscus.md#die-benachrichtigung-notifyurl).
- Vorgemerkte Umsätze darf der Server abrufen oder nicht (`umsatz.fetchnotbooked`, beim Payment-Server vorgegeben `false`); Bankbote liefert sie nie.

## Der Dienstbenutzer in ERPNext

In jedem ERPNext einen eigenen Benutzer nur für Bankbote anlegen, als *System User*, mit einer eigenen Rolle, etwa „Bankbote“, und nur dieser Rolle, und für ihn unter *API Access* Schlüssel und Geheimnis erzeugen. Die Rechte der Rolle im *Role Permissions Manager* setzen, nicht in der DocType-Definition:

| DocType | Rechte |
|---|---|
| Bank Transaction | Read, Create, Submit |
| Bank Account | Read |

Die Suite `erpnextTest` legt genau diese Rolle in einem echten ERPNext 16.61 an und belegt (siehe [Entwicklung](entwicklung.md#integrationstests)):

- **Genug:** Mit diesen Rechten liest Bankbote die Bankkonten aller Unternehmen der Site, findet vorhandene Umsätze und legt Umsätze mit `docstatus` 1 an, die ERPNext in derselben Anfrage einreicht. Weitere Rechte, etwa auf Currency oder Company, braucht das nicht; das Unternehmen ergänzt ERPNext selbst aus dem Bankkonto.
- **Einen Entwurf einreichen kann die Rolle nicht:** Das braucht das Recht Write auf Bank Transaction (HTTP 403 mit `PUT … {"docstatus": 1}` wie mit `frappe.client.submit`). Bankbote reicht deshalb keinen Entwurf ein. Hält ERPNext eine `transaction_id` von Bankbote als Entwurf, etwa von Hand angelegt, zählt der Umsatz als fehlgeschlagen mit der Meldung `… holds hibiscus:… as a draft; submit or delete it there`, und Bankbote legt ihn nicht ein zweites Mal an. Den Entwurf reicht ein Mensch ein oder löscht ihn.
- **Nicht mehr:** Der Schlüssel liest keine Ausgangsrechnungen, Buchungen (GL Entry), Kunden, Sachkonten und keine Benutzer im Einzelnen (HTTP 403), legt keine Bankkonten an, ändert und löscht keine (403) und storniert und löscht keine Bankumsätze (403). **Die Namen** aller Benutzer (ihre E-Mail-Adressen) und aller Unternehmen der Site kann er auflisten; das erlaubt Frappe jedem System User.

Für ein ERPNext mit mehreren Unternehmen kann eine User Permission auf *Company* den Benutzer zusätzlich auf die Unternehmen beschränken, deren Konten Bankbote liefert; das ist nicht getestet.

Für jedes Konto braucht das ERPNext genau ein Bankkonto mit „Is Company Account“ und der IBAN des Kontos, mit oder ohne Leerzeichen. Gibt es keines oder mehrere, liefert Bankbote für dieses Konto nichts und nennt das in der Zusammenfassung.

**Die Adresse muss die Site nennen.** Frappe wählt die Site nach dem Host der Anfrage. `bankbote.targets[n].url` ist deshalb die Adresse, unter der die Site heißt, etwa `https://erp.unternehmen-a.example.com`; die Adresse eines Containers oder eine IP-Adresse beantwortet ERPNext mit 404, solange kein Reverse Proxy davor den Host der Site setzt.

Zum ersten Prüfen gegen ein echtes ERPNext eignet sich eine Kopie der Site mit einem Konto und `backfill-from` auf wenige Tage:

1. Bankbote starten und das Log des ersten Laufs lesen.
2. In der Kopie die angelegten Bankumsätze ansehen: Unternehmen, Bankkonto, Beträge, Seite, Zweck, Referenz, Gegenkonto, eingereicht.
3. `curl -X POST http://127.0.0.1:8089/run` und prüfen, dass der zweite Lauf alles als vorhanden zählt.

## Nachholen

Bankbote schaut je Lauf `lookback-days` Tage zurück. Für ältere Umsätze, etwa beim ersten Einrichten, `bankbote.backfill-from` setzen und Bankbote neu starten:

```bash
docker run … ghcr.io/encircle360-oss/bankbote:<version> --bankbote.backfill-from=2026-01-01
```

Nur der erste Lauf nach dem Start geht bis dorthin zurück, danach gilt wieder `lookback-days`. Was schon in ERPNext ist, wird übersprungen; das Nachholen lässt sich also gefahrlos wiederholen. Danach den Schlüssel wieder entfernen, damit nicht jeder Neustart den langen Zeitraum liest. Der Hibiscus Server gibt höchstens 9999 Tage zurück.

## Health und Log

`GET http://127.0.0.1:8089/actuator/health` zeigt unter `components.delivery` den letzten Lauf:

```json
{
  "status": "UP",
  "details": {
    "running": false,
    "lastRun": {
      "startedAt": "2026-11-27T09:00:00Z",
      "finishedAt": "2026-11-27T09:00:02Z",
      "from": "2026-11-13",
      "successful": true,
      "sourceError": null,
      "targets": [
        {
          "name": "unternehmen-a",
          "successful": true,
          "error": null,
          "accounts": [
            {"iban": "DE17…0001", "found": 12, "delivered": 3, "existing": 8, "pending": 1, "failed": 0, "error": null}
          ]
        }
      ],
      "unmapped": [{"iban": "DE06…0005", "sourceId": "5", "found": 4}]
    }
  }
}
```

`delivery` ist `DOWN`, wenn der letzte Lauf einen Fehler hatte, sonst `UP`; der Gesamtstatus folgt ihm. Für Proben gibt es `/actuator/health/liveness` und `/actuator/health/readiness`, die nicht vom letzten Lauf abhängen.

Das Image hat keine Shell, also auch kein `curl` für eine Probe im Container. In Kubernetes eine `httpGet`-Probe auf einen eigenen Port für das Management stellen, der nicht nur auf Loopback lauscht:

```yaml
management:
  server:
    port: 8090
    address: 0.0.0.0
```

`/notify` und `/run` bleiben dabei auf dem Port 8089 und antworten ohnehin nur Aufrufen von der Loopback-Adresse. Wer den Health-Endpunkt außerhalb des Namespaces anbietet, zeigt dort die Namen der Ziele und maskierte IBANs; sonst nichts.

Das Log hat je Lauf eine Zeile je Konto und Ziel, eine je Konto ohne Zuordnung und eine Schlusszeile:

```text
target unternehmen-a account DE17…0001: found 12, delivered 3, existing 8, pending 1, failed 0
account DE06…0005 (id 5 at the source) is mapped to no target; its 4 transaction(s) in the window go nowhere
run from 2026-11-13 succeeded in 2104 ms
```

Fehler stehen als `WARN` mit Grund, etwa `ERPNext unternehmen-b is unavailable (HTTP 503)`. Das Log enthält nie Geheimnisse, volle IBANs, Beträge, Verwendungszwecke oder Namen.

## Updates

Ein neues Image ersetzt das alte; Bankbote hat keinen Zustand außer dem Bericht des letzten Laufs. Nach dem Start holt der erste Lauf nach, was in der Zwischenzeit gebucht wurde, soweit es im Fenster liegt. War Bankbote länger aus als `lookback-days`, einmal mit `backfill-from` starten.

Nur eine Instanz je Hibiscus Server betreiben, siehe [Architektur](architektur.md#idempotenz).
