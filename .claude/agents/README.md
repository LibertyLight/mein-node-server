# Subagents

Vier Subagents, ausgewählt für den Stack dieses Projekts (Express, natives
`node:sqlite`, Termux). Claude Code lädt sie automatisch aus diesem
Verzeichnis; mit `/agents` lassen sie sich auflisten.

| Agent | Expertise | Ursprung |
|---|---|---|
| `express-experte` | Routen, Middleware, Fehlerbehandlung | `express-expert` (0xfurai) |
| `datenbank-optimierer` | SQLite: Abfragen, Indizes, Schema | `sqlite-expert` (0xfurai) |
| `shell-experte` | Bash-Skripte, Termux-Alltag | `bash-expert` (0xfurai) |
| `code-inspektor` | Review auf Korrektheit und Sicherheit | `code-reviewer` (VoltAgent) |

Die Namen sind ASCII gehalten — sie dienen als Bezeichner, mit denen die
Agents aufgerufen werden. In den Beschreibungen sind Umlaute unproblematisch.

## Anpassungen gegenüber den Originalen

**Namen und Beschreibungen** sind eingedeutscht. Die Beschreibung entscheidet
mit, wann Claude einen Agent von sich aus heranzieht — sie nennt deshalb
jeweils auch den Anlass („Einsetzen bei …").

**Modell-ID.** Die drei Agents von
[0xfurai](https://github.com/0xfurai/claude-code-subagents) pinnten alle die
feste ID `claude-sonnet-4-20250514`. Das ist hier auf `sonnet` geändert, damit
sie nicht auf einem veralteten Modell festhängen.

**Werkzeuge.** Dieselben drei deklarierten kein `tools`-Feld. Ergänzt um
`Read, Write, Edit, Bash, Glob, Grep`.

`code-inspektor` von
[VoltAgent](https://github.com/VoltAgent/awesome-claude-code-subagents) ist
inhaltlich unverändert; dort war nur der Name anzupassen. Beide Sammlungen
stehen unter MIT-Lizenz.

## Warum nur vier

Die beiden Sammlungen enthalten zusammen über 300 Agents — für Kubernetes,
Rust, Unreal Engine und vieles mehr. Für dieses Projekt sind die irrelevant
und machen die Auswahl nur unübersichtlich. Weitere lassen sich jederzeit
einzeln nachziehen.

Ein Hinweis zur Vorsicht: Subagents sind Anweisungstexte, die Claude steuern.
Diese vier wurden gelesen und sind unbedenklich. Für ungeprüfte Agents aus
fremden Sammlungen gilt das nicht automatisch.
