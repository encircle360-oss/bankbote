# Betrieb

> Bankbote ist noch nicht gegen einen echten Hibiscus Server oder ein echtes ERPNext gelaufen. Diese Seite beschreibt den vorgesehenen Betrieb; die Schritte, die nur mit echten Systemen prüfbar sind, sind als solche genannt.

## Das Image

`ghcr.io/encircle360-oss/bankbote:<version>` enthält nur das native Image `/bankbote` auf einer Basis ohne Shell, die nichts außer glibc, CA-Zertifikaten und Zeitzonen hat. Es läuft als Benutzer `nonroot` (UID 65532), für `linux/amd64` und `linux/arm64`. Ein Release bringt dazu je Plattform eine SBOM (SPDX) und eine Provenienz-Attestierung, prüfbar mit `gh attestation verify oci://ghcr.io/encircle360-oss/bankbote:<version> --owner encircle360-oss`. Selbst bauen lässt sich das Image mit `docker build -f Containerfile -t bankbote .`; das native Image braucht beim Bauen mehrere GB Arbeitsspeicher.

Das native Image startet in Sekundenbruchteilen und braucht wenig Speicher; eine JVM gibt es im Image nicht.

Das Arbeitsverzeichnis des Images ist `/`. Spring liest deshalb `/config/application.yaml` ohne weiteres Argument, siehe [Konfiguration](konfiguration.md).

**Gehärtet betreiben.** Bankbote braucht keine Capabilities, keine neuen Rechte und kein beschreibbares Root-Dateisystem, wohl aber ein **beschreibbares `/tmp`**: Der eingebettete Tomcat legt dort beim Start sein Arbeitsverzeichnis an und startet ohne es nicht (`Unable to create tempDir. java.io.tmpdir is set to /tmp`). Ein kleines tmpfs genügt; Bankbote schreibt sonst nichts:

```bash
docker run --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
  --network container:hibiscus \
  -v ./config:/config:ro -v ./secrets:/run/secrets:ro \
  ghcr.io/encircle360-oss/bankbote:<version>
```

So startet der Rauchtest der CI das Image auf beiden Plattformen. In Kubernetes ist das ein `emptyDir` auf `/tmp`, siehe [Kubernetes](#kubernetes-neben-dem-hibiscus-server).

## Netzwerk-Namespace

Bankbote lauscht auf `127.0.0.1:8089` und ist dafür gedacht, im **Netzwerk-Namespace des Hibiscus Servers** zu laufen, sodass beide sich über Loopback erreichen und sonst niemand Bankbote:

- **Kubernetes:** Bankbote als zweiter Container im Pod des Hibiscus Servers. Beide teilen den Namespace; `https://127.0.0.1:8080` ist der Hibiscus Server, `http://127.0.0.1:8089` ist Bankbote.
- **docker:** `docker run --network container:<hibiscus>` oder in compose `network_mode: "service:hibiscus"`.

In compose, gehärtet:

```yaml
services:
  hibiscus:
    image: ghcr.io/encircle360-oss/hibiscus-server:<version>
    # Datenbank, Volumes und Master-Passwort wie in der Dokumentation dieses Images
  bankbote:
    image: ghcr.io/encircle360-oss/bankbote:<version>
    network_mode: "service:hibiscus"
    read_only: true
    tmpfs:
      - /tmp
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    volumes:
      - ./config:/config:ro
      - ./secrets:/run/secrets:ro
```

Eine Health-Prüfung von docker oder compose (`HEALTHCHECK`, `healthcheck:`) geht nicht: Sie läuft als Befehl im Container, und das Image hat weder Shell noch `curl`.

Ausgehend braucht Bankbote HTTPS zu jeder ERPNext-Site, sonst nichts. Eine Netzwerkrichtlinie kann den ausgehenden Verkehr auf genau diese Adressen beschränken.

## Dateien

| Pfad im Container | Inhalt |
|---|---|
| `/config/application.yaml` | die Konfiguration, siehe [Konfiguration](konfiguration.md); optional, wenn alles aus Umgebungsvariablen oder Argumenten kommt |
| frei wählbar, etwa `/run/secrets/…` | Master-Passwort, Zertifikat des Hibiscus Servers, je Ziel API-Schlüssel und -Geheimnis, je eine Datei |
| `/tmp` | beschreibbar, etwa als tmpfs: Arbeitsverzeichnis des eingebetteten Tomcat |

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

`delivery` ist `DOWN`, wenn der letzte Lauf einen Fehler hatte, sonst `UP`; der Gesamtstatus folgt ihm. Dieser Endpunkt mit allen Einzelheiten ist nur auf `127.0.0.1:8089` erreichbar, also aus dem Namespace, in Kubernetes etwa mit `kubectl port-forward`.

### Proben

`/actuator/health/liveness` und `/actuator/health/readiness` hängen nicht vom letzten Lauf ab und zeigen nur ihren Status, `{"status":"UP"}` mit HTTP 200, sonst `DOWN` mit HTTP 503. Auf `127.0.0.1:8089` antworten sie immer.

Für Proben von außerhalb des Namespaces, etwa eine `httpGet`-Probe des kubelet, hat das Image einen **Probe-Port**, als Vorgabe aus. `bankbote.probes.port` (Umgebungsvariable `BANKBOTE_PROBES_PORT`) schaltet ihn ein; er lauscht auf allen Adressen des Namespaces oder auf `bankbote.probes.address`:

| Adresse | Pfad | Antwort |
|---|---|---|
| `127.0.0.1:8089` | `/notify`, `/run` (POST) | löst einen Lauf aus, siehe [Sicherheit](sicherheit.md#die-endpunkte) |
| `127.0.0.1:8089` | `/actuator/health` | der letzte Lauf mit Namen der Ziele, maskierten IBANs, Zählern und Fehlermeldungen |
| `127.0.0.1:8089` | `/actuator/health/liveness`, `/actuator/health/readiness` | nur der Status |
| Probe-Port, alle Adressen | `GET`/`HEAD` `/actuator/health/liveness`, `/actuator/health/readiness` | nur der Status |
| Probe-Port, alle Adressen | alles andere, auch `/run`, `/notify` und `/actuator/health` | HTTP 404 ohne Inhalt |

Der Probe-Port ist ein zweiter Listener desselben Servers. Er vergleicht Methode und Pfad genau, so wie sie ankommen; eine andere Schreibweise, ein Schrägstrich am Ende oder ein Parameter im Pfad bekommt 404. Der Port 8089 bleibt auf Loopback; `/notify` und `/run` sind von außerhalb des Namespaces nicht erreichbar, auch nicht über den Probe-Port. Der Rauchtest der CI prüft das auf beiden Plattformen von außerhalb des Containers.

**Kein eigener Management-Server.** Spring Boots `management.server.port` und `management.server.address` lehnt Bankbote beim Start ab. Das native Image legt beim Bauen fest, ob der Actuator einen eigenen Server hat; gebaut ist es ohne. Bis 0.1.0 wurde `management.server.port` darum stillschweigend übergangen, und zusammen mit `management.server.address` brach der Start mit `Management-specific server address cannot be configured as the management server is not listening on a separate port` ab. Ein eigener Management-Server hätte außerdem den ganzen Health-Endpunkt mit dem letzten Lauf nach außen gegeben und ihn von Port 8089 genommen; der Probe-Port gibt nur die beiden Proben frei.

Das Log hat je Lauf eine Zeile je Konto und Ziel, eine je Konto ohne Zuordnung und eine Schlusszeile:

```text
target unternehmen-a account DE17…0001: found 12, delivered 3, existing 8, pending 1, failed 0
account DE06…0005 (id 5 at the source) is mapped to no target; its 4 transaction(s) in the window go nowhere
run from 2026-11-13 succeeded in 2104 ms
```

Fehler stehen als `WARN` mit Grund, etwa `ERPNext unternehmen-b is unavailable (HTTP 503)`. Das Log enthält nie Geheimnisse, volle IBANs, Beträge, Verwendungszwecke oder Namen.

## Kubernetes: neben dem Hibiscus Server

Bankbote läuft als zweiter Container im Pod des Hibiscus Servers. Die Konfiguration kommt aus einer ConfigMap, die Geheimnisse als Dateien aus Secrets, die Liste der Konten aus Umgebungsvariablen. Ein Beispiel mit erfundenen Namen und zwei Zielen:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: bankbote-config
data:
  application.yaml: |
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
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hibiscus
spec:
  replicas: 1                # eine Bankbote-Instanz je Hibiscus Server
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: hibiscus
  template:
    metadata:
      labels:
        app: hibiscus
    spec:
      containers:
        - name: hibiscus-server
          image: ghcr.io/encircle360-oss/hibiscus-server:<version>
          # Datenbank, Volumes und Master-Passwort wie in der Dokumentation dieses Images
        - name: bankbote
          image: ghcr.io/encircle360-oss/bankbote:<version>
          env:
            - name: BANKBOTE_PROBES_PORT
              value: "8090"
            # Die ganze Liste der Konten aus Umgebungsvariablen: IBAN und Ziel eines Kontos
            # müssen aus derselben Quelle kommen.
            - name: BANKBOTE_ACCOUNTS_0_IBAN
              value: DE17123456780000000001
            - name: BANKBOTE_ACCOUNTS_0_TARGET
              value: unternehmen-a
            - name: BANKBOTE_ACCOUNTS_1_IBAN
              value: DE60123456780000000003
            - name: BANKBOTE_ACCOUNTS_1_TARGET
              value: unternehmen-b
          ports:
            - name: probes
              containerPort: 8090
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: probes
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: probes
          securityContext:
            runAsNonRoot: true
            readOnlyRootFilesystem: true
            allowPrivilegeEscalation: false
            capabilities:
              drop: [ALL]
            seccompProfile:
              type: RuntimeDefault
          volumeMounts:
            - name: config
              mountPath: /config
              readOnly: true
            - name: hibiscus
              mountPath: /run/secrets/hibiscus
              readOnly: true
            - name: unternehmen-a
              mountPath: /run/secrets/unternehmen-a
              readOnly: true
            - name: unternehmen-b
              mountPath: /run/secrets/unternehmen-b
              readOnly: true
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: config
          configMap:
            name: bankbote-config
        - name: hibiscus           # Dateien master-password und certificate.pem
          secret:
            secretName: bankbote-hibiscus
        - name: unternehmen-a      # Dateien api-key und api-secret
          secret:
            secretName: bankbote-unternehmen-a
        - name: unternehmen-b
          secret:
            secretName: bankbote-unternehmen-b
        - name: tmp
          emptyDir:
            medium: Memory
            sizeLimit: 16Mi
```

- **Konfigurationsdatei:** Die ConfigMap landet als `/config/application.yaml`, die Bankbote ohne Argument liest. Ein `workingDir` am Container darf nicht gesetzt sein, sonst sucht Spring `./config/` dort.
- **Geheimnisse als Dateien:** Secrets als Volume, nur lesend; die Dateien sind als Vorgabe für alle lesbar (Modus 0644), also auch für den Benutzer 65532. Das Secret mit dem Master-Passwort hängt nur dieser Pod ein.
- **Listen aus einer Quelle:** Spring bindet eine Liste wie `bankbote.accounts` oder `bankbote.targets` ganz aus einer Quelle, der mit dem höchsten Rang, und mischt sie nicht. Stehen die Konten in den Umgebungsvariablen, gehören sie nicht zusätzlich in die Datei; IBAN und Ziel eines Kontos getrennt auf Datei und Umgebung zu verteilen, ergibt ein Konto ohne IBAN oder ohne Ziel, und Bankbote startet nicht.
- **`/tmp`:** das `emptyDir` ist das beschreibbare Verzeichnis, das Tomcat beim Start braucht.
- **Proben:** nur über den Probe-Port, siehe [Proben](#proben). Den letzten Lauf zeigt `kubectl port-forward deploy/hibiscus 8089:8089` und dann `curl -s http://127.0.0.1:8089/actuator/health`; der Port-Forward kommt im Pod über Loopback an. Einen Lauf auslösen geht auf demselben Weg mit `curl -X POST http://127.0.0.1:8089/run`.
- **Netzwerk:** Der Pod braucht ausgehend nur HTTPS zu den ERPNext-Sites; eingehend für Bankbote nur den Probe-Port vom kubelet.

## Updates

Ein neues Image ersetzt das alte; Bankbote hat keinen Zustand außer dem Bericht des letzten Laufs. Nach dem Start holt der erste Lauf nach, was in der Zwischenzeit gebucht wurde, soweit es im Fenster liegt. War Bankbote länger aus als `lookback-days`, einmal mit `backfill-from` starten.

Nur eine Instanz je Hibiscus Server betreiben, siehe [Architektur](architektur.md#idempotenz).
