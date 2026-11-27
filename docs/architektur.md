# Architektur

Bankbote ist ein kleiner Spring-Boot-Dienst in Java 25, gebaut als natives Image mit GraalVM. Er hat keine Datenbank und keinen eigenen Zustand außer dem Bericht des letzten Laufs: Was schon geliefert ist, weiß ERPNext.

## Bausteine

| Paket | Aufgabe |
|---|---|
| `account` | `Iban`: prüft eine IBAN nach ISO 13616 (mod 97), normalisiert sie und zeigt sie in `toString()` nur maskiert, etwa `DE17…0001` |
| `secret` | `Secret`: liest ein Geheimnis aus einer Datei mit einer Zeile; `toString()` zeigt nie den Wert |
| `tls` | `TlsContexts`: der Kontext mit genau einem gepinnten Zertifikat für den Hibiscus Server, der mit eigenen Zertifizierungsstellen und der des Systems für ERPNext |
| `source` | die Schnittstelle `TransactionSource` mit `SourceAccount`, `SourceTransaction` und `SourceEntries`, unabhängig von Hibiscus |
| `hibiscus` | `HibiscusClient` liest die JSON-Beans, `HibiscusSource` macht daraus Konten und Umsätze, `HibiscusValues` liest Beträge, Daten, Flags und Referenzen, siehe [Hibiscus Server](hibiscus.md) |
| `erpnext` | `ErpNextClient`: ein Client je Ziel für die REST-API mit dem Schlüssel dieses Ziels, ohne Weiterleitungen; `BankTransactionDocument` und die Antworten |
| `delivery` | `Delivery` führt einen Lauf aus, `BankTransactionMapper` bildet die Felder, `RunCoordinator` sorgt für einen Lauf zur Zeit, `ScheduledRuns` für Start und Zeitplan, `RunReportLog` und die Berichte für die Zusammenfassung |
| `config` | `BankboteProperties`, `ConfigurationCheck` mit allen Regeln, `BankboteConfiguration` baut daraus den Dienst, `InvalidConfigurationFailureAnalyzer` zeigt die Fehler beim Start |
| `web` | `TriggerController` mit `POST /notify` und `POST /run`, `DeliveryHealthIndicator` für den Health-Endpunkt |

Eine weitere Quelle implementiert `TransactionSource` und hält deren Regeln: Konten mit IBAN und Währung, Umsätze mit stabiler ID, exakten Beträgen und dem Kennzeichen „vorgemerkt“, und was sich nicht sicher lesen lässt, als `Unreadable`. Der Rest von Bankbote bleibt, wie er ist.

## Ein Lauf

1. `RunCoordinator` startet den Lauf auf seinem einzigen Thread. Der erste Tag des Fensters ist heute minus `lookback-days` in der Zeitzone `time-zone`, beim ersten Lauf nach dem Start `backfill-from`, wenn gesetzt.
2. `Delivery` liest alle Konten der Quelle und ordnet sie nach gültiger IBAN. Konten ohne Zuordnung werden als `UnmappedAccount` gezählt; ihre Umsätze werden nur gezählt, nie geliefert.
3. Für jedes Ziel startet `Delivery` einen virtuellen Thread. Ein Ziel bearbeitet seine Konten nacheinander:
   1. Hat die Quelle die IBAN nicht oder mehrmals, wird das Konto nicht geliefert.
   2. Umsätze des Fensters lesen; vorgemerkte zählen, unlesbare als fehlgeschlagen zählen.
   3. Gibt es etwas zu liefern, beim ersten Mal die Bankkonten des Unternehmens holen (`Bank Account` mit `is_company_account = 1`) und das eine mit der IBAN wählen, verglichen nach Normalisierung, also auch mit Leerzeichen oder Kleinbuchstaben in ERPNext.
   4. In ERPNext nachsehen, welche `transaction_id` dieser Umsätze das Bankkonto schon hat (je 50 in einer Anfrage).
   5. Je Umsatz, ältester zuerst: schon eingereicht oder storniert → übersprungen; nur als Entwurf vorhanden → eingereicht; sonst angelegt und eingereicht.
4. Jeder Thread gibt einen `TargetReport` zurück; `Delivery` setzt den `RunReport` zusammen, `RunReportLog` schreibt ihn ins Log, `DeliveryHealthIndicator` zeigt ihn.

### Fehler

Ein Fehler betrifft so wenig wie möglich:

| Fehler | Folge |
|---|---|
| Quelle nicht erreichbar oder Passwort abgelehnt | der Lauf endet mit `sourceError`, kein Ziel wird gefragt |
| Umsätze eines Kontos nicht lesbar | nur dieses Konto |
| ein Umsatz unlesbar | nur dieser Umsatz, als fehlgeschlagen gezählt |
| kein oder mehr als ein Bankkonto mit der IBAN | nur dieses Konto |
| ERPNext lehnt einen Umsatz ab (HTTP 4xx, etwa `ValidationError`) | nur dieser Umsatz |
| ERPNext nicht erreichbar, langsam, HTTP 5xx oder Weiterleitung | dieses Ziel für den Rest des Laufs; seine übrigen Umsätze zählen als fehlgeschlagen |
| ERPNext lehnt den Schlüssel ab (HTTP 401, 403) | dieses Ziel für den Rest des Laufs |
| ERPNext behält die `transaction_id` nicht | dieses Ziel für den Rest des Laufs, weil weitere Lieferungen Dubletten erzeugen könnten |
| ein unerwarteter Fehler im Lauf | der Lauf endet mit Bericht, der nächste läuft normal |

Die anderen Ziele laufen in eigenen Threads mit eigenen Clients und eigenen Zeitlimits weiter; ein Ziel kann sie weder aufhalten noch scheitern lassen.

## Idempotenz

Die `transaction_id` ist `<source-key>:<ID des Umsatzes im Hibiscus Server>`, etwa `hibiscus:4711`. Sie ist für jeden Lauf gleich. Vor dem Anlegen fragt Bankbote ERPNext nach genau diesen Kennungen am Bankkonto:

- **Zweimal laufen, überlappende Fenster:** Was vorhanden ist, wird übersprungen.
- **Absturz oder Zeitüberschreitung beim Anlegen:** Bankbote legt mit `docstatus` 1 an, ERPNext legt an und reicht ein in einer Anfrage, ohne Entwurf dazwischen. Ist die Antwort verloren gegangen, findet der nächste Lauf den Umsatz und überspringt ihn.
- **Liegengebliebener Entwurf:** Hat ERPNext einen Umsatz nur als Entwurf (etwa von Hand angelegt, oder weil ein ERPNext beim Anlegen nicht einreicht), reicht Bankbote ihn ein, statt einen zweiten anzulegen.
- **Kennung in ERPNext nicht eindeutig:** `transaction_id` ist in der Datenbank nicht eindeutig. Hält ERPNext eine Kennung mehrmals, zählt sie als vorhanden und das Log nennt es.
- **ERPNext übernimmt die Kennung nicht:** Jede Antwort auf ein Anlegen muss die gesendete `transaction_id` tragen. Fehlt sie, hält Bankbote das Ziel an, weil die Prüfung auf Vorhandenes sonst ins Leere ginge.

`source-key` darf sich deshalb für einen Hibiscus Server nie ändern, und zwei Hibiscus Server, die dieselben Ziele beliefern, brauchen verschiedene Schlüssel.

Die Sperre gegen gleichzeitige Läufe gilt im Prozess. Zwei Instanzen gegen dieselben Ziele zu betreiben, ist nicht vorgesehen.

## Felder

| Bank Transaction | aus | Regel |
|---|---|---|
| `date` | Buchungstag (`datum`) | |
| `bank_account` | das eine Bankkonto des Unternehmens mit der IBAN | daraus folgt in ERPNext das Unternehmen |
| `currency` | Währung des Kontos im Hibiscus Server | |
| `deposit`, `withdrawal` | Betrag | positiv → `deposit`, negativ → `withdrawal` mit Betrag ohne Vorzeichen, die andere Seite 0; 0 auf beiden Seiten bei 0 |
| `description` | Verwendungszweck | alle Zeilen, ungekürzt |
| `reference_number` | Ende-zu-Ende-Referenz (`endtoendid`), sonst Kundenreferenz (`customerref`) | ohne `NOTPROVIDED` und `NONREF` |
| `transaction_id` | `<source-key>:<id>` | |
| `transaction_type` | Art (`art`) | |
| `bank_party_name` | Name der Gegenseite | |
| `bank_party_iban` | Konto der Gegenseite, wenn es eine gültige IBAN ist | normalisiert |
| `bank_party_account_number` | Konto der Gegenseite, wenn es keine IBAN ist | |

Felder vom Typ Data werden auf die 140 Zeichen von Frappe gekürzt.

**Warum die Ende-zu-Ende-Referenz `reference_number` wird:** Der Bankabgleich von ERPNext vergleicht `reference_number` mit der Referenz von Zahlungen. Die Ende-zu-Ende-Referenz einer SEPA-Zahlung legt der Zahler fest, oft mit der Rechnungsnummer; sie ist für den Abgleich die nützlichste. Die Kundenreferenz ist der Ersatz, wenn die Bank keine Ende-zu-Ende-Referenz liefert. Die Primanota ist eine interne Nummer der Bank, mit der in ERPNext nichts zu vergleichen ist; sie wird nicht übertragen.

## Entscheidungen

- **JSON-Beans statt XML-RPC:** nur sie zeigen vorgemerkte Umsätze, siehe [Hibiscus Server](hibiscus.md#welche-schnittstelle-bankbote-liest).
- **Anlegen mit `docstatus` 1:** kein Entwurf zwischen Anlegen und Einreichen; Entwürfe, die es trotzdem gibt, reicht Bankbote ein.
- **Ein Lauf für alle Konten statt je Konto:** Die Benachrichtigung des Hibiscus Servers nennt ein Konto, kommt aber vor dessen Umsätzen und nicht für jede Synchronisierung. Ein Lauf über alle Konten mit Verzögerung ist einfacher und verpasst nichts.
- **Kein eigener Zustand:** Ohne Datenbank kann nichts zwischen Bankbote und ERPNext auseinanderlaufen. Der Preis sind wenige Anfragen je Konto und Lauf.
- **Virtuelle Threads je Ziel:** Ziele sind voneinander unabhängig; innerhalb eines Ziels bleibt die Reihenfolge der Umsätze erhalten.
- **Keine Abhängigkeiten außer Spring Boot:** HTTP über `java.net.http`, JSON über das Baummodell von Jackson aus Spring Boot. Das hält das native Image frei von Reflexion auf eigene Klassen.
