# Entwicklung

## Aufbau des Repositories

| Pfad | Inhalt |
|---|---|
| `src/main/java/com/encircle360/oss/bankbote/` | der Dienst, Pakete wie in [Architektur](architektur.md#bausteine) |
| `src/main/resources/application.yaml` | Vorgaben von Server, Actuator und `bankbote.*` |
| `src/main/resources/META-INF/spring.factories` | meldet die Analyse für eine fehlerhafte Konfiguration an |
| `src/test/java/com/encircle360/oss/bankbote/` | die Tests, je Klasse neben ihrem Paket |
| `src/integrationTest/java/` | Integrationstests gegen echte Server in Containern, siehe [Integrationstests](#integrationstests) |
| `src/integrationTest/images/Dockerfile` | die Images der Integrationstests, je mit Tag und Digest; wird nie gebaut |
| `src/test/java/com/encircle360/oss/bankbote/testing/` | nachgebaute Server: `FakeHttpServer` (HTTP und HTTPS des JDK), `FakeHibiscus` (JSON-Beans mit eigenem Zertifikat und Master-Passwort), `FakeErpNext` (Bankkonten und Bankumsätze der REST-API mit Token), `FakeSource` (Quelle im Speicher), `TestCertificate` (selbst signierte Zertifikate mit `keytool`, nur im temporären Verzeichnis) |
| `Containerfile`, `.dockerignore` | natives Image mit GraalVM, Laufzeit auf distroless |
| `.github/workflows/` | CI, siehe unten |
| `.github/scripts/smoke-test.sh` | Rauchtest des Images: gehärtet, mit eingehängter `application.yaml` ohne Argumente, Probe-Port von außerhalb des Containers, Start ohne und mit fehlerhafter Konfiguration |
| `docs/` | Dokumentation |

## Einrichtung

Nötig ist ein JDK 25. Gradle kommt über den Wrapper (`./gradlew`), dessen Verteilung mit SHA-256 gepinnt ist. Eine Toolchain lädt der Build nicht selbst; `JAVA_HOME` oder der `PATH` muss auf ein JDK 25 zeigen. Auf NixOS etwa ein JDK 25 aus nixpkgs in einer `nix shell`.

```bash
./gradlew check            # tests, format check and the coverage gate
./gradlew spotlessApply    # formats the sources with google-java-format
./gradlew bootRun --args='--spring.config.additional-location=file:./local/'   # runs against a local configuration
```

Das native Image baut nur die CI, im Container. Lokal geht es mit `docker build -f Containerfile -t bankbote .`, braucht aber mehrere GB Arbeitsspeicher und einige Minuten; danach prüft `.github/scripts/smoke-test.sh bankbote` es wie die CI (braucht `docker`, `curl` und `openssl`).

pre-commit läuft wie in den anderen Projekten:

```bash
python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/pre-commit install
.venv/bin/pre-commit run --all-files
```

## Tests

Jede Regel hat einen Test, und der Test kommt zuerst, als eigener Commit vor dem Code, der ihn grün macht. `./gradlew check` verlangt **100 % Abdeckung von Zeilen und Zweigen** (JaCoCo); ausgenommen ist nur `BankboteApplication`, die `main`-Methode.

Die Tests laufen auf der JVM und nur gegen Server auf `127.0.0.1`, die sie selbst starten. Es gibt keine echten Hosts, Banken, IBANs oder Zugangsdaten: IBANs sind erfunden mit gültiger Prüfziffer und einer Bankleitzahl `12345678`, Adressen enden auf `example.com`, Zertifikate entstehen beim Test mit `keytool` im temporären Verzeichnis, es liegt kein Schlüssel im Repository.

| Test | prüft |
|---|---|
| `IbanTest`, `SecretTest`, `TlsContextsTest` | IBAN, Geheimnisse, gepinntes Zertifikat und Zertifizierungsstellen |
| `HibiscusValuesTest` | Beträge mit Exponent, negativ und auf die Nachkommastellen der Währung; Tausendertrenner und Komma werden abgelehnt; Daten, Flags, Referenzen |
| `HibiscusSourceTest` | die JSON-Beans über HTTPS mit Master-Passwort, vorgemerkte und unlesbare Umsätze, Valuta und Transaktions-ID der Bank, Fenster mit vollständigem ersten Tag in jeder Zeitzone des Servers, Weiterleitungen, fremdes Zertifikat, Zeitlimit, Größengrenze, Zeichensatz |
| `ErpNextClientTest` | Bankkonten seitenweise, vorhandene Umsätze, Anlegen und Einreichen, Fehlerarten, Token nie an eine andere Adresse |
| `BankTransactionMapperTest` | die Felder des Bankumsatzes |
| `TransactionIdsTest` | die `transaction_id`: die Beispiele aus [Architektur](architektur.md#beispiele), Normalisierung, Platzhalter, laufende Nummern, eine neu aufgebaute Quelle |
| `DeliveryTest` | Mandantentrennung mit zwei und drei Zielen, ausgefallene, langsame und den Schlüssel ablehnende Ziele, Idempotenz auch nach einer neu aufgebauten Quelle, gleiche Umsätze eines Tages, Entwürfe, Zuordnung der Bankkonten, vorgemerkte und unlesbare Umsätze |
| `RunCoordinatorTest` | ein Lauf zur Zeit, Zusammenfassen von Anfragen, Nachholen, Wartezeit der Benachrichtigung |
| `ConfigurationCheckTest`, `StartupFailureTest`, `BankboteConfigurationTest` | jede Regel der Konfiguration und der Start mit falscher Zuordnung |
| `BankboteServiceTest` | der ganze Dienst gegen `FakeHibiscus` und drei `FakeErpNext`, mit Benachrichtigung, Health und Log ohne Geheimnisse und persönliche Daten |
| `ReportsTest`, `DeliveryHealthIndicatorTest`, `TriggerControllerTest` | Zusammenfassung, Log, Health, Endpunkte nur von Loopback |
| `ProbePortTest` | der Probe-Port beantwortet nur `GET` und `HEAD` von Liveness und Readiness mit dem Status allein, auch nach einem gescheiterten Lauf, und alles andere mit 404; der Hauptport behält alles; ein fehlerhafter Probe-Port und ein eigener Management-Server verhindern den Start |

Was nur gegen einen echten Hibiscus Server und ein echtes ERPNext geprüft werden kann, steht im [README](../README.md#was-getestet-ist-und-was-nicht) und in [Betrieb](betrieb.md#der-dienstbenutzer-in-erpnext).

## Integrationstests

Die Integrationstests unter `src/integrationTest` lassen Bankbote gegen echte Server in Containern laufen. Sie haben ein eigenes Source-Set und eigene Gradle-Tasks, liegen außerhalb der Abdeckungsgrenze der Unit-Tests und laufen nicht mit `./gradlew check`. Sie nutzen die nachgebauten Server der Unit-Tests mit, wo ein echter fehlt.

| Task | Tag | Server | Dauer, Speicher |
|---|---|---|---|
| `./gradlew hibiscusTest` | `hibiscus` | Hibiscus Server 2.12.4 aus dem Image von encircle360-oss/hibiscus-server-image mit MariaDB 11.8 | lokal etwa 40 s nach dem Laden der Images; Hibiscus Server bis etwa 500 MiB, MariaDB etwa 150 MiB, dazu die JVM der Tests |
| `./gradlew erpnextTest` | `erpnext` | ERPNext 16.61 aus dem Image von encircle360-oss/erpnext_germanized mit MariaDB 11.8 und Redis; dazu für die ganze Kette Hibiscus Server mit MariaDB | lokal etwa 3,5 min nach dem Laden der Images, davon je Testklasse etwa 1,5 min für eine neue Site; ERPNext (gunicorn mit zwei Workern) etwa 300 MiB, MariaDB etwa 250 MiB, Redis wenige MiB; die ganze Kette mit Hibiscus Server zusammen unter 1,5 GiB |

Nötig ist ein Docker, das Testcontainers erreicht, und ein JDK 25. Jeder Task hat ein Zeitlimit (`hibiscusTest` 10 min, `erpnextTest` 20 min), jedes Warten auf einen Container eine Frist: `LogOrExit` wartet auf eine Zeile der Ausgabe und scheitert sofort mit Exit-Code und Ende der Ausgabe, wenn der Container vorher endet; `Readiness` wartet über abgelehnte Verbindungen, geschlossene Handshakes, Zeitüberschreitungen und 5xx hinweg. Ein gescheiterter Test trägt das Ende der Logs der Server mit maskierten Passwörtern (`ServerLogOnFailure`). Jede Testklasse startet ihre Container selbst und hält sie mit Testcontainers zusammen, die Hilfe `ryuk` von Testcontainers räumt nach einem Abbruch auf. Die Suiten nacheinander laufen lassen, nicht gleichzeitig.

**Hibiscus Server:** Der Test erzeugt Master-Passwort und Passwort der Datenbank bei jedem Lauf, legt sie als Dateien nur lesbar in den Container und startet den Server mit schreibgeschütztem Root-Dateisystem. Der Server legt beim Start das Schema des Herstellers in MariaDB an; `HibiscusServer.start` wartet danach mit `Readiness` bis zu drei Minuten, bis `konto/list` mit 200 antwortet, und nimmt abgelehnte Verbindungen, geschlossene Handshakes, Zeitüberschreitungen und 5xx bis dahin als „noch nicht bereit“; erst am Ende der Frist scheitert der Start mit dem Ende von `/data/jameica.log`. Scheitert ein Test, hängt `ServerLogOnFailure` die letzten Zeilen von `/data/jameica.log` mit maskierten Passwörtern an den Bericht. `HibiscusFixtures` schreibt erfundene Konten und Umsätze über JDBC in die Tabellen `konto` und `umsatz`; der Server sieht sie sofort. Das Zertifikat holt `HibiscusServer.certificateFromKeyStore()` aus `/data/cfg/jameica.keystore`, so wie [Betrieb](betrieb.md#das-zertifikat-des-hibiscus-servers) es beschreibt. Die Ergebnisse stehen unter [Hibiscus Server](hibiscus.md#gegen-einen-laufenden-server-geprüft).

**ERPNext:** Die Site heißt `localhost`, weil Frappe die Site nach dem Host der Anfrage wählt und die Tests sie unter `http://localhost:<Port>` erreichen. Ein Skript im Container schreibt die gemeinsame Konfiguration, legt die Site nur mit ERPNext an, erzeugt ihren Schlüssel zum Verschlüsseln, bevor gunicorn mit mehreren Workern startet (sonst können Worker verschiedene Schlüssel erzeugen, und ein API-Geheimnis lässt sich nicht mehr entschlüsseln), und startet dann gunicorn wie das Image. `ErpNextFixtures` richtet über die REST-API als Administrator den Einrichtungsassistenten, ein zweites Unternehmen, Bank, Sach- und Bankkonten und den Dienstbenutzer ein; seine Rechte setzt sie über den Permission Manager, wie es eine Administratorin täte. Die Ergebnisse stehen unter [Betrieb](betrieb.md#der-dienstbenutzer-in-erpnext).

**Images:** Jedes Image, auch `testcontainers/ryuk`, steht mit Tag und Digest in `src/integrationTest/images/Dockerfile`, das Dependabot aktuell hält. Die Tests lesen es; `build.gradle.kts` gibt Testcontainers das Image von `ryuk` über `TESTCONTAINERS_RYUK_CONTAINER_IMAGE`. Eine Variable `BANKBOTE_IMAGE_<STAGE>` ersetzt ein Image, etwa ein selbst gebautes:

```bash
(cd ../hibiscus-server-image && ./build.sh)          # builds hibiscus-server:local
BANKBOTE_IMAGE_HIBISCUS_SERVER=hibiscus-server:local ./gradlew hibiscusTest
```

## Stil

- Java 25, Spring Boot 4, google-java-format über Spotless; `javac` mit `-Xlint:all -Werror`.
- Code, Kommentare und Commits englisch, Dokumentation, Issues und Pull Requests deutsch.
- Geld ist `BigDecimal`, nie `double`; Beträge werden nie gerundet, sondern genau gelesen oder abgelehnt.
- Was Geheimnisse oder persönliche Daten trägt, hat ein `toString()`, das sie nicht zeigt.
- Kommentare nennen eine Bedingung, die der Code nicht zeigen kann, nie die Geschichte einer Änderung.
- Abhängigkeiten nur, wenn es nicht ohne geht: Heute sind es Spring Boot mit Web MVC, Actuator und Validation; HTTP läuft über `java.net.http`, JSON über das Baummodell von Jackson aus Spring Boot. Was dazukommt, wird exakt gepinnt und begründet.

## CI

| Workflow | Jobs |
|---|---|
| `tests.yml` | `./gradlew check`: Tests, Format, Abdeckung |
| `integration-erpnext.yml` | `./gradlew erpnextTest` in Pull Requests, die ERPNext-Client, Lieferung, Konfiguration, die Suite oder den Build berühren, auf `main` und wöchentlich, mit 35 min Zeitlimit; ein fehlgeschlagener wöchentlicher Lauf öffnet oder ergänzt ein Issue. Das Image ist groß und eine neue Site braucht Minuten, deshalb nicht in jedem Pull Request |
| `integration.yml` | `./gradlew hibiscusTest` in jedem Pull Request, auf `main` und wöchentlich: die Suite braucht etwa eine Minute, und der wöchentliche Lauf bemerkt Änderungen an Images oder Runner zwischen den Pull Requests |
| `linters.yml` | pre-commit: Dateihygiene, `shellcheck`, `hadolint`, `zizmor`, Spotless |
| `security.yml` | `gitleaks` über die ganze Historie, `zizmor`, Trivy über die Bibliotheken des ausführbaren Jars, CodeQL |
| `image.yml` | natives Image und Container für `linux/amd64` und `linux/arm64` auf eigenen Runnern, Rauchtest mit erfundenen Zugangsdaten, gehärtet und mit Probe-Port; veröffentlicht nur, wenn `release.yml` es aufruft |
| `release.yml` | bei einem Tag `vX.Y.Z`: prüft Tag gegen `version` in `build.gradle.kts` und `CHANGELOG.md`, veröffentlicht `ghcr.io/encircle360-oss/bankbote:X.Y.Z` und `:X.Y` mit SBOM und Provenienz und legt das GitHub-Release mit dem Abschnitt aus dem Changelog an |

Alle Actions sind per Commit-SHA mit der Version als Kommentar gepinnt, die Basis-Images und die Images der Integrationstests per Digest, Gradle per SHA-256 der Verteilung, die Plugins und Bibliotheken exakt (die Bibliotheken über die BOM von Spring Boot). Dependabot schlägt wöchentlich Updates vor, mit sieben Tagen Wartezeit.

## Ein Release

1. `version` in `build.gradle.kts` auf `X.Y.Z` setzen und in `CHANGELOG.md` den Abschnitt `## [X.Y.Z] - JJJJ-MM-TT` anlegen, per Pull Request.
2. Nach dem Merge den Tag `vX.Y.Z` auf den Merge-Commit setzen. `release.yml` baut, prüft und veröffentlicht.
3. Danach `version` auf die nächste `-SNAPSHOT` setzen.
