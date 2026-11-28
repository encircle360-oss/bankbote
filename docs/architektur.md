# Architektur

Bankbote ist ein kleiner Spring-Boot-Dienst in Java 25, gebaut als natives Image mit GraalVM. Er hat keine Datenbank und keinen eigenen Zustand außer dem Bericht des letzten Laufs: Was schon geliefert ist, weiß ERPNext.

## Bausteine

| Paket | Aufgabe |
|---|---|
| `account` | `Iban`: prüft eine IBAN nach ISO 13616 (mod 97), normalisiert sie und zeigt sie in `toString()` nur maskiert, etwa `DE17…0001` |
| `secret` | `Secret`: liest ein Geheimnis aus einer Datei mit einer Zeile; `toString()` zeigt nie den Wert |
| `tls` | `TlsContexts`: der Kontext mit genau einem gepinnten Zertifikat für den Hibiscus Server, der mit eigenen Zertifizierungsstellen und der des Systems für ERPNext |
| `net` | `Failures`: beschreibt eine gescheiterte Verbindung mit Klasse und Meldung, ohne Inhalte |
| `source` | die Schnittstelle `TransactionSource` mit `SourceAccount`, `SourceTransaction` und `SourceEntries`, unabhängig von Hibiscus |
| `hibiscus` | `HibiscusClient` liest die JSON-Beans, `HibiscusSource` macht daraus Konten und Umsätze, `HibiscusValues` liest Beträge, Daten, Flags und Referenzen, siehe [Hibiscus Server](hibiscus.md) |
| `erpnext` | `ErpNextClient`: ein Client je Ziel für die REST-API mit dem Schlüssel dieses Ziels, ohne Weiterleitungen; `BankTransactionDocument` und die Antworten |
| `delivery` | `Delivery` führt einen Lauf aus, `BankTransactionMapper` bildet die Felder, `RunCoordinator` sorgt für einen Lauf zur Zeit, `ScheduledRuns` für Start und Zeitplan, `RunReportLog` und die Berichte für die Zusammenfassung |
| `config` | `BankboteProperties`, `ConfigurationCheck` mit allen Regeln, `BankboteConfiguration` baut daraus den Dienst, `InvalidConfigurationFailureAnalyzer` zeigt die Fehler beim Start |
| `web` | `TriggerController` mit `POST /notify` und `POST /run`, `DeliveryHealthIndicator` für den Health-Endpunkt, `ProbePort` für den optionalen Probe-Port, der nur Liveness und Readiness beantwortet |

Eine weitere Quelle implementiert `TransactionSource` und hält deren Regeln: Konten mit IBAN und Währung; Umsätze mit Buchungstag, Valuta, exaktem Betrag, Verwendungszweck, Gegenkonto, der Transaktions-ID der Bank, wenn es eine gibt, und dem Kennzeichen „vorgemerkt“; IDs, die nach ihrem Zahlenwert in der Reihenfolge stehen, in der die Quelle die Umsätze bekam; jeder gebuchte Umsatz eines Tages vollständig; und was sich nicht sicher lesen lässt, als `Unreadable`. Der Rest von Bankbote bleibt, wie er ist; die `transaction_id` hängt nicht von der Quelle ab, siehe [Die Kennung](#die-kennung-transaction_id).

## Ein Lauf

1. `RunCoordinator` startet den Lauf auf seinem einzigen Thread. Der erste Tag des Fensters ist heute minus `lookback-days` in der Zeitzone `time-zone`, beim ersten Lauf nach dem Start `backfill-from`, wenn gesetzt.
2. `Delivery` liest alle Konten der Quelle und ordnet sie nach gültiger IBAN. Konten ohne Zuordnung werden als `UnmappedAccount` gezählt; ihre Umsätze werden nur gezählt, nie geliefert.
3. Für jedes Ziel startet `Delivery` einen virtuellen Thread. Ein Ziel bearbeitet seine Konten nacheinander:
   1. Hat die Quelle die IBAN nicht oder mehrmals, wird das Konto nicht geliefert.
   2. Umsätze des Fensters lesen; vorgemerkte zählen, unlesbare als fehlgeschlagen zählen. Jeder gebuchte Umsatz bekommt seine `transaction_id`, siehe [Die Kennung](#die-kennung-transaction_id).
   3. Gibt es etwas zu liefern, beim ersten Mal die Bankkonten des Unternehmens holen (`Bank Account` mit `is_company_account = 1`) und das eine mit der IBAN wählen, verglichen nach Normalisierung, also auch mit Leerzeichen oder Kleinbuchstaben in ERPNext.
   4. In ERPNext nachsehen, welche `transaction_id` dieser Umsätze das Bankkonto schon hat (je 50 in einer Anfrage).
   5. Je Umsatz, ältester zuerst: schon eingereicht oder storniert → übersprungen; nur als Entwurf vorhanden → fehlgeschlagen mit einer Meldung, weil der Dienstbenutzer keinen Entwurf einreichen darf; sonst angelegt und in derselben Anfrage eingereicht.
4. Jeder Thread gibt einen `TargetReport` zurück; `Delivery` setzt den `RunReport` zusammen, `RunReportLog` schreibt ihn ins Log, `DeliveryHealthIndicator` zeigt ihn.

### Fehler

Ein Fehler betrifft so wenig wie möglich:

| Fehler | Folge |
|---|---|
| Quelle nicht erreichbar oder Passwort abgelehnt | der Lauf endet mit `sourceError`, kein Ziel wird gefragt; `RunCoordinator` wiederholt ihn einmal nach `notify-delay`, danach erst wieder nach einem erfolgreichen Lauf |
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

Die `transaction_id` folgt aus dem Umsatz selbst, nicht aus der Datenbank der Quelle, siehe [Die Kennung](#die-kennung-transaction_id). Sie ist für jeden Lauf gleich. Vor dem Anlegen fragt Bankbote ERPNext nach genau diesen Kennungen am Bankkonto:

- **Zweimal laufen, überlappende Fenster:** Was vorhanden ist, wird übersprungen.
- **Absturz oder Zeitüberschreitung beim Anlegen:** Bankbote legt mit `docstatus` 1 an, ERPNext legt an und reicht ein in einer Anfrage, ohne Entwurf dazwischen. Ist die Antwort verloren gegangen, findet der nächste Lauf den Umsatz und überspringt ihn.
- **Entwurf:** Hält ERPNext einen Umsatz mit dieser Kennung nur als Entwurf, etwa von Hand angelegt, zählt er als fehlgeschlagen mit der Meldung `… holds b1-…-1 as a draft; submit or delete it there`, und Bankbote legt ihn nicht noch einmal an. Einreichen könnte Bankbote ihn nur mit dem Recht Write, das der Dienstbenutzer nicht hat, siehe [Betrieb](betrieb.md#der-dienstbenutzer-in-erpnext). Antwortet ERPNext auf ein Anlegen mit einem Entwurf, gilt dasselbe.
- **Kennung in ERPNext nicht eindeutig:** `transaction_id` ist in der Datenbank nicht eindeutig. Hält ERPNext eine Kennung mehrmals, zählt sie als vorhanden und das Log nennt es.
- **ERPNext übernimmt die Kennung nicht:** Jede Antwort auf ein Anlegen muss die gesendete `transaction_id` tragen. Fehlt sie, hält Bankbote das Ziel an, weil die Prüfung auf Vorhandenes sonst ins Leere ginge.
- **Neu aufgebaute Quelle:** Eine neue Datenbank des Hibiscus Servers oder ein gelöschtes und neu abgerufenes Konto gibt denselben Umsätzen neue IDs. Die Kennungen bleiben dieselben, Bankbote liefert nichts doppelt und überspringt keinen neuen Umsatz.

Die Sperre gegen gleichzeitige Läufe gilt im Prozess. Zwei Instanzen gegen dieselben Ziele zu betreiben, ist nicht vorgesehen.

## Die Kennung `transaction_id`

Die Kennung ist so festgelegt, dass eine andere Umsetzung oder eine andere Quelle mit denselben Daten der Bank sie nachrechnen kann. Ihre Form ist

```text
b1-<Art>-<Hash>-<laufende Nummer>
```

- `b1` ist die Version des Schemas. Wer das Verfahren unten ändert, braucht eine neue Version; mit ihr ändern sich alle Kennungen.
- `<Art>` ist `r`, wenn die Bank dem Umsatz eine eigene Transaktions-ID gegeben hat (**Bankreferenz**), sonst `c` (**Inhalt**).
- `<Hash>` sind die ersten 128 Bit von SHA-256 über die kanonische Form, 32 Zeichen hexadezimal in Kleinbuchstaben.
- `<laufende Nummer>` ist der Rang des Umsatzes unter allen gebuchten Umsätzen desselben Kontos mit demselben Schlüssel (`b1-<Art>-<Hash>`), in der Reihenfolge der Quelle, ab 1.

Eine Kennung besteht nur aus `[a-z0-9-]`, ist bei einstelliger Nummer 39 Zeichen lang, weit unter den 140 eines Felds vom Typ Data, und enthält keinen Doppelpunkt; mit einer Kennung von Bankbote 0.1.x (`hibiscus:4711`) kann sie nicht zusammenfallen.

### Kanonische Form

Die Felder stehen in fester Reihenfolge, getrennt durch `|`, das nach der Normalisierung in keinem Feld vorkommen kann, mit `b1` und der Art vorneweg. Gehasht wird die Zeichenkette als UTF-8.

| Art | kanonische Form |
|---|---|
| Bankreferenz | `b1\|r\|<IBAN>\|<Buchungstag>\|<Betrag>\|<Bank-ID>` |
| Inhalt | `b1\|c\|<IBAN>\|<Buchungstag>\|<Valuta>\|<Betrag>\|<Gegenkonto>\|<Verwendungszweck>` |

| Feld | Form |
|---|---|
| IBAN | die IBAN des Kontos, in Großbuchstaben, ohne Leerzeichen |
| Buchungstag, Valuta | ISO 8601, `JJJJ-MM-TT`. Hat die Quelle keine Valuta, gilt der Buchungstag |
| Betrag | Dezimalzahl mit Vorzeichen, ohne Nullen am Ende und ohne Exponent: `-119`, `0.5`, `1234.56`, `0`. Die Nachkommastellen der Quelle spielen so keine Rolle |
| Bank-ID, Gegenkonto, Verwendungszweck | Text: Unicode-Normalform NFKC, dann Großbuchstaben ohne Rücksicht auf eine Sprache (`Locale.ROOT`, `ß` wird `SS`), dann bleiben nur Buchstaben (Unicode-Kategorien L) und Dezimalziffern (Nd). Zeilenumbrüche, Leerzeichen und Satzzeichen ändern so nichts |

Eine Bank-ID gilt als fehlend, wenn ihre Normalform leer oder einer der Platzhalter `NONREF`, `NOTPROVIDED`, `NOTAVAILABLE` oder `NOREF` ist; dann gilt der Schlüssel aus dem Inhalt.

**Warum die Bank-ID normalisiert wird statt nur beschnitten:** Eine andere Quelle oder ein anderes Format kann dieselbe ID mit anderen Leerzeichen oder Satzzeichen wiedergeben, was mit bloßem Beschneiden doppelte Lieferungen ergäbe, während zwei IDs, die erst durch die Normalisierung gleich werden, nichts verlieren: Sie bekommen verschiedene laufende Nummern und werden beide geliefert.

Die Art des Umsatzes, der Name der Gegenseite und die Referenzen gehören nicht zum Schlüssel; die Bank schreibt sie je nach Format verschieden.

### Laufende Nummer

Zwei Umsätze, die sich in nichts unterscheiden, etwa dieselbe Kartenzahlung zweimal an einem Tag, haben denselben Schlüssel. Die laufende Nummer macht daraus `…-1` und `…-2`, so wie Hibiscus beim Abruf zählt, wie viele gleiche Umsätze es schon hat (siehe [Hibiscus Server](hibiscus.md#stabile-ids)). Dafür gilt:

- Gezählt werden nur gebuchte Umsätze; ein vorgemerkter zählt nie mit.
- Gezählt wird in der Reihenfolge der Quelle, beim Hibiscus Server nach aufsteigender ID, verglichen als Zahl.
- Der Schlüssel enthält den Buchungstag, gezählt wird also je Tag. Bankbote liest deshalb jeden Tag des Fensters vollständig, auch den ersten, siehe [Hibiscus Server](hibiscus.md#welche-schnittstelle-bankbote-liest).
- Kommt später ein weiterer gleicher Umsatz desselben Tages hinzu, bekommt er die nächste Nummer und wird geliefert; die früheren behalten ihre.
- Haben Umsätze mit derselben Bank-ID verschiedenen Inhalt, weil eine Bank eine ID mehrfach vergibt, werden sie mit laufenden Nummern geliefert, und das Log nennt je Lauf und Konto einmal ihre Anzahl, mit maskierter IBAN und ohne Inhalt.

### Beispiele

Berechnet vom Code, dieselben Werte prüft `TransactionIdsTest`. Konto `DE17123456780000000001`, Gegenkonto `DE87 1234 5678 0000 0000 02`, Zweck `Rechnung 2026-0042` und `für Müller & Söhne` in zwei Zeilen:

| Buchungstag | Valuta | Betrag | Gegenkonto | Zweck | Bank-ID | Nummer | `transaction_id` |
|---|---|---|---|---|---|---|---|
| 2026-11-20 | 2026-11-20 | -119.00 | wie oben | wie oben | – | 1 | `b1-c-e2a692a01368e5b6c192945bb8bcc12c-1` |
| 2026-11-20 | 2026-11-20 | -119.00 | wie oben | wie oben | `NOTPROVIDED` | 1 | `b1-c-e2a692a01368e5b6c192945bb8bcc12c-1` |
| 2026-11-20 | 2026-11-20 | -119.00 | wie oben | wie oben | `TX-2026-0001` | 1 | `b1-r-99684f9a4eb07a1404badb1bf21830fd-1` |
| 2026-11-21 | – | -4.50 | – | `Kartenzahlung Bäckerei` | – | 2 | `b1-c-dabc3fbdda7aa130d25f753ffb4c3807-2` |
| 2026-11-22 | 2026-11-23 | 1234.560 | – | `Gutschrift` | – | 1 | `b1-c-21c5d87925d386bbfb59abd480bc2a32-1` |

Die kanonische Form der ersten Zeile ist `b1|c|DE17123456780000000001|2026-11-20|2026-11-20|-119|DE87123456780000000002|RECHNUNG20260042FÜRMÜLLERSÖHNE`, die der dritten `b1|r|DE17123456780000000001|2026-11-20|-119|TX20260001`.

### Was die Kennung übersteht und was nicht

Sie übersteht eine neu aufgebaute Datenbank des Hibiscus Servers, ein gelöschtes und neu abgerufenes Konto, eine andere Reihenfolge der Zeilen und eine andere Quelle mit denselben Daten der Bank.

Sie übersteht nicht:

- **Die Bank liefert ihre Transaktions-ID neu oder nicht mehr**, etwa nach einem Wechsel von MT940 zu CAMT: Die Umsätze im Fenster bekommen die andere Art von Schlüssel und werden ein zweites Mal geliefert.
- **Die Bank liefert für Umsätze ohne eigene ID einen anderen Text**, etwa einen anders zusammengesetzten Verwendungszweck oder ein anderes Gegenkonto: Die betroffenen Umsätze im Fenster werden ein zweites Mal geliefert.
- **Die Bank ändert einen gebuchten Umsatz nachträglich** in Buchungstag, Valuta, Betrag, Gegenkonto, Zweck oder ID: Er kommt als neuer Umsatz an, der alte bleibt in ERPNext.

Dubletten dieser Art betreffen höchstens das Fenster eines Laufs und stehen in ERPNext nebeneinander; sie lassen sich dort stornieren.

## Felder

| Bank Transaction | aus | Regel |
|---|---|---|
| `date` | Buchungstag (`datum`) | |
| `bank_account` | das eine Bankkonto des Unternehmens mit der IBAN | daraus folgt in ERPNext das Unternehmen |
| `currency` | Währung des Kontos im Hibiscus Server | |
| `deposit`, `withdrawal` | Betrag | positiv → `deposit`, negativ → `withdrawal` mit Betrag ohne Vorzeichen, die andere Seite 0; 0 auf beiden Seiten bei 0 |
| `description` | Verwendungszweck | alle Zeilen, ungekürzt |
| `reference_number` | Ende-zu-Ende-Referenz (`endtoendid`), sonst Kundenreferenz (`customerref`) | ohne `NOTPROVIDED` und `NONREF`; Small Text, ungekürzt |
| `transaction_id` | Konto, Buchungstag, Valuta, Betrag, Gegenkonto, Zweck oder Transaktions-ID der Bank (`txid`) | siehe [Die Kennung](#die-kennung-transaction_id) |
| `transaction_type` | Art (`art`) | auf 50 Zeichen gekürzt, die Länge des Felds in ERPNext 16 |
| `bank_party_name` | Name der Gegenseite | |
| `bank_party_iban` | Konto der Gegenseite, wenn es eine gültige IBAN ist | normalisiert |
| `bank_party_account_number` | Konto der Gegenseite, wenn es keine IBAN ist | |

Felder vom Typ Data werden auf die 140 Zeichen von Frappe gekürzt, `transaction_type` auf seine 50. Ein Betrag von 0 kommt auf beiden Seiten als 0 an; ERPNext nimmt ihn an und setzt den Status „Reconciled“.

**Warum die Ende-zu-Ende-Referenz `reference_number` wird:** Der Bankabgleich von ERPNext vergleicht `reference_number` mit der Referenz von Zahlungen. Die Ende-zu-Ende-Referenz einer SEPA-Zahlung legt der Zahler fest, oft mit der Rechnungsnummer; sie ist für den Abgleich die nützlichste. Die Kundenreferenz ist der Ersatz, wenn die Bank keine Ende-zu-Ende-Referenz liefert. Die Primanota ist eine interne Nummer der Bank, mit der in ERPNext nichts zu vergleichen ist; sie wird nicht übertragen.

## Entscheidungen

- **JSON-Beans statt XML-RPC:** nur sie zeigen vorgemerkte Umsätze, siehe [Hibiscus Server](hibiscus.md#welche-schnittstelle-bankbote-liest).
- **Anlegen mit `docstatus` 1:** kein Entwurf zwischen Anlegen und Einreichen, und die Rechte Create und Submit genügen; Entwürfe, die es trotzdem gibt, meldet Bankbote, statt sie einzureichen.
- **Ein Lauf für alle Konten statt je Konto:** Die Benachrichtigung des Hibiscus Servers nennt ein Konto, kommt aber vor dessen Umsätzen und nicht für jede Synchronisierung. Ein Lauf über alle Konten mit Verzögerung ist einfacher und verpasst nichts.
- **Kein eigener Zustand:** Ohne Datenbank kann nichts zwischen Bankbote und ERPNext auseinanderlaufen. Der Preis sind wenige Anfragen je Konto und Lauf.
- **Virtuelle Threads je Ziel:** Ziele sind voneinander unabhängig; innerhalb eines Ziels bleibt die Reihenfolge der Umsätze erhalten.
- **Keine Abhängigkeiten außer Spring Boot:** HTTP über `java.net.http`, JSON über das Baummodell von Jackson aus Spring Boot. Das hält das native Image frei von Reflexion auf eigene Klassen.
