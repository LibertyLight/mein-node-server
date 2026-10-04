#!/usr/bin/env bash
#
# Paperclip-Verknuepfung fuer das Termux:Widget auf dem Android-Homescreen.
#
# Legt ~/.shortcuts/Paperclip.sh an. Beim Antippen des Widgets oeffnet sich
# Termux mit Paperclip im Vordergrund, und nach einigen Sekunden oeffnet sich
# die UI im Browser.
#
# Das Skript ist idempotent: mehrfaches Ausfuehren ueberschreibt nur die
# Verknuepfung.
#
# Aufruf in Termux:
#   bash scripts/termux-paperclip-widget.sh            # laeuft direkt in Termux
#   MODUS=debian bash scripts/termux-paperclip-widget.sh   # laeuft im Debian (proot-distro)
#
# Danach einmalig: App "Termux:Widget" aus F-Droid installieren und das
# Widget auf dem Homescreen platzieren (Langes Druecken > Widgets > Termux).
#
set -euo pipefail

MODUS="${MODUS:-termux}"
PORT="${PAPERCLIP_PORT:-3100}"
DISTRO="debian"
SHORTCUT="$HOME/.shortcuts/Paperclip.sh"

info() { printf '\n\033[1;34m==>\033[0m %s\n' "$1"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$1" >&2; }
die()  { printf '\033[1;31mFehler:\033[0m %s\n' "$1" >&2; exit 1; }

[ -d /data/data/com.termux ] || die "Das hier laeuft nicht in Termux. Abbruch."
case "$MODUS" in
  termux|debian) ;;
  *) die "MODUS muss 'termux' oder 'debian' sein (war: $MODUS)." ;;
esac

# --- Laufzeitumgebung -----------------------------------------------------

if [ "$MODUS" = "termux" ]; then
  info "Node.js in Termux sicherstellen"
  command -v node >/dev/null 2>&1 || pkg install -y nodejs
  START_CMD="npx --yes paperclipai run"
else
  command -v proot-distro >/dev/null 2>&1 \
    || die "proot-distro fehlt. Erst 'bash scripts/termux-claude-code.sh' ausfuehren."
  proot-distro login "$DISTRO" -- true >/dev/null 2>&1 \
    || die "Debian startet nicht. Erst 'bash scripts/termux-claude-code.sh' ausfuehren."

  info "Node.js 22 in Debian sicherstellen"
  proot-distro login "$DISTRO" -- bash -c '
    command -v node >/dev/null 2>&1 && exit 0
    apt-get update && apt-get install -y curl ca-certificates
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y nodejs
  '
  START_CMD="proot-distro login $DISTRO -- npx --yes paperclipai run"
fi

# termux-open-url gehoert zu termux-tools und ist normalerweise vorhanden.
command -v termux-open-url >/dev/null 2>&1 \
  || warn "termux-open-url fehlt - die UI muss von Hand geoeffnet werden."

# --- Verknuepfung schreiben -----------------------------------------------

info "Verknuepfung anlegen: $SHORTCUT"
mkdir -p "$HOME/.shortcuts"
cat > "$SHORTCUT" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
# Von scripts/termux-paperclip-widget.sh erzeugt.
echo "Paperclip startet - UI: http://localhost:$PORT"
( sleep 10; termux-open-url "http://localhost:$PORT" ) &
$START_CMD
echo
echo "Paperclip wurde beendet. Mit Enter schliessen."
read -r _
EOF
chmod 700 "$SHORTCUT"
chmod 700 "$HOME/.shortcuts"

info "Fertig"
cat <<EOF
Naechste Schritte auf dem Handy:
  1. Termux:Widget aus F-Droid installieren (gleiche Quelle wie Termux).
  2. Homescreen lang druecken > Widgets > Termux:Widget platzieren.
  3. "Paperclip" im Widget antippen.
EOF
