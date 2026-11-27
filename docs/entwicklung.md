# Entwicklung

## Aufbau des Repositories

| Pfad | Inhalt |
|---|---|
| `src/main/java/com/encircle360/bankbote/` | der Dienst, Pakete wie in [Architektur](architektur.md#bausteine) |
| `src/main/resources/application.yaml` | Vorgaben von Server, Actuator und `bankbote.*` |
| `src/main/resources/META-INF/spring.factories` | meldet die Analyse für eine fehlerhafte Konfiguration an |
| `src/test/java/com/encircle360/bankbote/` | die Tests, je Klasse neben ihrem Paket |
| `src/test/java/com/encircle360/bankbote/testing/` | nachgebaute Server: `FakeHttpServer` (HTTP und HTTPS des JDK), `FakeHibiscus` (JSON-Beans mit eigenem Zertifikat und Master-Passwort), `FakeErpNext` (Bankkonten und Bankumsätze der REST-API mit Token), `FakeSource` (Quelle im Speicher), `TestCertificate` (selbst signierte Zertifikate mit `keytool`, nur im temporären Verzeichnis) |
| `Containerfile`, `.dockerignore` | natives Image mit GraalVM, Laufzeit auf distroless |
| `.github/workflows/` | CI, siehe unten |
| `.github/scripts/smoke-test.sh` | Rauchtest des Images |
| `docs/` | Dokumentation |

## Einrichtung

Nötig ist ein JDK 25. Gradle kommt über den Wrapper (`./gradlew`), dessen Verteilung mit SHA-256 gepinnt ist. Eine Toolchain lädt der Build nicht selbst; `JAVA_HOME` oder der `PATH` muss auf ein JDK 25 zeigen. Auf NixOS etwa ein JDK 25 aus nixpkgs in einer `nix shell`.

```bash
./gradlew check            # tests, format check and the coverage gate
./gradlew spotlessApply    # formats the sources with google-java-format
./gradlew bootRun --args='--spring.config.additional-location=file:./local/'   # runs against a local configuration
```

Das native Image baut nur die CI, im Container. Lokal geht es mit `docker build -f Containerfile -t bankbote .`, braucht aber mehrere GB Arbeitsspeicher und einige Minuten.

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
| `HibiscusSourceTest` | die JSON-Beans über HTTPS mit Master-Passwort, vorgemerkte und unlesbare Umsätze, Fenster, Weiterleitungen, fremdes Zertifikat, Zeitlimit, Größengrenze, Zeichensatz |
| `ErpNextClientTest` | Bankkonten seitenweise, vorhandene Umsätze, Anlegen und Einreichen, Fehlerarten, Token nie an eine andere Adresse |
| `BankTransactionMapperTest` | die Felder des Bankumsatzes |
| `DeliveryTest` | Mandantentrennung mit zwei und drei Zielen, ausgefallene, langsame und den Schlüssel ablehnende Ziele, Idempotenz, Entwürfe, Zuordnung der Bankkonten, vorgemerkte und unlesbare Umsätze |
| `RunCoordinatorTest` | ein Lauf zur Zeit, Zusammenfassen von Anfragen, Nachholen, Wartezeit der Benachrichtigung |
| `ConfigurationCheckTest`, `StartupFailureTest`, `BankboteConfigurationTest` | jede Regel der Konfiguration und der Start mit falscher Zuordnung |
| `BankboteServiceTest` | der ganze Dienst gegen `FakeHibiscus` und drei `FakeErpNext`, mit Benachrichtigung, Health und Log ohne Geheimnisse und persönliche Daten |
| `ReportsTest`, `DeliveryHealthIndicatorTest`, `TriggerControllerTest` | Zusammenfassung, Log, Health, Endpunkte nur von Loopback |

Was nur gegen einen echten Hibiscus Server und ein echtes ERPNext geprüft werden kann, steht im [README](../README.md#was-getestet-ist-und-was-nicht) und in [Betrieb](betrieb.md#der-dienstbenutzer-in-erpnext).

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
| `linters.yml` | pre-commit: Dateihygiene, `shellcheck`, `hadolint`, `zizmor`, Spotless |
| `security.yml` | `gitleaks` über die ganze Historie, `zizmor`, Trivy über die Bibliotheken des ausführbaren Jars, CodeQL |
| `image.yml` | natives Image und Container für `linux/amd64` und `linux/arm64` auf eigenen Runnern, Rauchtest mit erfundenen Zugangsdaten; veröffentlicht nur, wenn `release.yml` es aufruft |
| `release.yml` | bei einem Tag `vX.Y.Z`: prüft Tag gegen `version` in `build.gradle.kts` und `CHANGELOG.md`, veröffentlicht `ghcr.io/encircle360-oss/bankbote:X.Y.Z` und `:X.Y` mit SBOM und Provenienz und legt das GitHub-Release mit dem Abschnitt aus dem Changelog an |

Alle Actions sind per Commit-SHA mit der Version als Kommentar gepinnt, die Basis-Images per Digest, Gradle per SHA-256 der Verteilung, die Plugins und Bibliotheken exakt (die Bibliotheken über die BOM von Spring Boot). Dependabot schlägt wöchentlich Updates vor, mit sieben Tagen Wartezeit.

## Ein Release

1. `version` in `build.gradle.kts` auf `X.Y.Z` setzen und in `CHANGELOG.md` den Abschnitt `## [X.Y.Z] - JJJJ-MM-TT` anlegen, per Pull Request.
2. Nach dem Merge den Tag `vX.Y.Z` auf den Merge-Commit setzen. `release.yml` baut, prüft und veröffentlicht.
3. Danach `version` auf die nächste `-SNAPSHOT` setzen.
