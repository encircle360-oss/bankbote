## Was ändert sich?

<!-- Ein Thema pro Pull Request. Was ändert sich für Betreiber, was intern? -->

## Quelle

<!-- Welche Stelle im Quelltext von Hibiscus Server oder ERPNext, welche Dokumentation belegt das Verhalten, auf das sich die Änderung verlässt? -->

## Checkliste

- [ ] Tests zuerst: Jede Änderung hat einen Test, der ohne sie fehlschlägt. Kein Test wurde abgeschwächt.
- [ ] `./gradlew check` läuft lokal durch: Tests, Format und 100 % Abdeckung von Zeilen und Zweigen.
- [ ] Die Dokumentation unter `docs/` und im README passt zum Code.
- [ ] Keine echten IBANs, Namen, Verwendungszwecke, Zugangsdaten, Zertifikate oder Hostnamen in Code, Tests, Logs oder Screenshots.
- [ ] Geldbeträge sind `BigDecimal`, nie `double`; nichts Geheimes und nichts Persönliches erreicht Log, Ausnahme oder Health-Ausgabe.
