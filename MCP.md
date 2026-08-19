# GitHub MCP Server

Dieses Projekt ist für den entfernten GitHub MCP Server konfiguriert
(`https://api.githubcopilot.com/mcp/`). Die Konfiguration liegt in `.mcp.json`
und gilt projektweit — jeder MCP-Client, der `.mcp.json` liest (z. B. Claude
Code), findet den Server automatisch.

## Einrichtung

1. Personal Access Token auf GitHub erstellen:
   <https://github.com/settings/personal-access-tokens>
   Benötigte Berechtigungen richten sich nach den Tools, die genutzt werden
   sollen (z. B. `repo` bzw. Repository-Lese-/Schreibrechte für Issues und
   Pull Requests).

2. Token als Umgebungsvariable setzen — **nicht** in `.mcp.json` eintragen,
   die Datei liegt im Repository:

   ```bash
   export GITHUB_PERSONAL_ACCESS_TOKEN=ghp_dein_token
   ```

   Für dauerhafte Nutzung in `~/.bashrc` bzw. `~/.zshrc` ablegen.

3. Client neu starten. In Claude Code lässt sich der Status mit `/mcp` prüfen.

## Varianten des Endpunkts

| URL | Bedeutung |
| --- | --- |
| `https://api.githubcopilot.com/mcp/` | Alle Tools (Standard) |
| `https://api.githubcopilot.com/mcp/readonly` | Nur lesende Tools |
| `https://api.githubcopilot.com/mcp/x/issues` | Nur ein Toolset, hier Issues |
| `https://api.githubcopilot.com/mcp/x/pull_requests` | Nur Pull Requests |

Um eine Variante zu verwenden, einfach die `url` in `.mcp.json` anpassen.

## Alternative: OAuth statt Token

Clients mit OAuth-Unterstützung können den Server auch ohne PAT nutzen. Dann
entfällt der `headers`-Block in `.mcp.json`, und die Anmeldung erfolgt beim
ersten Verbinden im Browser.
