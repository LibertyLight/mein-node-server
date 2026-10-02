# Tastatur+ – Android-Tastatur mit KI-Schreibhilfe

Eine eigene Android-Tastatur nach dem Vorbild des Samsung-Keyboards mit
Galaxy AI. Die App liegt in `tastatur/`, die KI läuft über diesen Node-Server
(`tastatur-ki/`, Route `/api/tastatur`).

```
Tastatur (Handy)  →  POST /api/tastatur/ki  →  Node-Server  →  Claude
                  ←  { text }               ←               ←
```

Der API-Schlüssel bleibt auf dem Server. Die Tastatur kennt nur ein eigenes
Zugangswort, das nur für diese Route gilt.

## Was drin ist

| Samsung Keyboard / Galaxy AI | Tastatur+ |
| --- | --- |
| QWERTZ mit ü ö ä, Zahlenreihe, Langdruck-Sonderzeichen | ✅ inkl. Hinweiszeichen oben rechts |
| Symbolseiten 1/2 und 2/2 | ✅ |
| Wortvorschläge, Lernen eigener Wörter, Folgewort | ✅ auf dem Gerät, ~66.000 Wörter, langes Tippen auf einen Vorschlag vergisst ihn |
| Autokorrektur | ✅ bei Leertaste/Satzzeichen; ⌫ direkt danach macht sie rückgängig |
| Auto-Großschreibung, Doppel-Leertaste = Punkt | ✅ |
| Cursor per Wischen über die Leertaste | ✅ |
| Emojis mit „Zuletzt verwendet“ | ✅ |
| Zwischenablage-Verlauf | ✅ ohne Passwortfelder und als vertraulich markierte Inhalte |
| Textbearbeitung (Pfeile, Markieren, Kopieren …) | ✅ |
| Einhandmodus, Höhe, Hell/Dunkel | ✅ |
| Buchstabengröße, Tasten in Großbuchstaben | ✅ |
| Spracheingabe | ✅ über die Spracherkennung des Geräts |
| **Schreibhilfe: Ton ändern** (Professionell, Locker, Höflich, Social Media, Emojis) | ✅ Claude |
| **Rechtschreibung & Grammatik** | ✅ Claude |
| **Zusammenfassen**, **Stichpunkte** | ✅ Claude |
| **Übersetzen** (Chat-Übersetzung) | ✅ 12 Sprachen, Claude |
| **Verfassen** aus Stichworten (Composer) | ✅ Nachricht, E-Mail, Social-Post |
| Wischen zum Schreiben (Swype) | ❌ bewusst weggelassen |
| Handschrift, Sticker, GIFs, Bitmoji | ❌ |
| Antwortvorschläge zu *empfangenen* Nachrichten | ❌ eine Tastatur sieht fremde Chat-Inhalte nicht |
| KI ohne Internet direkt auf dem Gerät | ❌ (Samsung nutzt dafür ein eigenes On-Device-Modell) |

Die Schreibhilfe bearbeitet den **markierten Text**. Ist nichts markiert,
bearbeitet sie den **ganzen Text im Feld**. Das Ergebnis erscheint erst als
Vorschau. Von dort kannst du es ersetzen, einfügen oder kopieren.

## Server einrichten

```bash
export ANTHROPIC_API_KEY="sk-ant-…"
export TASTATUR_TOKEN="$(openssl rand -hex 24)"   # mindestens 16 Zeichen
echo "$TASTATUR_TOKEN"                             # für die App notieren
npm run start:app
```

Beim Start steht im Log `Schreibhilfe für die Tastatur aktiv`. Fehlt etwas,
sagt der Server, was. Ohne `TASTATUR_TOKEN` bleibt die Route aus. Der Server
lauscht auf allen Netzwerkschnittstellen, und sonst könnte jeder im WLAN auf
deine Kosten Claude nutzen.

Freiwillig:

| Variable | Vorgabe | Zweck |
| --- | --- | --- |
| `TASTATUR_MODELL` | `claude-opus-5-5` | Modell |
| `TASTATUR_AUFWAND` | `low` | `low` antwortet am schnellsten, `medium`/`high` gründlicher |
| `TASTATUR_MAX_ZEICHEN` | `8000` | Längster Text, der bearbeitet wird |
| `TASTATUR_ANFRAGEN_PRO_MINUTE` | `30` | Bremse gegen Ausreißer |

Lehnt ein Sicherheitsfilter eine Anfrage ab, springt serverseitig automatisch
ein anderes Modell ein (`fallbacks: "default"`).

## App installieren

1. APK besorgen: Entweder die `app-debug.apk` aus dem Chat, oder auf GitHub unter
   *Actions → Tastatur-APK → letzter Lauf → Artifacts*.
   Selbst bauen geht so: `cd tastatur && ./gradlew assembleDebug`.
2. Auf dem Handy öffnen. Android fragt einmalig, ob die Installation aus
   dieser Quelle erlaubt ist.
3. **Tastatur+** öffnen und die Schritte unter „Einrichten“ antippen:
   aktivieren, als aktuelle Tastatur wählen, Mikrofon erlauben.
4. Unter „Schreibhilfe“ das Zugangswort eintragen und **Verbindung testen**.

Android warnt beim Aktivieren jeder fremden Tastatur, dass sie alles Getippte
mitlesen *könnte*. Das ist Standard. Diese Tastatur schickt Text nur dann an
deinen Server, wenn du in der Schreibhilfe eine Aktion antippst.

### Server-Adresse

- Server in **Termux auf demselben Handy**: `http://127.0.0.1:3000` (Vorgabe).
- Server **woanders**: nur per **HTTPS**, z. B. hinter einem Reverse-Proxy oder
  Tunnel. Unverschlüsseltes HTTP erlaubt die App bewusst nur zu `127.0.0.1`,
  denn sonst ginge das Zugangswort im Klartext durchs Netz.

## Autokorrektur

Korrigiert wird, sobald ein Wort mit Leertaste, Satzzeichen oder Enter endet.
Was gleich eingesetzt wird, steht **fett** in der Vorschlagsleiste.

- **⌫ direkt danach** holt dein getipptes Wort zurück. Die Tastatur merkt es
  sich und korrigiert es nicht wieder.
- **Getipptes Wort antippen** (ganz links in der Leiste) übernimmt es so, wie
  es ist, und merkt es sich ebenfalls.
- Erkannt werden typische Fehler: Nachbartasten auf QWERTZ, vertauschte
  Buchstaben, vergessene oder verdoppelte Buchstaben, fehlende Umlaute
  („fur“ → „für“).
- Nicht korrigiert wird: in Adress-, E-Mail- und Passwortfeldern, nach
  `@`, `#` oder `/`, Wörter mit Ziffern, Abkürzungen in GROSSBUCHSTABEN und
  Wörter, die nur eine Endung an ein bekanntes Wort hängen („Dorfs“).
  Großgeschriebene Wörter mitten im Satz (oft Namen) werden nur bei kleinen
  Fehlern korrigiert.
- Ein Wort, das du zweimal unkorrigiert schreibst, gilt als bekannt.

Bei sehr seltenen echten Wörtern (meist Namen) liegt die Fehlkorrekturrate in
Tests bei etwa 9 %. Dann einmal ⌫, ab da kennt die Tastatur das Wort.
Ausschalten lässt sich die Autokorrektur in den Einstellungen.

Herkunft der Wortliste: siehe `tastatur/WOERTERLISTE.md`.

## Bedienung

- **✨** in der Leiste: Schreibhilfe. Erst Text markieren oder schreiben.
- **⋯** neben den Vorschlägen: zurück zu den Werkzeugen.
- **Leertaste lang drücken**: Tastatur wechseln. **Wischen**: Cursor bewegen.
- **⇧ zweimal schnell**: Feststelltaste.
- **Taste lang drücken**: Sonderzeichen (z. B. `e` → é è ê ë €, `s` → ß).

## Bekannte Grenzen

- Die APK ist mit dem Debug-Schlüssel signiert. Baut man sie auf einem anderen
  Rechner neu, muss die alte Version vorher deinstalliert werden.
- Für eine Veröffentlichung im Play Store fehlen ein Release-Schlüssel und eine
  Datenschutzerklärung.
