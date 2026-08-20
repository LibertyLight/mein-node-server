#!/usr/bin/env bash
#
# Claude Code unter Termux (Android) einrichten.
#
# Hintergrund: Claude Code wird als natives Binary ausgeliefert, das gegen
# glibc bzw. musl gelinkt ist. Termux nutzt Androids Bionic-libc, deshalb
# laesst sich das Binary dort nicht direkt ausfuehren. Der etablierte Weg ist
# eine echte Linux-Umgebung via proot-distro. Dasselbe Verfahren nutzt das
# Projekt PocketCode (github.com/rajbreno/PocketCode) fuer OpenCode.
#
# Das Skript ist idempotent: mehrfaches Ausfuehren schadet nicht.
#
# Aufruf in Termux:
#   bash scripts/termux-claude-code.sh
#
set -euo pipefail

DISTRO="debian"

info() { printf '\n\033[1;34m==>\033[0m %s\n' "$1"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$1" >&2; }
die()  { printf '\033[1;31mFehler:\033[0m %s\n' "$1" >&2; exit 1; }

# --- Vorbedingungen -------------------------------------------------------

[ -d /data/data/com.termux ] || die "Das hier laeuft nicht in Termux. Abbruch."
[ -n "${PREFIX:-}" ] || die "PREFIX ist nicht gesetzt. Laeuft Termux korrekt?"

case "$(uname -m)" in
  aarch64|arm64) ;;
  *) warn "Architektur $(uname -m) ist ungetestet. Erwartet wird aarch64." ;;
esac

# --- Termux-Basis ---------------------------------------------------------

info "Termux-Pakete aktualisieren"
pkg update -y
pkg upgrade -y

info "proot-distro installieren"
pkg install -y proot-distro

# --- Linux-Umgebung -------------------------------------------------------

# Funktionale Pruefung statt Pfad-Annahme: laesst sich die Distro starten,
# ist sie installiert. Wo proot-distro das Rootfs ablegt, ist damit egal --
# der Pfad hat sich zwischen den Versionen geaendert (5.6.0 ist eine
# Python-Neufassung).
if proot-distro login "$DISTRO" -- true >/dev/null 2>&1; then
  info "$DISTRO ist bereits installiert, wird uebersprungen"
else
  info "$DISTRO installieren (das dauert je nach Verbindung einige Minuten)"
  proot-distro install "$DISTRO"
  proot-distro login "$DISTRO" -- true >/dev/null 2>&1 \
    || die "$DISTRO liess sich nach der Installation nicht starten."
fi

# --- Innerer Teil: laeuft in Debian ---------------------------------------

info "Claude Code in $DISTRO installieren"

INNER_TMP="$(mktemp)"
trap 'rm -f "$INNER_TMP"' EXIT

cat > "$INNER_TMP" <<'INNER'
#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

echo "--> Systempakete"
apt-get update -qq
apt-get install -y -qq curl git ca-certificates ripgrep less

# PATH dauerhaft setzen, bevor installiert wird
if ! grep -qs 'HOME/.local/bin' /root/.bashrc; then
  echo 'export PATH="$HOME/.local/bin:$PATH"' >> /root/.bashrc
fi
export PATH="$HOME/.local/bin:$PATH"

if command -v claude >/dev/null 2>&1; then
  echo "--> Claude Code ist bereits vorhanden: $(command -v claude)"
else
  echo "--> Offizieller Installer"
  if curl -fsSL https://claude.ai/install.sh | bash; then
    echo "--> Installer durchgelaufen"
  else
    echo "--> Installer fehlgeschlagen, weiche auf npm aus"
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y -qq nodejs
    npm install -g @anthropic-ai/claude-code
  fi
fi

export PATH="$HOME/.local/bin:$PATH"
if command -v claude >/dev/null 2>&1; then
  echo "--> Version: $(claude --version 2>&1 || echo 'konnte nicht ermittelt werden')"
else
  echo "!! claude wurde nicht gefunden. Pruefe die Ausgabe oben." >&2
  exit 1
fi
INNER

# Das Skript wird base64-kodiert uebergeben, damit weder ein Pfad im Rootfs
# noch eine Bind-Mount-Option noetig ist. base64 liefert nur [A-Za-z0-9+/=],
# ist also im Kommando unproblematisch.
proot-distro login "$DISTRO" -- bash -c \
  "echo $(base64 -w0 "$INNER_TMP") | base64 -d | bash"

# --- Komfort in Termux ----------------------------------------------------

ALIAS_LINE="alias linux='proot-distro login $DISTRO'"
if ! grep -qsF "$ALIAS_LINE" "$HOME/.bashrc"; then
  info "Alias 'linux' in ~/.bashrc eintragen"
  printf '\n%s\n' "$ALIAS_LINE" >> "$HOME/.bashrc"
fi

cat <<EOF

============================================================
 Fertig.

 So startest du:

   source ~/.bashrc     # einmalig, oder Termux neu oeffnen
   linux                # wechselt nach $DISTRO
   claude               # startet Claude Code

 Dein Projekt liegt in Termux unter ~/mein-node-server und ist
 in $DISTRO NICHT sichtbar. Klone es dort separat:

   linux
   git clone https://github.com/LibertyLight/mein-node-server
   cd mein-node-server && claude

 Speicher: $DISTRO belegt rund 1,5 GB.
============================================================

EOF
