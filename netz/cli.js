#!/usr/bin/env node
'use strict';

/**
 * Netzdoktor -- Kommandozeile.
 *
 *   node netz/cli.js                     Analyse
 *   node netz/cli.js reparieren          Simulation der Reparaturen
 *   node netz/cli.js reparieren --anwenden --ja
 *   node netz/cli.js beobachten --intervall 60
 */

const analyseModul = require('./analyse');
const berichtModul = require('./bericht');
const reparaturModul = require('./reparaturen');
const konfig = require('./konfig');

const HILFE = `
Netzdoktor – Netzwerkanalyse und -reparatur

Aufruf:
  node netz/cli.js [befehl] [optionen]

Befehle:
  analyse                Alle Prüfungen ausführen (Vorgabe)
  reparieren             Reparaturen simulieren oder anwenden
  reparaturen            Verfügbare Maßnahmen auflisten
  beobachten             Analyse in festem Abstand wiederholen
  konfig                 Gespeicherte Einstellungen anzeigen
  konfig-zuruecksetzen   Gespeicherte Einstellungen löschen
  hilfe                  Diese Übersicht

Optionen:
  --json                 Ausgabe als JSON (für Skripte)
  --ausfuehrlich         Zusätzlich die Rohdaten je Prüfung
  --port <nummer>        Port der eigenen Anwendung (Vorgabe: 3000 bzw. $PORT)
  --messungen <anzahl>   Anzahl der Latenzmessungen (Vorgabe: 4)
  --nur <id,id>          Nur diese Prüfungen bzw. Reparaturen ausführen
  --anwenden             Reparaturen wirklich durchführen (sonst Simulation)
  --ja                   Auch als riskant eingestufte Maßnahmen zulassen
  --intervall <sekunden> Abstand bei "beobachten" (Vorgabe: 60)

Rückgabewerte: 0 = alles in Ordnung, 1 = Warnungen, 2 = Fehler
`;

function leseArgumente(argv) {
  const optionen = { befehl: 'analyse', json: false, ausfuehrlich: false, anwenden: false, ja: false, intervall: 60 };
  const rest = [...argv];

  if (rest[0] && !rest[0].startsWith('--')) optionen.befehl = rest.shift();

  while (rest.length > 0) {
    const teil = rest.shift();
    switch (teil) {
      case '--json': optionen.json = true; break;
      case '--ausfuehrlich': optionen.ausfuehrlich = true; break;
      case '--anwenden': optionen.anwenden = true; break;
      case '--ja': optionen.ja = true; break;
      case '--port': optionen.port = Number(rest.shift()); break;
      case '--messungen': optionen.messungen = Number(rest.shift()); break;
      case '--intervall': optionen.intervall = Number(rest.shift()); break;
      case '--nur': optionen.nur = String(rest.shift() || '').split(',').map((s) => s.trim()).filter(Boolean); break;
      case '--hilfe':
      case '-h': optionen.befehl = 'hilfe'; break;
      default:
        console.error(`Unbekannte Option: ${teil}`);
        process.exit(64);
    }
  }
  return optionen;
}

function abschlusscode(bericht) {
  if (bericht.gesamtstatus === 'fehler') return 2;
  if (bericht.gesamtstatus === 'warnung') return 1;
  return 0;
}

/** Analyseoptionen aus den CLI-Argumenten ableiten. */
function analyseOptionen(optionen, { mitFortschritt }) {
  return {
    ...(optionen.port ? { port: optionen.port } : {}),
    ...(optionen.messungen ? { messungen: optionen.messungen } : {}),
    ...(optionen.befehl === 'analyse' && optionen.nur ? { nur: optionen.nur } : {}),
    ...(mitFortschritt
      ? { beiFortschritt: (eintrag) => console.log(berichtModul.pruefzeile(eintrag)) }
      : {}),
  };
}

async function befehlAnalyse(optionen) {
  const liveAusgabe = !optionen.json;
  if (liveAusgabe) console.log(berichtModul.kopfzeile('Netzdoktor – Analyse läuft'));

  const bericht = await analyseModul.analysiere(analyseOptionen(optionen, { mitFortschritt: false }));

  if (optionen.json) {
    const { _ktx, ...sauber } = bericht;
    console.log(JSON.stringify(sauber, null, 2));
  } else {
    console.log(berichtModul.formatiereBericht(bericht, { ausfuehrlich: optionen.ausfuehrlich }));
  }
  return abschlusscode(bericht);
}

async function befehlReparieren(optionen) {
  if (!optionen.json) console.log(berichtModul.kopfzeile('Netzdoktor – Analyse vor der Reparatur'));

  const bericht = await analyseModul.analysiere(analyseOptionen(optionen, { mitFortschritt: false }));
  const ergebnis = await analyseModul.repariere(bericht, {
    anwenden: optionen.anwenden,
    bestaetigt: optionen.ja,
    nur: optionen.nur || null,
  });

  if (optionen.json) {
    console.log(JSON.stringify(ergebnis, null, 2));
    return ergebnis.ergebnisse.some((e) => e.ausgefuehrt && !e.erfolg) ? 2 : 0;
  }

  console.log(berichtModul.formatiereReparaturen(ergebnis));

  if (optionen.anwenden) {
    console.log(berichtModul.kopfzeile('Kontrolle nach der Reparatur'));
    const nachher = await analyseModul.analysiere(analyseOptionen(optionen, { mitFortschritt: false }));
    console.log(berichtModul.formatiereBericht(nachher));
    return abschlusscode(nachher);
  }
  return 0;
}

function befehlReparaturen(optionen) {
  if (optionen.json) {
    console.log(JSON.stringify(reparaturModul.reparaturen.map(({ id, titel, beschreibung, risiko, bestaetigungNoetig }) => ({ id, titel, beschreibung, risiko, bestaetigungNoetig })), null, 2));
    return 0;
  }

  console.log(berichtModul.kopfzeile('Verfügbare Reparaturen'));
  for (const reparatur of reparaturModul.reparaturen) {
    const zusatz = reparatur.bestaetigungNoetig ? ', Bestätigung nötig' : '';
    console.log(`  ${berichtModul.farben.blau(reparatur.id)} ${berichtModul.farben.grau(`[Risiko: ${reparatur.risiko}${zusatz}]`)}`);
    console.log(`     ${reparatur.beschreibung}`);
  }
  console.log('');
  return 0;
}

async function befehlBeobachten(optionen) {
  const abstand = Math.max(5, optionen.intervall) * 1000;
  console.log(`Netzdoktor beobachtet das Netz alle ${abstand / 1000} s. Abbruch mit Strg+C.\n`);

  // Endlosschleife statt setInterval: so ueberlappen sich langsame Laeufe nicht.
  for (;;) {
    const bericht = await analyseModul.analysiere(analyseOptionen(optionen, { mitFortschritt: false }));
    const zeit = new Date().toLocaleTimeString('de-DE');
    const gesamt = berichtModul.symbol(bericht.gesamtstatus);
    const z = bericht.zusammenfassung;
    console.log(`[${zeit}] ${gesamt} ${bericht.diagnose.ebene}: ${bericht.diagnose.text} ${berichtModul.farben.grau(`(${z.ok}/${z.gesamt} ok, ${bericht.dauerMs} ms)`)}`);
    await new Promise((aufloesen) => setTimeout(aufloesen, abstand));
  }
}

function befehlKonfig(optionen) {
  const aktuell = konfig.lade();
  if (optionen.json) {
    console.log(JSON.stringify(aktuell, null, 2));
    return 0;
  }
  console.log(berichtModul.kopfzeile('Gespeicherte Einstellungen'));
  console.log(`  Datei: ${konfig.KONFIG_DATEI}`);
  console.log(`  DNS-Server:          ${aktuell.dnsServer ? aktuell.dnsServer.join(', ') : 'System'}`);
  console.log(`  Adressreihenfolge:   ${aktuell.ergebnisReihenfolge || 'System'}`);
  console.log(`  Proxy bereinigt:     ${aktuell.proxyBereinigt ? 'ja' : 'nein'}\n`);
  return 0;
}

async function haupt(argv) {
  const optionen = leseArgumente(argv);

  switch (optionen.befehl) {
    case 'analyse': return befehlAnalyse(optionen);
    case 'reparieren': return befehlReparieren(optionen);
    case 'reparaturen': return befehlReparaturen(optionen);
    case 'beobachten': return befehlBeobachten(optionen);
    case 'konfig': return befehlKonfig(optionen);
    case 'konfig-zuruecksetzen': {
      const geloescht = konfig.zuruecksetzen();
      console.log(geloescht ? 'Einstellungen gelöscht.' : 'Es waren keine Einstellungen gespeichert.');
      return 0;
    }
    case 'hilfe': console.log(HILFE); return 0;
    default:
      console.error(`Unbekannter Befehl: ${optionen.befehl}`);
      console.log(HILFE);
      return 64;
  }
}

if (require.main === module) {
  haupt(process.argv.slice(2))
    .then((code) => { process.exitCode = code; })
    .catch((fehler) => {
      console.error(`Netzdoktor abgebrochen: ${fehler.message}`);
      process.exitCode = 70;
    });
}

module.exports = { haupt, leseArgumente };
