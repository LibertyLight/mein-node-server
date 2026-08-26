# Claude auf WhatsApp

Ein Webhook, der eingehende WhatsApp-Nachrichten an Claude weiterreicht und die
Antwort zurückschickt – mit Gesprächsverlauf, sodass Rückfragen wie „und was
war das erste noch mal?“ funktionieren. Sprachnachrichten werden vorher per
Whisper transkribiert; ab dann sind sie nicht mehr von getippten zu
unterscheiden. Bilder gehen unverändert mit – die sieht Claude selbst.

Der Bot hängt als Router in `app.js`. Als zusätzliche Abhängigkeit kommt nur das
offizielle Anthropic-SDK dazu; Datenbank (`node:sqlite`) und HTTP-Aufrufe
(`fetch`) sind Node-Bordmittel.

## Wie es zusammenhängt

```
WhatsApp-App  →  Meta Cloud API  →  POST /whatsapp/webhook  →  Claude
                                 ←  Graph-API-Aufruf        ←  Antwort

Sprachnachricht:  Medien-ID  →  Datei von Meta holen  →  Whisper  →  Text  ↑
Bild:             Medien-ID  →  Datei von Meta holen  →  als Bildblock  ────↑
```

Meta erwartet auf den Webhook innerhalb weniger Sekunden ein `200`. Claude
braucht länger als das, deshalb quittiert die Route sofort und beantwortet die
Nachricht danach im Hintergrund.

## Was du bei Meta brauchst

Ein WhatsApp-Business-Konto ist Pflicht – die Cloud API ist der einzige offizielle
Weg, Nachrichten zu empfangen. Zum Ausprobieren reicht die kostenlose
Testnummer, die Meta jeder App mitgibt.

1. Auf <https://developers.facebook.com> eine App vom Typ **Business** anlegen
   und das Produkt **WhatsApp** hinzufügen.
2. Unter *WhatsApp → API Setup* stehen die **Telefonnummern-ID** und ein
   temporärer Zugriffstoken (24 Stunden). Für den Dauerbetrieb im
   Business-Manager einen System-Benutzer mit einem permanenten Token anlegen.
3. Dort auch die eigene Handynummer als Testempfänger eintragen.
4. Das **App-Geheimnis** steht unter *App-Einstellungen → Allgemein*.
5. Den **Prüf-Token** für den Webhook denkst du dir selbst aus – eine lange
   Zufallszeichenkette, zum Beispiel `openssl rand -hex 24`.

## Einrichten

Die Zugangsdaten kommen ausschließlich aus Umgebungsvariablen:

```bash
export ANTHROPIC_API_KEY="sk-ant-…"          # console.anthropic.com
export WHATSAPP_TOKEN="EAAG…"                # Zugriffstoken der App
export WHATSAPP_TELEFON_ID="123456789012345" # Telefonnummern-ID, nicht die Nummer
export WHATSAPP_PRUEF_TOKEN="…"              # selbst ausgedacht
export WHATSAPP_APP_GEHEIMNIS="…"            # App-Einstellungen → Allgemein
export WHATSAPP_ERLAUBTE_NUMMERN="491701234567"
```

Freiwillig:

| Variable | Vorgabe | Zweck |
| --- | --- | --- |
| `CLAUDE_MODELL` | `claude-opus-5` | Modell |
| `CLAUDE_AUFWAND` | `medium` | Denktiefe: `low` … `max`, höher heißt langsamer |
| `CLAUDE_MAX_TOKENS` | `8000` | Obergrenze je Antwort |
| `CLAUDE_SYSTEM_PROMPT` | WhatsApp-tauglich, kurz | eigene Rolle für den Bot |
| `WHATSAPP_VERLAUF_NACHRICHTEN` | `20` | wie viele Nachrichten Claude je Anfrage sieht |
| `WHATSAPP_GRAPH_VERSION` | `v23.0` | Version der Graph-API |
| `WHISPER_API_KEY` | – | Schlüssel für die Transkription; ohne ihn bleiben Sprachnachrichten aus |
| `WHISPER_URL` | `https://api.openai.com/v1` | Whisper-Dienst, auch ein lokaler Server |
| `WHISPER_MODELL` | `whisper-1` | Transkriptionsmodell |
| `WHISPER_SPRACHE` | – | z. B. `de`; leer heißt automatisch erkennen |
| `WHATSAPP_MAX_AUDIO_MB` | `20` | Obergrenze je Sprachnachricht |
| `WHATSAPP_MAX_BILD_MB` | `5` | Obergrenze je Bild |
| `WHATSAPP_BILDER_IM_VERLAUF` | `2` | wie viele Bilder bei jeder Anfrage mitgehen |
| `WHATSAPP_BILD_FRAGE` | „Was ist auf diesem Bild zu sehen?“ | Frage bei Bildern ohne Unterschrift |
| `WHATSAPP_TRANSKRIPT_ZEIGEN` | `1` | `0` blendet das Transkript über der Antwort aus |

Anders als der Rest des Repos liegt das Anthropic-SDK nicht mit in
`node_modules` – nach dem Auschecken einmal `npm install` ausführen, sonst
fehlt `@anthropic-ai/sdk` und auch die Tests laufen nicht.

Danach prüfen, ohne aufs Handy zu warten:

```bash
npm install                              # einmalig, zieht @anthropic-ai/sdk nach
npm run whatsapp:pruefen                 # Konfiguration + Claude-Verbindung
npm run whatsapp:pruefen -- 491701234567 # zusätzlich eine Testnachricht schicken
npm run start:app
```

Beim Start meldet der Server, ob der Bot aktiv ist – und andernfalls, welche
Variable fehlt.

### Dauerhaft unter Termux

Ein `export` gilt nur für die laufende Sitzung: nach dem nächsten Start von
Termux sind die Werte weg und der Bot meldet wieder, dass alles fehlt. Damit
sie bleiben, gehören sie in eine Datei, die beim Öffnen der Shell gelesen wird.

Nicht direkt in die `~/.bashrc` – Zugangsdaten haben in der Shell-Konfiguration
nichts verloren und lassen sich dort auch schlechter austauschen. Besser eine
eigene Datei, die nur dir gehört:

```bash
cat > ~/.whatsapp-env <<'EOF'
export ANTHROPIC_API_KEY="hier-einsetzen"
export WHATSAPP_TOKEN="hier-einsetzen"
export WHATSAPP_TELEFON_ID="hier-einsetzen"
export WHATSAPP_PRUEF_TOKEN="hier-einsetzen"
export WHATSAPP_APP_GEHEIMNIS="hier-einsetzen"
export WHATSAPP_ERLAUBTE_NUMMERN="491701234567"
export WHISPER_API_KEY="hier-einsetzen"
EOF

chmod 600 ~/.whatsapp-env     # nur für den eigenen Benutzer lesbar
nano ~/.whatsapp-env          # echte Werte eintragen; falls nano fehlt: pkg install nano
```

Einen Prüf-Token denkst du dir selbst aus – ohne Zusatzpaket geht das so:

```bash
head -c 24 /dev/urandom | base64
```

Dann einmalig einhängen:

```bash
grep -q whatsapp-env ~/.bashrc || echo '[ -f ~/.whatsapp-env ] && . ~/.whatsapp-env' >> ~/.bashrc
. ~/.whatsapp-env             # für die laufende Sitzung sofort aktiv
```

Ab dem nächsten Start passiert das von allein. Prüfen:

```bash
cd ~/mein-node-server         # dort, wohin du geklont hast
npm run whatsapp:pruefen
```

Läuft der Befehl mit „fatal: not a git repository“ oder „Could not read
package.json“ auf, stehst du im falschen Verzeichnis – das sind die Meldungen
aus dem Termux-Home. `find ~ -maxdepth 3 -name package.json` findet den
Projektordner wieder.

## Webhook eintragen

Meta ruft den Webhook über HTTPS auf, also braucht der Server eine öffentliche
Adresse. Zum Entwickeln genügt ein Tunnel:

```bash
cloudflared tunnel --url http://localhost:3000
# oder: ngrok http 3000
```

Im Meta-Dashboard unter *WhatsApp → Konfiguration → Webhook* eintragen:

- **Callback-URL:** `https://deine-adresse/whatsapp/webhook`
- **Verify-Token:** derselbe Wert wie in `WHATSAPP_PRUEF_TOKEN`
- danach bei den Feldern **`messages`** abonnieren – ohne das kommt nichts an.

Meta ruft die Adresse einmal per `GET` auf und erwartet die `hub.challenge`
zurück. Klappt das nicht, steht der Grund im Serverlog.

## Bedienung im Chat

Einfach schreiben. Zusätzlich versteht der Bot:

| Befehl | Wirkung |
| --- | --- |
| `/neu` | Gesprächsverlauf vergessen und neu anfangen |
| `/hilfe` | Kurzübersicht |

Sprachnachrichten und Bilder versteht er ebenfalls (siehe unten). Videos,
Sticker und Dateien beantwortet er mit einem Hinweis.

## Sprachnachrichten

Sobald `WHISPER_API_KEY` (oder `WHISPER_URL`) gesetzt ist, beantwortet der Bot
auch Sprachnachrichten. Der Weg dahin:

1. Der Webhook liefert nur eine **Medien-ID**, keine Datei.
2. Über die Graph-API kommen die Metadaten samt befristeter Adresse.
3. Die Datei selbst wird von dieser Adresse geladen – **auch dieser Aufruf
   braucht den Zugriffstoken**, die Adresse allein genügt nicht.
4. Whisper macht Text daraus, der Rest läuft wie bei einer getippten Nachricht:
   Verlauf, Claude, Antwort.

Über der Antwort steht in kursiv, was verstanden wurde – sonst rätselt man bei
einer unpassenden Antwort, ob Claude oder die Transkription danebenlag.
Abschalten mit `WHATSAPP_TRANSKRIPT_ZEIGEN=0`.

WhatsApp-Sprachnachrichten sind immer Opus in einem Ogg-Behälter, das nimmt
Whisper direkt an – eine Umwandlung mit ffmpeg ist nicht nötig. Angehängte
Audiodateien in anderen Formaten werden ebenfalls versucht; AMR von älteren
Telefonen lehnt Whisper ab, das meldet der Bot verständlich zurück.

### Ohne fremden Dienst

`WHISPER_URL` zeigt auf jeden Server, der dieselbe Schnittstelle anbietet
(`POST /audio/transcriptions`, multipart) – etwa
[whisper.cpp](https://github.com/ggml-org/whisper.cpp) oder
[speaches](https://github.com/speaches-ai/speaches) auf dem eigenen Rechner:

```bash
export WHISPER_URL="http://127.0.0.1:8080/v1"
```

Einen Schlüssel braucht ein lokaler Server in der Regel nicht; der Bot lässt
den Kopf dann weg. So verlässt keine Aufnahme das eigene Netz.

## Bilder

Bilder brauchen keine Einrichtung und keinen zweiten Dienst: Claude sieht sie
selbst. Der Weg ist derselbe wie bei Sprachnachrichten – Medien-ID, Metadaten,
Datei –, nur geht die Datei danach unverändert als Bildblock an die API,
**vor** dem Text: so liest Claude sie am besten.

Die Bildunterschrift ist die Frage. Fehlt sie, fragt der Bot von sich aus
„Was ist auf diesem Bild zu sehen?“ (`WHATSAPP_BILD_FRAGE`). Steht in der
Unterschrift ein Befehl wie `/neu`, ist das Bild nicht gemeint – dann wird es
gar nicht erst heruntergeladen.

Lesbar sind JPEG, PNG, GIF und WebP. Bewegte Bilder werden zum ersten
Einzelbild; andere Formate meldet der Bot verständlich zurück.

### Warum nicht jedes Bild ewig mitgeht

Die Messages-API ist zustandslos: bei jeder Anfrage geht der ganze Verlauf mit,
Bilder eingeschlossen – und jedes Bild kostet jedes Mal aufs Neue. Deshalb
tragen nur die jüngsten Bilder (`WHATSAPP_BILDER_IM_VERLAUF`, Vorgabe 2) ihre
Daten wirklich mit; ältere schrumpfen im Verlauf auf `[Bild]` samt Unterschrift.
Rückfragen zum gerade geschickten Bild funktionieren also, das Bild von vorletzter
Woche belastet aber keine Rechnung mehr.

Die Bilder liegen als BLOB in `wa_verlauf`. Wer den Verlauf mit `/neu` löscht,
wird sie mit los.

## Endpunkte

| Endpunkt | Methode | Zweck |
| --- | --- | --- |
| `/whatsapp/webhook` | GET | Bestätigung durch Meta (`hub.challenge`) |
| `/whatsapp/webhook` | POST | eingehende Nachrichten, signaturgeprüft |
| `/whatsapp/status` | GET | Zustand des Bots, ohne Zugangsdaten – nur vom Gerät selbst |

## Sicherheit

Drei Dinge hängen hier zusammen: eine öffentlich erreichbare Adresse, ein
API-Schlüssel, der Geld kostet, und ein Modell, das auf alles antwortet. Deshalb:

- **Signaturprüfung.** Jede `POST`-Anfrage muss den Header
  `X-Hub-Signature-256` mitbringen, den Meta mit dem App-Geheimnis über den
  rohen Anfragekörper berechnet. Ohne gültige Signatur: `403`. Genau deswegen
  hängt der Router in `app.js` **vor** `express.json()` – ein bereits geparster
  Körper lässt sich nicht zeichengenau nachbilden, und die Prüfung würde
  scheitern.
- **Freigabeliste.** `WHATSAPP_ERLAUBTE_NUMMERN` ist Pflicht. Nachrichten
  anderer Absender werden verworfen und nur protokolliert – sie bekommen keine
  Antwort, die verrät, dass hier jemand zuhört. Bewusst für alle öffnen:
  `WHATSAPP_ERLAUBTE_NUMMERN=alle`.
- **Doppelte Zustellungen.** Meta stellt „mindestens einmal“ zu und wiederholt
  stundenlang, wenn eine Antwort ausbleibt. Jede Nachrichten-ID wird deshalb in
  `wa_gesehen` vermerkt; Wiederholungen kosten kein zweites Mal Geld.
  Nachrichten, die älter als eine Stunde sind, beantwortet der Bot gar nicht
  mehr.
- **Keine Interna im Chat.** Fehler der API werden übersetzt („zu viele
  Anfragen“, „Zugang ungültig“); Details stehen nur im Serverlog.

## Aufbau

| Datei | Aufgabe |
| --- | --- |
| `whatsapp/index.js` | setzt alles zusammen, liefert den Router |
| `whatsapp/konfig.js` | Umgebungsvariablen, Pflichtangaben, Freigabeliste |
| `whatsapp/routen.js` | Express-Router: Bestätigung, Webhook, Status |
| `whatsapp/nachrichten.js` | Signatur, Webhook-Format, Befehle, Textaufteilung |
| `whatsapp/verlauf.js` | Gesprächsverlauf und Doppel-Erkennung in SQLite |
| `whatsapp/claude.js` | Anfrage an die Messages-API, Auswertung der Antwort |
| `whatsapp/medien.js` | Sprachnachrichten und Bilder von Meta herunterladen |
| `whatsapp/whisper.js` | Transkription über die Whisper-Schnittstelle |
| `whatsapp/versand.js` | Graph-API-Aufrufe inklusive Wiederholung |
| `whatsapp/bot.js` | Ablauf: Nachricht → Verlauf → Claude → Antwort |
| `whatsapp/pruefen.js` | Einrichtung testen, ohne aufs Handy zu warten |

Der Verlauf liegt in derselben SQLite-Datei wie der Rest der Anwendung
(`datenbank.db`, Tabellen `wa_verlauf` und `wa_gesehen`) und übersteht einen
Neustart.

### Warum eine Warteschlange je Nummer

Die Messages-API ist zustandslos: bei jeder Anfrage geht der bisherige Verlauf
komplett mit. Schreibt jemand zweimal schnell hintereinander, würde die zweite
Anfrage sonst einen Verlauf sehen, in dem die erste Antwort noch fehlt – Claude
antwortet dann zweimal auf denselben Stand. `bot.js` reiht Nachrichten derselben
Nummer deshalb hintereinander auf; verschiedene Absender laufen weiter parallel.

## Grenzen

- **24-Stunden-Fenster.** Meta erlaubt freie Textnachrichten nur, wenn der
  Nutzer innerhalb der letzten 24 Stunden geschrieben hat. Danach gehen nur noch
  genehmigte Vorlagen durch. Für einen Bot, den man selbst anschreibt, spielt
  das keine Rolle.
- **Text, Sprache, Bilder.** Videos, Sticker und Dokumente werden erkannt, aber
  nicht ausgewertet.
- **Zweiter Dienst für Sprache.** Claude selbst hört nichts – ohne Whisper
  bleiben Sprachnachrichten unbeantwortet. Beim gehosteten Dienst verlässt die
  Aufnahme das eigene Netz; wem das nicht passt, der setzt `WHISPER_URL` auf
  einen lokalen Server.
- **Ein Prozess.** Der Verlauf liegt in einer lokalen SQLite-Datei; mehrere
  Instanzen hinter einem Lastverteiler teilen ihn sich nicht.
- **Kosten.** Jede Nachricht ist ein API-Aufruf mit dem gesamten mitgeschickten
  Verlauf. `WHATSAPP_VERLAUF_NACHRICHTEN` begrenzt, wie teuer eine einzelne
  Anfrage werden kann; Sprachnachrichten kosten zusätzlich die Transkription,
  Bilder je nach Größe ein Vielfaches einer Textnachricht.
