# Subagents

Vier Subagents, ausgewählt für den Stack dieses Projekts (Express, natives
`node:sqlite`, Termux). Claude Code lädt sie automatisch aus diesem
Verzeichnis; mit `/agents` lassen sie sich auflisten.

| Agent | Wofür | Quelle |
|---|---|---|
| `express-expert` | Routen, Middleware, Fehlerbehandlung | 0xfurai |
| `sqlite-expert` | Abfragen, Indizes, Schema | 0xfurai |
| `bash-expert` | Shell-Skripte, Termux-Alltag | 0xfurai |
| `code-reviewer` | Review auf Korrektheit und Sicherheit | VoltAgent |

## Anpassungen gegenüber den Originalen

Die drei Agents von [0xfurai](https://github.com/0xfurai/claude-code-subagents)
pinnten alle die feste Modell-ID `claude-sonnet-4-20250514` und deklarierten
kein `tools`-Feld. Beides ist hier korrigiert: `model: sonnet` und eine
explizite Werkzeugliste.

`code-reviewer` von
[VoltAgent](https://github.com/VoltAgent/awesome-claude-code-subagents) ist
unverändert. Beide Sammlungen stehen unter MIT-Lizenz.

## Warum nur vier

Die beiden Sammlungen enthalten zusammen über 300 Agents — für Kubernetes,
Rust, Unreal Engine und vieles mehr. Für dieses Projekt sind die irrelevant
und machen die Auswahl nur unübersichtlich. Weitere lassen sich jederzeit
einzeln nachziehen.

Ein Hinweis zur Vorsicht: Subagents sind Anweisungstexte, die Claude steuern.
Diese vier wurden gelesen und sind unbedenklich. Für ungeprüfte Agents aus
fremden Sammlungen gilt das nicht automatisch.
