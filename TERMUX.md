# Claude Code unter Termux

## Warum es nicht einfach so geht

`npm install -g @anthropic-ai/claude-code` läuft in Termux zwar durch, aber
`claude` startet danach nicht. Grund: Claude Code wird als natives Binary
ausgeliefert. Ein Blick auf das ARM64-Paket zeigt:

```
ELF 64-bit LSB executable, ARM aarch64, dynamically linked,
interpreter /lib/ld-musl-aarch64.so.1
```

Termux nutzt Androids Bionic-libc und hat weder `/lib/ld-musl-aarch64.so.1`
noch glibc. Offiziell unterstützt sind macOS 13+, Windows, Ubuntu 20.04+,
Debian 10+ und Alpine 3.19+ — Android ist nicht dabei.

## Die Lösung: eine echte Linux-Umgebung

`proot-distro` richtet ein Debian in Termux ein, ohne Root-Rechte. Darin gibt
es glibc, und das Binary läuft. Dasselbe Verfahren nutzt
[PocketCode](https://github.com/rajbreno/PocketCode) für OpenCode.

```bash
bash scripts/termux-claude-code.sh
```

Das Skript ist idempotent — mehrfaches Ausführen schadet nicht. Es macht:

1. Termux-Pakete aktualisieren
2. `proot-distro` installieren, Debian einrichten
3. In Debian: `curl`, `git`, `ripgrep` installieren
4. Claude Code über den offiziellen Installer einrichten, bei Fehlschlag über npm
5. `PATH` um `~/.local/bin` ergänzen
6. Alias `linux` in Termux anlegen

## Danach

```bash
source ~/.bashrc   # einmalig
linux              # wechselt nach Debian
claude             # startet Claude Code
```

## Was du wissen solltest

**Auf einem Gerät erprobt.** Android 15, ARM64, Termux mit proot-distro
5.6.0 — Debian eingerichtet, Claude Code 2.1.236 unter
`~/.local/bin/claude` installiert. Sollte ein Schritt bei dir scheitern,
ist die Ausgabe an der Stelle der beste Anhaltspunkt.

Eine Stolperfalle ist dabei aufgefallen: Frühere Fassungen des Skripts
suchten das Rootfs unter `$PREFIX/var/lib/proot-distro/installed-rootfs/`.
proot-distro 5.6.0 ist eine Python-Neufassung und legt es woanders ab.
Das Skript verlässt sich deshalb auf keinen Pfad mehr, sondern prüft mit
`proot-distro login debian -- true`, ob die Distro startet.

**Getrennte Dateisysteme.** Dein Termux-Home und das Debian-Home sind zwei
verschiedene Orte. Ein Projekt in `~/mein-node-server` (Termux) ist in Debian
nicht sichtbar. Entweder dort separat klonen, oder mit `--bind` einhängen:

```bash
proot-distro login debian --bind $HOME:/mnt/termux
```

**Speicherbedarf.** Debian belegt rund 1,5 GB.

**Geschwindigkeit.** proot emuliert Systemaufrufe im Userspace. Alles läuft
spürbar langsamer als nativ, besonders Dateioperationen.

## Die Alternative

Du brauchst das alles nicht, wenn dir Claude Code im Browser reicht:
[claude.ai/code](https://claude.ai/code) läuft auf dem Handy ohne
Installation, und GitHub ist dort bereits angebunden. Der lokale CLI lohnt
sich vor allem, wenn du offline oder direkt auf den Dateien in Termux
arbeiten willst.
