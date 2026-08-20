#!/data/data/com.termux/files/usr/bin/bash
#
# BibBot unter Termux bauen und als installierbares Paket in den Download-Ordner legen.
#
# Aufruf:  bash scripts/bibbot-build.sh
#
# Ergebnis: ~/storage/shared/Download/bibbot.zip  (und bibbot.crx als Kopie)
#           Der Zip-Inhalt entspricht exakt dem, was das Projekt selbst per dist.sh
#           ausliefert - inklusive der Ordner options/ und popup/, die in vielen
#           Anleitungen fehlen und deshalb zu "Paket ungueltig" beim Laden fuehren.

set -euo pipefail

BIBBOT_DIR="${BIBBOT_DIR:-$HOME/bibbot}"
DOWNLOAD_DIR="${DOWNLOAD_DIR:-$HOME/storage/shared/Download}"
REPO_URL="https://github.com/stefanw/bibbot.git"

info()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
fail()  { printf '\033[1;31mXX\033[0m %s\n' "$*" >&2; exit 1; }

# 1. Werkzeuge -----------------------------------------------------------------
for tool in git node npm zip unzip; do
  command -v "$tool" >/dev/null 2>&1 || fail "'$tool' fehlt. Installiere es mit: pkg install $tool -y"
done

# 2. Speicherzugriff -----------------------------------------------------------
if [ ! -d "$HOME/storage" ]; then
  warn "Termux hat noch keinen Zugriff auf den Geraetespeicher."
  fail "Fuehre zuerst 'termux-setup-storage' aus und erlaube den Zugriff."
fi
mkdir -p "$DOWNLOAD_DIR"

# 3. Quellcode holen bzw. aktualisieren ----------------------------------------
if [ -d "$BIBBOT_DIR/.git" ]; then
  info "Aktualisiere vorhandenes Repository in $BIBBOT_DIR"
  git -C "$BIBBOT_DIR" pull --ff-only
else
  info "Klone $REPO_URL nach $BIBBOT_DIR"
  git clone "$REPO_URL" "$BIBBOT_DIR"
fi

cd "$BIBBOT_DIR"

# 4. Bauen ---------------------------------------------------------------------
info "Installiere Abhaengigkeiten (dauert beim ersten Mal ein paar Minuten)"
npm install --no-audit --no-fund

info "Baue die Erweiterung"
npm run build

# 5. Paket schnueren -----------------------------------------------------------
# Wichtig: manifest.json muss auf oberster Ebene im Zip liegen, und alle im
# Manifest referenzierten Pfade muessen mitkopiert werden.
info "Packe die Erweiterung"
rm -rf dist
mkdir -p dist
zip -q -r -FS dist/bibbot.zip \
  manifest.json \
  build/* \
  icons/* \
  assets/* \
  options/* \
  popup/* \
  LICENSE README.md

# 6. Paket pruefen -------------------------------------------------------------
info "Pruefe Paketinhalt"
listing="$(unzip -Z1 dist/bibbot.zip)"
missing=0
for required in manifest.json build/background.js build/content.js options/options.html popup/popup.html; do
  if printf '%s\n' "$listing" | grep -qx "$required"; then
    printf '   ok      %s\n' "$required"
  else
    printf '   FEHLT   %s\n' "$required"
    missing=1
  fi
done
[ "$missing" -eq 0 ] || fail "Das Paket ist unvollstaendig - der Browser wuerde es ablehnen."

# 7. In den Download-Ordner kopieren -------------------------------------------
cp dist/bibbot.zip "$DOWNLOAD_DIR/bibbot.zip"
cp dist/bibbot.zip "$DOWNLOAD_DIR/bibbot.crx"   # manche Browser filtern den Dateidialog auf .crx

version="$(node -p "require('./manifest.json').version")"
info "Fertig: BibBot $version liegt als bibbot.zip und bibbot.crx im Download-Ordner"
ls -la "$DOWNLOAD_DIR/bibbot.zip" "$DOWNLOAD_DIR/bibbot.crx"

cat <<'HINWEIS'

Naechster Schritt im Browser (Quetta / Lemur / Mises, Entwicklermodus an):
  Erweiterungen -> "+ (from .zip/.crx/...)" -> Downloads -> bibbot.zip

Einfacher ist meistens die Installation direkt aus dem Chrome Web Store:
  https://chrome.google.com/webstore/detail/bibbot/edafomjglmkfbiieocpflnhfdmikkhbo
Dafuer wird dieses Skript gar nicht gebraucht.
HINWEIS
