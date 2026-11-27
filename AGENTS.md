# Hinweise für Coding-Agenten

Der Einsatz von KI-Werkzeugen und Coding-Agenten ist in diesem Projekt ausdrücklich erlaubt, für Maintainer wie für Mitwirkende. Verantwortlich bleibt immer der Mensch, der den Pull Request stellt und die Änderung versteht. Diese Datei ist der Einstieg. Die Regeln stehen in [CONTRIBUTING.md](CONTRIBUTING.md) und [docs/entwicklung.md](docs/entwicklung.md) und gelten für Menschen und Agenten gleichermaßen.

## Was ein Agent wissen muss

- **Projekt:** Dienst in Java 25 mit Spring Boot 4 und Gradle (Wrapper), gebaut als natives Image mit GraalVM im Container. Er liest gebuchte Umsätze von einem Hibiscus Server und liefert sie je Konto an die ERPNext-16-Installation des Unternehmens, dem das Konto gehört. Keine App in ERPNext, nur dessen REST-API.
- **Struktur:** [docs/architektur.md](docs/architektur.md). Die Quelle ist die Schnittstelle `source/TransactionSource`; einzige Umsetzung ist `hibiscus/HibiscusSource` über die JSON-Beans des Hibiscus Servers. `erpnext/ErpNextClient` ist ein Client je Ziel. `delivery/Delivery` führt einen Lauf aus, `delivery/RunCoordinator` sorgt für einen Lauf zur Zeit. `config/ConfigurationCheck` prüft die Konfiguration.
- **Tests:** unter `src/test/java`, je Klasse neben ihrem Paket; nachgebaute Server in `testing/`. `BankboteServiceTest` startet den ganzen Dienst gegen einen nachgebauten Hibiscus Server und drei nachgebaute ERPNext. Die Integrationstests unter `src/integrationTest` laufen gegen echte Server in Containern (`./gradlew hibiscusTest`), außerhalb von `./gradlew check`; ihre Images stehen gepinnt in `src/integrationTest/images/Dockerfile`, siehe [Integrationstests](docs/entwicklung.md#integrationstests). Eine Suite zur Zeit, Container danach aufräumen.
- **Sprache:** Code, Kommentare und Commits englisch. Dokumentation, Issues und Pull Requests deutsch. Commits im Imperativ, klein geschrieben, ohne Präfixe und Fußzeilen ([Commits](CONTRIBUTING.md#commits)).
- **Quellen:** Was Bankbote über Hibiscus Server weiß, steht mit Datei und Commit in [docs/hibiscus.md](docs/hibiscus.md). Was dort nicht steht, wird nicht aus dem Gedächtnis ergänzt, sondern im Quelltext von Hibiscus nachgeschlagen und dort eingetragen. Annahmen über ERPNext, die nicht gegen ein echtes ERPNext geprüft sind, stehen in [docs/betrieb.md](docs/betrieb.md#der-dienstbenutzer-in-erpnext) und im README.

## Leitplanken

1. **Test zuerst, sichtbar in der Historie.** Erst ein Commit mit dem Test, der fehlschlägt, dann der Commit, der ihn grün macht. Kein Test wird abgeschwächt, übersprungen oder gelöscht, um grün zu werden. Zeilen und Zweige haben 100 % Abdeckung, und das bleibt so.
2. **Geschlossen fehlschlagen.** Ein Umsatz, der sich nicht sicher lesen, als gebucht erkennen oder zuordnen lässt, wird nicht geliefert. Ein vorgemerkter Umsatz wird nie geliefert.
3. **Ein Konto, ein Ziel.** Nichts darf einen Umsatz an ein anderes Ziel geben als das konfigurierte, und kein Schlüssel darf eine andere Adresse erreichen als die seines Ziels.
4. **Geld ist `BigDecimal`.** Nie `double`, nie runden.
5. **Keine echten Daten.** Keine echten IBANs, Namen, Zwecke, Zugangsdaten, Zertifikate, Hostnamen, Banken oder Unternehmen in Code, Tests, Fixtures, Logs, Issues oder Commits. Testdaten sind erfunden: IBANs mit gültiger Prüfziffer und Bankleitzahl `12345678`, Adressen unter `example.com`.
6. **Keine Geheimnisse beim Agenten.** Ein Agent fragt nie nach einem Master-Passwort, API-Schlüssel oder Zertifikat eines echten Systems, liest sie nie aus und speichert sie nie.
7. **Nichts Geheimes und nichts Persönliches** in Logs, Ausnahmen, `toString()` oder Health-Ausgabe. Der Test des ganzen Dienstes prüft das.
8. **Ein Pull Request, ein Thema.** Dokumentation im selben Pull Request. Kleine Schritte.
9. **Nichts Ungeprüftes nach `main`.** Ein Maintainer prüft Zuordnung, Idempotenz, TLS und Umgang mit Geheimnissen vor dem Merge. Die CI muss grün sein.
10. **Keine neuen Abhängigkeiten ohne Grund.** Was dazukommt, wird exakt gepinnt und begründet; Actions per Commit-SHA, Basis-Images per Digest.
11. **Nichts Fremdes übernehmen oder nennen.** Andere Projekte dürfen inspirieren. Code wird eigenständig geschrieben; genannt werden nur Hibiscus, Hibiscus Server, Jameica, Frappe und ERPNext, die Bankbote nutzt, kein anderes Produkt.
12. **Kein Vermerk über KI.** Code, Dokumentation, Commits und Pull Requests nennen keine KI-Werkzeuge, Coding-Agenten oder Assistenten, die an einem Beitrag mitgearbeitet haben, auch nicht in Fußzeilen.
13. **Kein natives Image lokal, wenn der Speicher knapp ist.** Das native Image baut die CI; lokal genügen `./gradlew check` und `pre-commit run --all-files`.

## Wiederkehrende Aufgaben

- **Ein Feld mehr übertragen:** Beleg in `docs/hibiscus.md`, Test in `HibiscusSourceTest` und `BankTransactionMapperTest`, Feld in `SourceTransaction`, `HibiscusSource`, `BankTransactionMapper`, `BankTransactionDocument` und `ErpNextClient.insertSubmitted`, Tabelle in `docs/architektur.md`.
- **Eine neue Quelle:** `TransactionSource` umsetzen, mit stabiler ID je gebuchtem Umsatz, exakten Beträgen und dem Kennzeichen „vorgemerkt“; Tests nach dem Muster von `HibiscusSourceTest` gegen einen nachgebauten Server; eigene Seite unter `docs/` mit Belegen; Auswahl in `BankboteConfiguration`.
- **Eine neue Regel der Konfiguration:** Test in `ConfigurationCheckTest` mit der genauen Meldung, Regel in `ConfigurationCheck`, Zeile in `docs/konfiguration.md`.
