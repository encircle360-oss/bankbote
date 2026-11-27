# Sicherheit

## Unterstützte Versionen

Sicherheitskorrekturen gibt es für den Branch `main` und die jeweils neueste Veröffentlichung. Ältere Stände werden nicht nachgepflegt.

## Sicherheitslücke melden

Bitte **nicht** als öffentliches Issue. Melde sie über die private Meldefunktion von GitHub: [„Report a vulnerability“](https://github.com/encircle360-oss/bankbote/security/advisories/new) unter *Security*. Die Meldung sehen nur du und die Maintainer des Repositorys. Ohne GitHub-Konto sende eine Beschreibung an **oss@encircle360.com**.

Hilfreich sind: betroffene Version, Schritte zum Nachstellen, mögliche Auswirkungen und, wenn vorhanden, ein Vorschlag zur Behebung. Keine echten Master-Passwörter, API-Schlüssel, Zertifikate, IBANs oder Umsätze mitschicken.

Wir bestätigen den Eingang so schnell wie möglich, in der Regel innerhalb weniger Werktage, und stimmen die Veröffentlichung einer Korrektur mit dir ab.

## Was Bankbote beim Umgang mit Geheimnissen und Daten zusichert

- Das Master-Passwort des Hibiscus Servers und die API-Schlüssel und -Geheimnisse der ERPNext-Installationen kommen aus Dateien, werden nicht gespeichert, nicht protokolliert und nur an die Adresse geschickt, zu der sie gehören; Weiterleitungen folgt Bankbote nie.
- Beim Hibiscus Server vertraut Bankbote genau dem konfigurierten Zertifikat.
- Jedes Konto geht an genau das eine konfigurierte Ziel; ein Konto ohne Zuordnung geht nirgendwohin.
- Log, Fehlermeldungen und Health-Ausgabe enthalten keine Geheimnisse, keine vollen IBANs und keine Beträge, Verwendungszwecke oder Namen.
- Bankbote spricht nur mit dem konfigurierten Hibiscus Server und den konfigurierten ERPNext-Installationen.
- Abhängigkeiten, Werkzeuge der CI, Actions und Basis-Images sind exakt gepinnt; Dependabot, Trivy, Geheimnis-Scans, `zizmor` und CodeQL laufen in der CI.

Das Sicherheitsmodell und seine Annahmen beschreibt [Sicherheit](docs/sicherheit.md).

Lücken in Hibiscus Server oder ERPNext selbst meldest du bitte direkt beim jeweiligen Projekt.
