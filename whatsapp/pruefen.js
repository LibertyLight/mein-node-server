#!/usr/bin/env node
'use strict';

/**
 * Einrichtung pruefen, ohne auf eine echte WhatsApp-Nachricht zu warten.
 *
 *   node whatsapp/pruefen.js              # nur Konfiguration und Claude testen
 *   node whatsapp/pruefen.js 4917012345   # zusaetzlich eine Testnachricht schicken
 *
 * Nuetzlich, weil sich bei Meta zwei Dinge leicht verwechseln lassen: die
 * Telefonnummern-ID (WHATSAPP_TELEFON_ID) und die eigentliche Nummer.
 */

const konfigModul = require('./konfig');
const claudeModul = require('./claude');
const versandModul = require('./versand');

async function haupt() {
  const ziel = process.argv[2];
  const konfig = konfigModul.lade();
  const fehlt = konfigModul.fehlendeAngaben(konfig);

  console.log('Konfiguration:');
  console.log(`  Modell:              ${konfig.modell} (Aufwand: ${konfig.aufwand})`);
  console.log(`  Graph-Version:       ${konfig.graphVersion}`);
  console.log(`  Telefonnummern-ID:   ${konfig.telefonId || '(fehlt)'}`);
  console.log(
    `  Freigegebene Nummern: ${konfig.alleErlaubt ? 'alle' : konfig.erlaubteNummern.join(', ') || '(keine)'}`,
  );
  console.log(
    `  Sprachnachrichten:   ${konfig.transkription ? `${konfig.whisperModell} über ${konfig.whisperUrl}` : 'aus'}`,
  );

  if (fehlt.length > 0) {
    console.error(`\nEs fehlen: ${fehlt.join(', ')}`);
    console.error('Einrichtung: siehe WHATSAPP.md');
    process.exitCode = 1;
    return;
  }

  console.log('\nFrage Claude …');
  try {
    const claude = claudeModul.erstelleClaude(konfig);
    const antwort = await claude.antworte([
      { role: 'user', content: 'Antworte in genau einem kurzen Satz, dass die Verbindung steht.' },
    ]);
    console.log(`  Claude: ${antwort}`);
  } catch (fehler) {
    console.error(`  Fehlgeschlagen: ${fehler.message}`);
    process.exitCode = 1;
    return;
  }

  if (!ziel) {
    console.log('\nKeine Zielnummer angegeben – Versand übersprungen.');
    return;
  }

  const nummer = konfigModul.normalisiereNummer(ziel);
  if (!konfigModul.istFreigegeben(konfig, nummer)) {
    console.error(`\n${nummer} steht nicht in WHATSAPP_ERLAUBTE_NUMMERN.`);
    process.exitCode = 1;
    return;
  }

  console.log(`\nSende Testnachricht an ${nummer} …`);
  try {
    const [ergebnis] = await versandModul.sendeText(
      konfig,
      nummer,
      'Testnachricht vom Claude-Bot. Die Einrichtung funktioniert.',
    );
    console.log(`  Versendet: ${ergebnis?.messages?.[0]?.id ?? 'ok'}`);
    console.log(
      '\nHinweis: Außerhalb des 24-Stunden-Fensters nimmt Meta nur Vorlagen an –' +
        ' schreib dem Bot zuerst selbst, dann klappt der Versand.',
    );
  } catch (fehler) {
    console.error(`  Fehlgeschlagen: ${fehler.message}`);
    process.exitCode = 1;
  }
}

haupt();
