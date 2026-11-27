# Mitwirken an Bankbote

Issues und Pull Requests sind willkommen. Bankbote ist ein Dienst, der Kontoumsätze von einem Hibiscus Server an die ERPNext-Installationen der Unternehmen liefert, denen die Konten gehören. Diese Seite beschreibt, wie ein Beitrag abläuft und welche Regeln gelten. Die Regeln gelten für Menschen und für Coding-Agenten gleichermaßen, siehe [AGENTS.md](AGENTS.md).

## Fehler und Wünsche melden

Bitte ein [Issue über die Vorlagen](https://github.com/encircle360-oss/bankbote/issues/new/choose) anlegen. Fragen und Austausch gehen in die [Discussions](https://github.com/encircle360-oss/bankbote/discussions) oder auf [Matrix](https://matrix.to/#/#oss:encircle360.com).

**Keine echten IBANs, Verwendungszwecke, Namen, Beträge, Master-Passwörter, API-Schlüssel, Zertifikate oder Hostnamen** in Issues, Screenshots, Logs oder Pull Requests. Die Zusammenfassung eines Laufs aus Log oder Health-Endpunkt enthält nur maskierte IBANs und Zähler und darf so geteilt werden. Sicherheitslücken bitte nicht öffentlich melden, sondern wie in [SECURITY.md](SECURITY.md) beschrieben.

## Ein Pull Request

1. **Fork und Branch.** Branches heißen nach dem Thema, etwa `quelle-camt-dateien`.
2. **Test zuerst.** Jede Änderung beginnt mit einem Test, der ohne sie fehlschlägt, als eigener Commit vor dem Code. Kein bestehender Test wird abgeschwächt oder gelöscht, um grün zu werden. Die Abdeckung von Zeilen und Zweigen bleibt bei 100 %.
3. **Quelle nennen.** Jedes Verhalten von Hibiscus Server oder ERPNext, auf das sich Bankbote verlässt, braucht einen Beleg: die Stelle im Quelltext mit Commit oder die Dokumentation. Der Beleg steht in `docs/`, nicht nur im Pull Request.
4. **Ein Thema pro Pull Request**, Dokumentation im selben Pull Request.
5. **Lokal prüfen:** `./gradlew check` und `pre-commit run --all-files`, siehe [Entwicklung](docs/entwicklung.md).
6. **Pull Request stellen.** Die Vorlage fragt nach Änderung, Quelle und Checkliste. Die CI muss grün sein.
7. **Review.** Änderungen an Zuordnung, Idempotenz, vorgemerkten Umsätzen, TLS und Geheimnissen merged erst, wenn ein Maintainer sie geprüft hat.

## Regeln im Code

- **Geld ist `BigDecimal`**, nie `double`. Beträge werden genau gelesen oder abgelehnt, nie gerundet.
- **Schlägt geschlossen fehl.** Was sich nicht sicher lesen oder zuordnen lässt, wird nicht geliefert, sondern gezählt und gemeldet.
- **Nichts Geheimes und nichts Persönliches** in Logs, Ausnahmen, `toString()` oder der Health-Ausgabe: keine Passwörter, Schlüssel, vollen IBANs, Beträge, Verwendungszwecke oder Namen.
- **Englisch im Code**, deutsch in der Dokumentation. Begriffe von Hibiscus wie `umsatz`, `konto` oder `zweck` bleiben, wie die Schnittstelle sie nennt.
- **Kommentare erklären das Warum**, nie das Was und nie die Geschichte einer Änderung.
- **Keine neuen Abhängigkeiten ohne Grund.** Was dazukommt, wird exakt gepinnt und im Pull Request begründet.
- **Kein fremder Code.** Andere Projekte dürfen inspirieren; Bankbote wird eigenständig geschrieben und spricht mit Hibiscus und ERPNext nur über ihre Schnittstellen.
- **Stil:** google-java-format über Spotless, `javac` mit `-Xlint:all -Werror`.

## Commits

Commit-Nachrichten sind englisch, im Imperativ und vervollständigen den Satz „If applied, this commit will …“: `test that a pending transaction is never delivered`, `read the flags of a transaction`. Keine Ticket-Präfixe, keine Fußzeilen. Eine Nachricht beschreibt, was sich ändert und warum, wenn das Warum nicht offensichtlich ist.

## Umgangston

Wir gehen respektvoll miteinander um, nehmen Kritik an der Sache an und nicht persönlich, und gehen davon aus, dass alle ihr Bestes geben. Wer das nicht einhält, wird von den Maintainern angesprochen und notfalls ausgeschlossen.

## Lizenz der Beiträge

Mit deinem Beitrag stimmst du zu, dass er unter der [AGPL-3.0-or-later](LICENSE) veröffentlicht wird, und bestätigst, dass du das Recht hast, ihn unter dieser Lizenz beizusteuern.
