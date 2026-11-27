# Sicherheit

## Das Problem

Ein Hibiscus Server hält die Bankzugänge mehrerer Konten, oft mehrerer Unternehmen. Er kennt genau eine Identität, `admin`, und genau einen Zugang, das **Master-Passwort** von Jameica (`JameicaLoginService` in jameica.webadmin, siehe [Hibiscus Server](hibiscus.md#anmeldung-und-tls)). Dasselbe Passwort schützt die gespeicherten PINs. Dienstkonten mit eingeschränkten Rechten oder API-Schlüssel gibt es nicht.

Holte jedes ERPNext seine Umsätze selbst beim Hibiscus Server, läge das Master-Passwort in jedem ERPNext, und jedes ERPNext könnte die Konten aller Unternehmen lesen und über den Hibiscus Server Aufträge an die Bank geben.

## Das Modell

Bankbote dreht die Richtung um:

- **Das Master-Passwort kennen nur der Hibiscus Server und Bankbote.** Bankbote läuft neben dem Hibiscus Server, liest dort und schiebt in die ERPNext-Installationen. Kein ERPNext hält einen Zugang zum Hibiscus Server.
- **Jedes ERPNext bekommt nur seine Konten.** Jedes Konto ist genau einem Ziel zugeordnet; die Zuordnung ist fest in der Konfiguration, wird beim Start geprüft und schlägt geschlossen fehl: Ein Konto ohne Zuordnung geht nirgendwohin, ein Konto mit zwei Zuordnungen oder einem unbekannten Ziel verhindert den Start.
- **Jedes ERPNext hat seinen eigenen, engen Schlüssel.** Der Dienstbenutzer darf Bankumsätze lesen, anlegen und einreichen und Bankkonten lesen, sonst nichts ([Betrieb](betrieb.md#der-dienstbenutzer-in-erpnext)). Bankbote liest nur Bankkonten des Unternehmens und Bankumsätze mit eigenen Kennungen.
- **Der Schlüssel eines ERPNext geht nur an dessen Adresse.** Jedes Ziel hat einen eigenen Client, der jede Anfrage aus seiner konfigurierten Adresse und einem festen Pfad bildet und Weiterleitungen nie folgt. Eine Weiterleitung, auch eine auf eine andere Site, endet als Fehler dieses Ziels. Getestet mit zwei und drei Zielen, mit Weiterleitungen auf fremde Server und mit Fehlern.
- **Kein Ziel kann ein anderes aufhalten.** Jedes Ziel läuft in einem eigenen Thread mit eigenen Zeitlimits.

## TLS zum Hibiscus Server

Bankbote vertraut beim Hibiscus Server genau dem konfigurierten Zertifikat: Das Zertifikat, das der Server vorlegt, muss Byte für Byte dieses sein. Keiner Zertifizierungsstelle wird vertraut, auch nicht denen des Systems. Weil Jameica sein Zertifikat selbst erzeugt, meist mit einem Namen, unter dem der Server im Pod nicht erreicht wird, prüft Bankbote den Namen nicht; der exakte Vergleich ersetzt diese Prüfung.

Das Master-Passwort geht als HTTP Basic im verschlüsselten Kanal, nur an die konfigurierte Adresse, nie über eine Weiterleitung.

## TLS zu ERPNext

Bankbote prüft das Zertifikat jeder Site mit den Zertifizierungsstellen des Systems oder, wenn `certificate-authorities` gesetzt ist, nur mit diesen, jeweils mit Prüfung des Namens. `allow-plain-http` schaltet TLS für ein Ziel ab; das ist nur für eine Site im selben vertrauenswürdigen Netz gedacht und schickt Schlüssel und Umsätze unverschlüsselt.

## Geheimnisse

- Master-Passwort, API-Schlüssel und -Geheimnisse kommen aus Dateien, nie aus der Konfiguration selbst, aus Umgebungsvariablen oder von der Kommandozeile.
- Ein Geheimnis zeigt in `toString()` nur `Secret[****]`; Fehlermeldungen nennen den Pfad der Datei, nie ihren Inhalt.
- Der Actuator gibt nur `health` frei; `env` und `configprops` sind nicht erreichbar.

## Persönliche Daten

Verwendungszwecke, Namen der Gegenseite und Beträge sind persönliche Daten. Bankbote hält sie nur während eines Laufs im Speicher, um sie an das eine ERPNext zu geben, dem das Konto gehört, und schreibt sie nirgends hin:

- Log und Health-Ausgabe enthalten Zähler, Namen der Ziele, IDs von Umsätzen im Hibiscus Server und IBANs nur maskiert (Land, Prüfziffern, letzte vier Zeichen).
- Fehlermeldungen von ERPNext übernimmt Bankbote nur als HTTP-Status und Klassennamen von Frappe (`exc_type`), nie den Text der Antwort.
- Die Records, die Umsätze tragen, zeigen in `toString()` nur ihre ID.

Ein Test des ganzen Dienstes prüft, dass Log und Health-Ausgabe weder Geheimnisse noch volle IBANs, Zwecke oder Namen enthalten; der Rauchtest der CI prüft das Log des nativen Images.

## Die Endpunkte

`POST /notify` (für den Hibiscus Server) und `POST /run` lösen einen Lauf aus. Der Hibiscus Server kann bei seiner Benachrichtigung keine Anmeldung mitschicken. Die Endpunkte sind deshalb so gebaut, dass eine Anmeldung nichts schützen müsste:

- **Annahme:** Bankbote läuft im Netzwerk-Namespace des Hibiscus Servers, und in diesem Namespace laufen nur Prozesse, denen man das Master-Passwort ohnehin anvertraut.
- Bankbote lauscht als Vorgabe nur auf `127.0.0.1`. Auch wenn der Server breiter lauscht, etwa für Proben, antworten `/notify` und `/run` nur Aufrufen von einer Loopback-Adresse und sonst mit 403.
- Die Endpunkte nehmen keine Eingaben an. Den Rumpf der Benachrichtigung, das Konto als JSON, liest Bankbote nicht; ein Aufruf kann weder ein Konto, ein Ziel noch einen Zeitraum wählen.
- Das Schlimmste, was ein Aufruf bewirken kann, ist ein Lauf, der nichts doppelt liefert. Läufe kommen nie gleichzeitig, und alle Aufrufe während eines Laufs oder einer Wartezeit ergeben zusammen höchstens einen weiteren.

## Was Bankbote nicht schützt

- **Wer den Namespace oder den Hibiscus Server übernimmt**, hat das Master-Passwort und alle Konten; das gilt mit und ohne Bankbote.
- **Wer die Konfiguration oder die Dateien mit Geheimnissen ändern kann**, kann Konten anderen Zielen zuordnen. Sie gehören nur lesend eingehängt und in die Hand derselben Personen, die den Hibiscus Server betreiben.
- **Ein ERPNext, dem ein Konto zugeordnet ist**, sieht dessen Umsätze vollständig. Die Zuordnung ist die Grenze.
- Bankbote prüft nicht, ob die Bank richtig gebucht hat, und gleicht nichts ab.

Sicherheitslücken bitte nicht öffentlich melden, sondern wie in [SECURITY.md](../SECURITY.md) beschrieben.
