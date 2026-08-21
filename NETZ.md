# Netzdoktor – Netzwerkanalyse und Reparatur

Ein Diagnosewerkzeug, das die Netzwerkverbindung Ebene für Ebene durchgeht –
vom Gerät über den Router bis ins Internet – und die Störung dort benennt, wo
sie tatsächlich beginnt. Statt „kein Internet“ liefert es Sätze wie „das
Internet ist per IP erreichbar, nur die Namensauflösung fällt aus“.

Alles läuft mit Node-Bordmitteln, ohne zusätzliche Pakete und ohne Root-Rechte
– damit es auch unter Termux funktioniert.

## Bedienung

```bash
npm run netz                    # vollständige Analyse
npm run netz:json               # dasselbe als JSON, für Skripte
npm run netz:reparieren         # Reparaturen simulieren (verändert nichts)
npm run netz:beobachten         # Analyse alle 60 Sekunden wiederholen
```

Direkt über die CLI gibt es mehr Schalter:

```bash
node netz/cli.js hilfe
node netz/cli.js analyse --ausfuehrlich          # zusätzlich die Rohdaten
node netz/cli.js analyse --nur dns-system,gateway
node netz/cli.js reparieren --anwenden --ja      # riskante Maßnahmen zulassen
node netz/cli.js beobachten --intervall 30
node netz/cli.js konfig                          # gespeicherte Einstellungen
node netz/cli.js konfig-zuruecksetzen
```

Rückgabewerte für Skripte und Cronjobs: `0` alles in Ordnung, `1` Warnungen,
`2` Fehler.

## Dashboard

Der Router hängt in `app.js` und liefert die Oberfläche unter
`http://localhost:3000/netz.html`:

```bash
npm run start:app
```

| Endpunkt | Methode | Zweck |
| --- | --- | --- |
| `/api/netz/analyse` | GET | Vollständiger Bericht (`?frisch=1` erzwingt eine neue Messung) |
| `/api/netz/reparaturen` | GET | Verfügbare Maßnahmen |
| `/api/netz/reparieren` | POST | `{ ids, anwenden, bestaetigt }` |
| `/api/netz/konfig` | GET / DELETE | Gespeicherte Einstellungen lesen / löschen |

Die verändernden Endpunkte sind auf die Loopback-Adresse beschränkt – sonst
könnte jeder im selben WLAN Prozesse auf dem Gerät beenden. Wer das bewusst
öffnen will, setzt `NETZ_REPARATUR_ENTFERNT=1`.

## Die Prüfungen

| Ebene | Prüfung | Findet heraus |
| --- | --- | --- |
| Gerät | `schnittstellen` | Gibt es überhaupt eine Adresse? Erkennt auch `169.254.x.x`, also eine ausgebliebene DHCP-Antwort |
| Gerät | `loopback` | Antwortet der lokale Netzwerk-Stack? |
| Gerät | `anwendungs-port` | Ist der Port der Anwendung frei, belegt oder bedient? |
| Gerät | `proxy` | Ist ein eingetragener Proxy erreichbar? |
| Heimnetz | `gateway` | Antwortet der Router? |
| Internet | `internet-tcp` | Erreichbarkeit fester IP-Adressen, ganz ohne DNS |
| Internet | `ipv6` | IPv6 konfiguriert, aber unbrauchbar? Zählt nur global routbare Adressen (`2000::/3`) |
| Internet | `https` | TLS-Fehler, aufbrechender Proxy, falsche Systemzeit |
| Internet | `captive-portal` | Hängt eine Anmeldeseite dazwischen? |
| Namensauflösung | `dns-system` | Löst der System-Auflöser auf? |
| Namensauflösung | `dns-oeffentlich` | Kommt man an öffentliche Auflöser heran? |
| Qualität | `latenz` | Laufzeit, Jitter und Paketverlust über mehrere Messungen |

Statt ICMP („ping“) werden durchgehend TCP-Handshakes gemessen. ICMP ist unter
Android nicht ohne Weiteres nutzbar und wird in vielen Netzen ohnehin
verworfen.

Die Reihenfolge ist der Weg des Datenpakets. Was zuerst kaputt ist, erklärt in
aller Regel alles Nachfolgende – deshalb nennt die Diagnose immer die unterste
gestörte Ebene und nicht die auffälligste Meldung.

## Die Reparaturen

Ohne `--anwenden` passiert grundsätzlich nichts: Jeder Lauf ist zunächst eine
Simulation und zeigt nur, was geschehen würde. Maßnahmen oberhalb von Risiko
„niedrig“ verlangen zusätzlich `--ja`.

| ID | Risiko | Wirkung |
| --- | --- | --- |
| `dns-fallback` | niedrig | Sucht einen erreichbaren öffentlichen DNS-Server und leitet die Namensauflösung dorthin um |
| `ipv4-bevorzugen` | niedrig | Setzt die Adressreihenfolge auf `ipv4first`, wenn IPv6 konfiguriert, aber tot ist |
| `proxy-bereinigen` | mittel | Entfernt nicht erreichbare Proxy-Variablen aus der Prozessumgebung |
| `port-freigeben` | hoch | Beendet den Prozess, der den Port der Anwendung blockiert (SIGTERM) |

Angewandte Einstellungen landen in `netz-konfig.json` und werden beim Start von
`app.js` und der CLI wieder aktiviert. `node netz/cli.js konfig-zuruecksetzen`
macht alles rückgängig.

### Warum die DNS-Reparatur mehr tut als `dns.setServers()`

Ein naheliegender, aber wirkungsloser Ansatz. `dns.setServers()` wirkt
ausschließlich auf `dns.resolve*()`. Verbindungen über `net`, `http` oder
`fetch` gehen dagegen durch `dns.lookup()`, und das fragt immer den Auflöser
des Betriebssystems – ein Fallback, der nur `setServers` aufruft, würde also
in der Diagnose funktionieren und im Alltag nichts ändern.

`netz/aufloeser.js` tauscht deshalb `dns.lookup` und `dns.promises.lookup`
gegen eigene Fassungen aus, die über die konfigurierten Server auflösen und nur
im Notfall auf das System zurückfallen. IP-Literale und `localhost` gehen
unverändert durch, damit `/etc/hosts` weiter gilt. Das
`util.promisify.custom`-Symbol wird mitgeliefert, sonst bekämen Aufrufer
plötzlich eine Zeichenkette statt `{ address, family }`.

Die Prüfung `dns-system` misst weiterhin ausdrücklich den echten System-
Auflöser – sonst würde die Reparatur ihren eigenen Erfolg messen.

## Aufbau

```
netz/
  cli.js          Kommandozeile
  routen.js       Express-Router für das Dashboard
  analyse.js      Ablaufsteuerung, Diagnose, Gesamtstatus
  pruefungen.js   die einzelnen Diagnoseschritte
  reparaturen.js  die Maßnahmen samt Risikoeinstufung
  aufloeser.js    Ersatz für dns.lookup
  werkzeuge.js    TCP, DNS, HTTP, Routingtabelle, Ports
  bericht.js      Ausgabe fürs Terminal
  konfig.js       netz-konfig.json lesen, schreiben, anwenden
public/netz.html  Dashboard
test/             Tests (npm test)
```

Eine neue Prüfung ist ein Objekt in `netz/pruefungen.js` mit `id`, `titel`,
`gruppe` und `ausfuehren(ktx)`; eine neue Maßnahme ein Objekt in
`netz/reparaturen.js` mit `pruefen(ktx)` und `anwenden(ktx, optionen)`. Beides
wird automatisch aufgenommen – ein Test wacht darüber, dass jede von einer
Prüfung genannte Reparatur-ID auch existiert.

### 6to4 wird gesondert benannt

`2002::/16` sieht aus wie eine globale Adresse und ist es formal auch – nur ist
es 6to4, ein Tunnelverfahren, das RFC 7526 für überholt erklärt hat. Die
öffentlichen Relays dafür sind weitgehend abgeschaltet. Ein Router mit
aktiviertem 6to4 verteilt also Adressen, die nirgends ankommen. Der Netzdoktor
nennt in diesem Fall ausdrücklich 6to4 als Ursache und verweist auf die
Router-Einstellung, statt nur „IPv6 nicht erreichbar“ zu melden – sonst sucht
man an der falschen Stelle.

### Warum nur `2000::/3` als IPv6 zählt

Eine `fe80::`-Adresse (Link-Local) hat praktisch jedes Gerät, und `fc00::/7`
(Unique Local) entspricht den privaten IPv4-Netzen. Beide kommen nie ins
Internet. Wer sie als „IPv6 ist eingerichtet" wertet, meldet auf fast jedem
Handy eine Störung, die keine ist – und schlägt eine Reparatur vor, die nichts
zu reparieren hat. Als IPv6-Anbindung zählt deshalb nur der global routbare
Bereich `2000::/3`; alles andere führt zu „übersprungen“ mit einem Hinweis,
dass das normal ist.

## Grenzen

Ohne Root-Rechte lässt sich unter Termux die Systemkonfiguration nicht ändern.
Die Reparaturen wirken deshalb auf Prozessebene: für diese Anwendung, nicht für
das gesamte Gerät. Für WLAN-Wechsel, APN- oder Systemeinstellungen gibt das
Programm eine Anweisung aus, statt Änderungen vorzutäuschen.

Das Standard-Gateway kann Node nicht selbst ermitteln. Der Netzdoktor probiert
`ip`, `route` und `netstat` durch; fehlen alle drei, schätzt er bei einer
/24-Schnittstelle auf `.1` und kennzeichnet das im Bericht als Schätzung.
