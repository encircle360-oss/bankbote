# Hibiscus Server

Diese Seite hält fest, worauf sich Bankbote beim Hibiscus Server verlässt, mit der Stelle im Quelltext. Gelesen wurden die Repositories von Olaf Willuhn auf GitHub im Stand vom 27.11.2026:

| Repository | Commit |
|---|---|
| [willuhn/hibiscus.server](https://github.com/willuhn/hibiscus.server) | `a8a1cfbd` |
| [willuhn/hibiscus](https://github.com/willuhn/hibiscus) | `e8866490` |
| [willuhn/hibiscus.xmlrpc](https://github.com/willuhn/hibiscus.xmlrpc) | `556dfef1` |
| [willuhn/jameica.webadmin](https://github.com/willuhn/jameica.webadmin) | `f9722e17` |
| [willuhn/jameica](https://github.com/willuhn/jameica) | `3852ca2f` |
| [willuhn/datasource](https://github.com/willuhn/datasource) | `003e30ee` |

Die Belege zum Fenster der JSON-Beans, zur Transaktions-ID der Bank und zum Abgleich beim Abruf ([Stabile IDs](#stabile-ids)) sind zusätzlich in genau den Versionen gelesen, die das Image der Integrationstests enthält:

| Repository | Version | Commit |
|---|---|---|
| [willuhn/hibiscus](https://github.com/willuhn/hibiscus) | 2.12.4 | `2f610866` |
| [willuhn/hibiscus.server](https://github.com/willuhn/hibiscus.server) | 2.12.4 | `7ca698ef` |
| [willuhn/jameica](https://github.com/willuhn/jameica) | 2.12.0 | `d4e8b0ba` |
| [hbci4j/hbci4java](https://github.com/hbci4j/hbci4java) | hbci4j-core 4.1.10 | `67e401f1` |

Dazu die Seiten [develop:xmlrpc](https://www.willuhn.de/wiki/doku.php?id=develop:xmlrpc), [develop:xmlrpc:umsatz](https://www.willuhn.de/wiki/doku.php?id=develop:xmlrpc:umsatz) und [develop:xmlrpc:konto](https://www.willuhn.de/wiki/doku.php?id=develop:xmlrpc:konto) des Wikis.

Was Bankbote von den JSON-Beans erwartet, prüfen die Integrationstests gegen einen laufenden Hibiscus Server 2.12.4 aus dem Image von [encircle360-oss/hibiscus-server-image](https://github.com/encircle360-oss/hibiscus-server-image) mit MariaDB 11.8, siehe [Gegen einen laufenden Server geprüft](#gegen-einen-laufenden-server-geprüft). Nicht geprüft ist alles, was eine Bank braucht: FinTS-Dialog, TAN, echte Abrufe, die Benachrichtigung nach einer echten Synchronisierung und echte Inhalte der Felder.

## Anmeldung und TLS

- Jameica Web-Admin lauscht auf Port 8080 (`listener.http.port`, `jameica.webadmin/src/de/willuhn/jameica/webadmin/Settings.java`), mit HTTPS und Anmeldung als Vorgabe (`listener.http.ssl` und `listener.http.auth`, beide `true`).
- Das Zertifikat erzeugt Jameica selbst, selbst signiert mit `CN=<Rechnername>` (`jameica/src/de/willuhn/jameica/security/SSLFactory.java`). Bankbote vertraut deshalb genau diesem Zertifikat und prüft den Namen darin nicht, siehe [Sicherheit](sicherheit.md#tls-zum-hibiscus-server).
- Die Anmeldung ist HTTP Basic. `JameicaLoginService` (`jameica.webadmin/src/de/willuhn/jameica/webadmin/server/JameicaLoginService.java`) kennt eine einzige Identität `admin`, vergleicht den Benutzernamen nicht („Den Usernamen vergleichen wir nicht.“) und prüft nur, ob das Passwort das Master-Passwort von Jameica ist. Es gibt keine Dienstkonten und keine API-Schlüssel.

## Welche Schnittstelle Bankbote liest

Hibiscus Server hat zwei Schnittstellen für Umsätze: den XML-RPC-Dienst `hibiscus.xmlrpc.umsatz` und die JSON-Beans von Web-Admin unter `/webadmin/rest/hibiscus/…`. **Bankbote liest die JSON-Beans**, weil nur sie zeigen, ob ein Umsatz vorgemerkt ist.

| | XML-RPC `umsatz.list` | JSON-Bean `konto/<id>/umsaetze/days/<n>` |
|---|---|---|
| Kennzeichen „vorgemerkt“ (`flags`) | **fehlt**, weder im Ergebnis noch als Filter | **enthalten** |
| Felder | ausgewählte, `zweck` nur als SVWZ | jede Spalte der Tabelle `umsatz` |
| Betrag | `HBCI.DECIMALFORMAT`, abhängig von der Sprache des Servers | `Double.toString`, ohne Sprache |
| Filter | Konto, Zeitraum, ID-Bereich | Konto, letzte `n` Tage |
| Grenze | `xmlrpc.result.limit`, vorgegeben 10000, neueste zuerst | keine |
| Grenzen der Pfade | – | Konto-ID und Tage bis vier Ziffern |

Belege:

- `hibiscus.xmlrpc/src/de/willuhn/jameica/hbci/xmlrpc/server/UmsatzServiceImpl.java`: `list(HashMap)` baut das Ergebnis aus `id`, `konto_id`, `empfaenger_name`, `empfaenger_konto`, `empfaenger_blz`, `art`, `betrag`, `valuta`, `datum`, `zweck`, `saldo`, `primanota`, `customer_ref`, `kommentar`, `gvcode`, `endtoendid`, `zweck_raw` und `umsatz_typ`; `flags` ist nicht dabei, und ein Filter `flags` endet als „unknown parameter“.
- `hibiscus.server/src/de/willuhn/jameica/hbci/payment/web/rest/UmsatzBean.java`: `@Path("/hibiscus/konto/([0-9]{1,4})/umsaetze/days/([0-9]{1,4})$")` gibt `JsonUtil.toJson(k.getUmsaetze(days))`.
- `hibiscus.server/src/de/willuhn/jameica/hbci/payment/util/JsonUtil.java`: schreibt für jedes Attribut des Objekts (`BeanUtil.getProperties`, bei einem `GenericObject` die Spalten der Tabelle) den Wert mit `BeanUtil.toString`, also `toString()`, und dazu `id`. Ein verknüpftes Objekt wie das Konto wird seine ID.
- `datasource`, `TypeRegistry` und `TypeGeneric`: Spalten vom Typ `date` kommen als `rs.getObject(…)`, ein `java.sql.Date`, dessen `toString()` `yyyy-MM-dd` ist; `double` als `Double`. `org.json` lässt Schlüssel mit `null` weg, eine leere Spalte `flags` fehlt also im JSON.
- `hibiscus/src/de/willuhn/jameica/hbci/server/KontoImpl.java`, `getUmsaetze(int days)` (2.12.4, Zeilen 486–499): `datum >= startOfDay(System.currentTimeMillis() − days × 24 h)`, neueste zuerst, ohne Grenze. Geschnitten wird nach der Spalte `datum`, dem Buchungstag, und an einer ganzen Tagesgrenze: `DateUtil.startOfDay` (`jameica/src/de/willuhn/jameica/util/DateUtil.java`, Zeilen 346–358) setzt die Uhrzeit mit `Calendar.getInstance()` auf 0:00, also in der Zeitzone der JVM des Servers. Weil `n × 24 Stunden` abgezogen werden und nicht `n` Kalendertage, liegt der Schnitt nach der Umstellung von Sommer- auf Winterzeit in der letzten Stunde eines Tages einen Tag später. Der Tag des Servers kann je nach Zeitzone bis zu zwei Tage vor dem von Bankbote liegen.
- `hibiscus.server/src/de/willuhn/jameica/hbci/payment/web/rest/KontoBean.java`: `@Path("/hibiscus/konto/list$")` gibt alle Konten mit `id`, `iban`, `waehrung` und den übrigen Spalten.
- `jameica.webadmin/src/de/willuhn/jameica/webadmin/server/RestServiceImpl.java`: setzt `application/json` nur, wenn `json.indent` größer als 0 ist (Vorgabe 2); ein Fehler wird HTTP 500.

Bankbote liest deshalb

- die Konten mit `GET /webadmin/rest/hibiscus/konto/list`,
- die Umsätze eines Kontos mit `GET /webadmin/rest/hibiscus/konto/<id>/umsaetze/days/<n>`, wobei `n` drei Tage mehr umfasst als das Fenster und Bankbote die Umsätze vor dem ersten Tag selbst verwirft. Zwei Tage decken einen Server ab, dessen Tag dem von Bankbote voraus ist, einer die Zeitumstellung. So ist auch der erste Tag des Fensters immer vollständig, was die laufenden Nummern der `transaction_id` brauchen ([Architektur](architektur.md#laufende-nummer)); `HibiscusSourceTest` prüft das für jede Stunde zweier Wochen über die Zeitumstellung hinweg und für Zeitzonen von −11 bis +14 Stunden.

Das Lesen ist ein eigener kleiner Client über `java.net.http` mit dem Baummodell von Jackson ohne Reflexion, passend zum nativen Image. Einen XML-RPC-Client braucht Bankbote nicht.

### Werte

| Spalte | Bankbote liest | Regel |
|---|---|---|
| `id` | Kennung des Umsatzes | fehlt sie, wird der Umsatz nicht geliefert |
| `konto_id` | muss das gelesene Konto sein | sonst nicht geliefert |
| `datum` | Buchungstag | `yyyy-MM-dd`, auch mit Uhrzeit, oder `dd.MM.yyyy`; anderes wird nicht geliefert |
| `valuta` | Valuta | wie `datum`; fehlt sie, gilt für die `transaction_id` der Buchungstag. Das Schema verlangt sie (`valuta DATE NOT NULL` in `mysql-create.sql` und `h2-create.sql`); eine Valuta, die sich nicht lesen lässt, wird nicht geliefert |
| `txid` | Transaktions-ID der Bank | ohne Leerzeichen am Rand; siehe [Stabile IDs](#stabile-ids) |
| `betrag` | Betrag | Form von `Double.toString`: Ziffern, Punkt, optional Exponent; nie mit Tausendertrennern oder Komma. Genau auf die Nachkommastellen der Währung des Kontos, sonst nicht geliefert |
| `flags` | vorgemerkt | siehe unten |
| `zweck`, `zweck2`, `zweck3` | Verwendungszweck | nicht leere Zeilen, je eine Zeile |
| `empfaenger_name`, `empfaenger_name2` | Name der Gegenseite | mit Leerzeichen verbunden |
| `empfaenger_konto` | Konto der Gegenseite | eine gültige IBAN wird `bank_party_iban`, alles andere `bank_party_account_number` |
| `art` | Art des Umsatzes | wie die Bank sie nennt |
| `endtoendid`, `customerref` | Referenz | siehe [Architektur](architektur.md#felder) |
| `waehrung` des Kontos | Währung | ISO 4217 mit festen Nachkommastellen, sonst wird das Konto nicht geliefert |

Ein Umsatz, der sich nicht sicher lesen lässt, wird nie geliefert und in der Zusammenfassung als fehlgeschlagen gezählt; der Grund steht im Log, ohne Inhalt des Umsatzes.

## Vorgemerkte Umsätze

- `hibiscus/src/de/willuhn/jameica/hbci/rmi/Umsatz.java`: `FLAG_NOTBOOKED = 1 << 1`, gespeichert in der Spalte `flags`.
- `hibiscus/src/de/willuhn/jameica/hbci/server/hbci/HBCIUmsatzJob.java`: Beim Abruf bekommen vorgemerkte Umsätze `umsatz.setFlags(Umsatz.FLAG_NOTBOOKED)` und `setSaldo(0d)`. Sie sind eigene Zeilen: Meldet die Bank einen vorgemerkten Umsatz nicht mehr, wird er gelöscht; der gebuchte Umsatz kommt als neue Zeile mit neuer, höherer ID. Die Prüfsumme eines vorgemerkten Umsatzes bekommt `notbooked` angehängt (`UmsatzImpl.java`), damit sie nicht mit der des gebuchten zusammenfällt.
- `hibiscus.server/cfg/de.willuhn.jameica.hbci.HBCI.properties`: Der Payment-Server ruft vorgemerkte Umsätze als Vorgabe gar nicht ab (`umsatz.fetchnotbooked=false`, „Vorgemerkte Umsaetze per Default nicht abrufen“).

**Regel in Bankbote:** Ein Umsatz ist vorgemerkt, wenn `flags` eine Zahl mit gesetztem Bit 2 ist. Fehlt `flags` oder ist leer, ist er gebucht. Steht etwas anderes als eine Zahl darin, wird der Umsatz nicht geliefert. Ein vorgemerkter Umsatz wird nie geliefert, trägt in Bankbote nur seine ID und bekommt keine `transaction_id`; der gebuchte Umsatz kommt später als eigene Zeile und bekommt erst dann seine. Damit entsteht auch dann keine Dublette, wenn der Server vorgemerkte Umsätze abruft.

Über XML-RPC ließe sich das nicht unterscheiden: Dort fehlt `flags`, und der Saldo 0 eines vorgemerkten Umsatzes kommt auch bei gebuchten vor.

## Stabile IDs

`id` der Tabelle `umsatz` ist ein fortlaufender Ganzzahlschlüssel (`IDENTITY` in H2, `AUTO_INCREMENT` in MySQL). Er gehört zur Datenbank, nicht zum Umsatz: Eine neu aufgebaute Datenbank oder ein gelöschtes und neu abgerufenes Konto gibt denselben Umsätzen neue IDs, und eine neue Datenbank zählt wieder ab 1. Die `transaction_id` von Bankbote beruht deshalb nicht auf ihr, sondern auf dem Umsatz selbst, siehe [Architektur](architektur.md#die-kennung-transaction_id). Die ID dient Bankbote nur noch für die Reihenfolge, in der der Server die Umsätze bekam.

**Die Transaktions-ID der Bank (`txid`):**

- `hbci4java`, `GV_Result/GVRKUms.java`, Zeilen 125–129: `UmsLine.id` ist „NUR BEI CAMT“ gefüllt, „Bei MT940-Abruf ist der Wert leer“.
- `hbci4java`, `GV/parsers/ParseCamt05200102.java`, Zeilen 202–256, ebenso in den Parsern der anderen Versionen von camt.052: `line.id` ist `TxDtls/Refs/Prtry/Ref`, beschnitten; ist es `null`, die `AcctSvcrRef` des Eintrags, sonst die der Transaktionsdetails, beide unbeschnitten. Gesetzt wird `line.id` nur, wenn die Transaktionsdetails `Refs` haben. Gelesen werden nur die ersten `NtryDtls` und davon die ersten `TxDtls` eines Eintrags; ein Eintrag mit `NtryDtls`, aber ohne `TxDtls` wird übergangen. `line.customerref` ist die beschnittene `AcctSvcrRef` des Eintrags, kann also dieselbe Zeichenkette sein.
- `hibiscus`, `server/Converter.java`, Zeile 63: `umsatz.setTransactionId(u.id)`, ohne `clean` und ohne Prüfung; `UmsatzImpl.java`, Zeilen 805–820, schreibt sie in die Spalte `txid` (`varchar(100)`).
- **Platzhalter:** Weder HBCI4Java noch Hibiscus setzen Platzhalter wie `NONREF` oder `NOTPROVIDED` in `txid` auf leer. Beide Zeichenketten kommen in hbci4java nur beim Erzeugen eigener SEPA-Aufträge vor (`AbstractSEPAGV.ENDTOEND_ID_NOTPROVIDED`), in Hibiscus nur beim Export als MT940 (`MT940UmsatzExporter`). Ein leerer `Prtry/Ref` bleibt eine leere Zeichenkette und führt nicht zur `AcctSvcrRef`. Bankbote behandelt deshalb selbst eine leere ID und die Platzhalter `NONREF`, `NOTPROVIDED`, `NOTAVAILABLE` und `NOREF` als fehlend, siehe [Architektur](architektur.md#kanonische-form).

**Der Abgleich beim Abruf:**

- `hibiscus`, `server/UmsatzImpl.java`, `equals`, Zeilen 302–330: gleich ist ein Umsatz mit derselben ID, sonst mit derselben `txid`, wenn beide eine haben, sonst mit derselben Prüfsumme. Der Vergleich der `txid` ist genau, mit Groß- und Kleinschreibung und Leerzeichen.
- `getChecksum`, Zeilen 394–437: eine Prüfsumme über Art, **ID des Kontos**, Betrag, Kundenreferenz, Gegenkonto, Name, Primanota, Zweck, Buchungstag und Valuta. Die ID des Kontos gehört zur Datenbank; als Kennung über Datenbanken hinweg taugt die Prüfsumme nicht.
- `server/hbci/HBCIUmsatzJob.java`, Zeilen 191–268: Für jeden abgerufenen gebuchten Umsatz zählt Hibiscus, wie viele gleiche es schon hat, und speichert ihn nur, wenn die Bank in diesem Abruf mehr gleiche geschickt hat, als die Datenbank hält. Gebuchte Umsätze werden beim Abruf nur eingefügt, nie geändert. Zwei gleiche Umsätze eines Tages sind deshalb zwei Zeilen; die laufende Nummer der `transaction_id` zählt sie ebenso.

Ein Sonderfall aus dem Quelltext: Gibt die Bank einem vorgemerkten und dem gebuchten Umsatz dieselbe Transaktions-ID, kann Hibiscus den gebuchten in diesem Abruf als schon vorhanden überspringen und erst im nächsten speichern, nachdem der vorgemerkte gelöscht ist. Für Bankbote heißt das nur, dass er einen Lauf später kommt.

## Die Benachrichtigung `notify.url`

- `hibiscus.server/src/de/willuhn/jameica/hbci/payment/Settings.java`: `notify.url`, laut Javadoc aufgerufen, „wenn die Synchronisierung lief“; einstellbar auch in Web-Admin (`web/beans/Settings.java`).
- `hibiscus.server/src/de/willuhn/jameica/hbci/payment/messaging/NotifyMessageConsumer.java`: hört auf `SaldoMessage` für ein `Konto`. Außerhalb der Oberfläche schickt sie nur `HBCISaldoJob` nach einem erfolgreichen Saldenabruf (`hibiscus/src/de/willuhn/jameica/hbci/server/hbci/HBCISaldoJob.java`).

Was gesendet wird:

- **ein POST je Konto**, nach dessen Saldenabruf, nicht nach dem Umsatzabruf und gar nicht, wenn für das Konto „Saldo synchronisieren“ aus ist;
- `Content-Type: application/x-www-form-urlencoded`, Rumpf `context=` und das Konto als JSON mit allen Spalten, nicht URL-kodiert;
- ohne Anmeldung, ohne Wiederholung, ohne Zeitlimit; Weiterleitungen werden befolgt, eine andere Antwort als 200 nur protokolliert;
- bei `https` wird jedes Zertifikat angenommen (`TrustMessageConsumer`).

Die Nachricht geht asynchron weiter (`MessagingQueue`), und der Saldenabruf läuft vor dem Umsatzabruf. Die Benachrichtigung kann also eintreffen, bevor die Umsätze derselben Synchronisierung gespeichert sind.

**Folgen für Bankbote:** Die Benachrichtigung ist nur ein Hinweis. Bankbote liest ihren Rumpf nicht, wartet `bankbote.notify-delay` (vorgegeben 60 s) und startet dann einen Lauf über alle Konten; alle Benachrichtigungen in dieser Zeit ergeben denselben Lauf. Der Zeitplan fängt Synchronisierungen ohne Saldenabruf auf. Weil der Hibiscus Server keine Anmeldung mitschicken kann und jedem Zertifikat traut, ist die Benachrichtigung über `http://127.0.0.1:8089/notify` im gemeinsamen Netzwerk-Namespace vorgesehen, siehe [Sicherheit](sicherheit.md#die-endpunkte).

## Gegen einen laufenden Server geprüft

Die Suite `hibiscusTest` (siehe [Entwicklung](entwicklung.md#integrationstests)) startet Hibiscus Server 2.12.4 aus dem Image von encircle360-oss/hibiscus-server-image mit MariaDB 11.8 in `utf8mb3`, so wie ein Betreiber ihn betreibt: Root-Dateisystem schreibgeschützt, ohne Capabilities, Master-Passwort und Passwort der Datenbank als Dateien, beide bei jedem Lauf neu erzeugt. Eine Bank gibt es nicht; Konten und Umsätze schreibt der Test über JDBC in die Tabellen `konto` und `umsatz` des Schemas des Herstellers (`plugins/hibiscus/sql/mysql-create.sql`), mit erfundenen Werten.

| Annahme | Ergebnis |
|---|---|
| Die JSON-Beans antworten, sobald der Server läuft | **widerlegt:** Ein Start durchläuft vier Phasen, gemessen mit einer Schleife, die vor dem Container startet: Verbindung abgelehnt, solange der Port nicht veröffentlicht ist; Verbindung angenommen und beim TLS-Handshake geschlossen, solange der veröffentlichte Port niemanden hinter sich hat; HTTP 500 mit `no command found for REST url` für etwa eine halbe Sekunde, auch noch nach der Meldung `all plugins initialised` des Einstiegspunkts, weil die Beans erst nach dem Webserver registriert werden (`RestMessageConsumer`, „searching auto rest beans“); dann 200. Auf dem Runner der CI dauern die Phasen länger. Bankbote behandelt alle drei gleich, als Quelle, die noch nicht lesbar ist, und wiederholt einen solchen Lauf einmal nach `notify-delay` (`StartBesideHibiscusTest`); die Tests warten mit `Readiness` über alle drei Phasen, bis `konto/list` mit 200 antwortet (`ReadinessTest`) |
| Zeilen, die neben dem Server in die Datenbank geschrieben werden, sieht er | **bestätigt:** sofort, ohne Neustart; Prüfsumme (`checksum`) und andere abgeleitete Spalten dürfen leer bleiben |
| Anmeldung mit beliebigem Benutzernamen und dem Master-Passwort | **bestätigt**, mit einer Einschränkung: ein **leerer** Benutzername ergibt 401 wie ein falsches oder fehlendes Passwort. Bankbote startet deshalb nicht mehr mit leerem `bankbote.hibiscus.username` |
| `konto/list` mit `id`, `iban`, `waehrung` | **bestätigt;** ein Konto ohne IBAN hat keinen Schlüssel `iban` |
| `datum` als `yyyy-MM-dd` | **bestätigt** |
| `betrag` wie `Double.toString` | **bestätigt:** `-1234.56`, `7.1`, `-0.5`, `0.0`, ab zehn Millionen in Exponentenschreibweise, etwa `1.0E7`, `1.23456789E7`, `9.999999999E7`; Bankbote liest alle exakt |
| `flags` mit `FLAG_NOTBOOKED` | **bestätigt:** `2` und `3` vorgemerkt, `0` und `1` gebucht, eine leere Spalte fehlt im JSON. Einen leeren Text kann die Spalte nicht halten, sie ist eine Zahl |
| Umlaute, Eurozeichen und Zeichen außerhalb von Latin-1 in Zweck und Name | **bestätigt:** `ä ö ü ß €`, `Ł`, `Ω` und typografische Anführungszeichen kommen unverändert an, auch mit den JDBC-Parametern des Herstellers (`characterEncoding=ISO8859_1`) |
| Inhaltstyp und Zeichensatz | `application/json` **ohne** `charset`, der Inhalt ist UTF-8; Bankbote liest `application/json` als UTF-8 |
| Fenster `days/<n>` | **bestätigt:** `n` Tage zurück ab dem Tag des Servers (Zeitzone Europe/Berlin), Buchungen mit Datum in der Zukunft inklusive; Bankbote verwirft die zusätzlichen Tage selbst |
| `valuta` und `txid` | **bestätigt:** `valuta` als `yyyy-MM-dd`, auch wenn sie vom Buchungstag abweicht; `txid` unverändert mit Leerzeichen am Rand, eine leere Spalte fehlt im JSON |
| Reihenfolge und Grenze | neueste zuerst nach `datum`, dann `id` absteigend; **keine Grenze**, 10 051 Umsätze kamen vollständig |
| Konto, das es nicht gibt | HTTP 500, Bankbote meldet es als Fehler des Kontos |
| Zertifikat | CN aus `HIBISCUS_CERT_HOSTNAME`, `O=Jameica Certificate`, ohne subjectAlternativeName; der Server legt genau das Zertifikat des Eintrags `jameica` in `/data/cfg/jameica.keystore` vor. Web-Admin zeigt seinen Fingerabdruck **nicht**, siehe [Betrieb](betrieb.md#das-zertifikat-des-hibiscus-servers) |
| Passwörter im Log des Servers | keines |

Ein weiterer Test startet den ganzen Dienst Bankbote gegen diesen Server und zwei nachgebaute ERPNext: Jedes Konto geht an sein Ziel, vorgemerkte Umsätze und Konten ohne Zuordnung gehen nirgendwohin, zwei gleiche Umsätze eines Tages kommen als `…-1` und `…-2` an, eine Benachrichtigung über `/notify` löst einen Lauf aus, der nichts doppelt liefert, nach Löschen und neuem Einfügen aller Umsätze des Kontos mit neuen IDs in anderer Reihenfolge liefert ein weiterer Lauf ebenfalls nichts, und das Log enthält weder Passwort noch IBAN, Zweck oder Namen.
