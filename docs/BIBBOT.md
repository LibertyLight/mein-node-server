# BibBot auf dem Smartphone

Kurzfassung der Fehlersuche zu den Versuchen, BibBot per Termux gebaut und in
Lemur/Kiwi/Carbon geladen zu bekommen.

## Was schiefgelaufen ist

**1. Das selbstgebaute Zip war unvollstaendig.**
Der verwendete Befehl war

```bash
zip -r ../bibbot.zip manifest.json build assets icons popup options.html popup.html
```

`options.html` und `popup.html` gibt es im Projekt gar nicht auf oberster Ebene.
Die `manifest.json` von BibBot verweist auf

* `options/options.html` (Optionsseite)
* `popup/popup.html` (Symbolleisten-Popup)
* `build/background.js`, `build/content.js`
* `icons/…`

Fehlt die Optionsseite, lehnt jede Chromium-Engine das Paket ab - je nach Browser
mit Fehlermeldung oder eben stillschweigend. Das Projekt bringt mit `dist.sh`
eine eigene Paketliste mit; `scripts/bibbot-build.sh` in diesem Repo macht
dasselbe und prueft das Ergebnis anschliessend.

**2. Kiwi Browser existiert nicht mehr.**
Das Projekt wurde Anfang 2025 eingestellt, das GitHub-Repo archiviert und die App
aus dem Play Store genommen. Der Erweiterungs-Code ist in Microsoft Edge Canary
eingeflossen. Alle Anleitungen "nimm Kiwi" laufen deshalb ins Leere - installiert
wurde am Ende Carbon Browser, der lokale Zips nicht entpackt.

**3. BibBot ist sehr wohl im Chrome Web Store.**
Die mobile Store-Suche blendet vieles aus, ueber den Direktlink geht es trotzdem:
<https://chrome.google.com/webstore/detail/bibbot/edafomjglmkfbiieocpflnhfdmikkhbo>

**4. Firefox fuer Android ist keine Option.**
In der `manifest.json` steht unter `browser_specific_settings.gecko` kein
`gecko_android`-Block. Ohne den taucht das Add-on in der mobilen AMO-Ansicht
nicht auf und laesst sich im Release-Firefox nicht installieren. Ausserdem nutzt
das Manifest `background.service_worker` (Manifest V3, Chromium-Variante).

## Weg A - ohne Termux (empfohlen)

1. Browser mit Erweiterungs-Unterstuetzung installieren: **Quetta**, **Lemur**
   oder **Mises** (alle im Play Store), alternativ **Edge Canary**.
2. Den Direktlink oben oeffnen.
3. "Zu Chrome hinzufuegen" / "Add to Chrome".
4. Erweiterungen -> BibBot -> Optionen: Bibliothek auswaehlen, Zugangsdaten
   eintragen.

## Weg B - eigenes Paket aus Termux

```bash
bash scripts/bibbot-build.sh
```

Das Skript klont bzw. aktualisiert `stefanw/bibbot`, baut mit `npm run build`,
packt genau die benoetigten Dateien, prueft das Paket und legt es als
`bibbot.zip` **und** `bibbot.crx` in `~/storage/shared/Download/` ab. Die
`.crx`-Kopie hilft bei Browsern, deren Dateidialog auf diese Endung filtert.

Danach im Browser: Erweiterungen -> Entwicklermodus an -> `+ (from .zip/.crx/…)`
-> Downloads -> `bibbot.zip`.

Voraussetzungen in Termux:

```bash
pkg install git nodejs zip unzip -y
termux-setup-storage
```

`node_modules` niemals in den Geraetespeicher kopieren - der enthaelt Symlinks,
die FAT/SDCardFS nicht abbilden kann. Genau daher kam der Kopierfehler.

## Realistische Erwartung

BibBot oeffnet im Hintergrund Tabs, loggt sich bei Genios/Munzinger ein und holt
den Artikeltext. Android-Browser frieren Hintergrund-Tabs aggressiv ein, deshalb
bricht der Ablauf auf dem Handy oefter ab als am Desktop. Wenn es haengt: den
BibBot-Tab im Vordergrund lassen, bis der Artikel geladen ist.

Zuverlaessiger Ersatz auf dem Handy ohne Erweiterung: direkt bei der Bibliothek
anmelden (z. B. `wiso-net.de` ueber den Bibliothekszugang) und den Artikel dort
per Titelsuche oeffnen. Genau das automatisiert BibBot - manuell sind es zwei
Klicks mehr, dafuer funktioniert es in jedem Browser.
