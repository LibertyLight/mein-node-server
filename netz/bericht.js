'use strict';

/** Aufbereitung der Analyseergebnisse fuer das Terminal. */

const ESC = String.fromCharCode(27);

// Farben nur, wenn wirklich ein Terminal dranhaengt (Pipes bleiben sauber).
const farbig = Boolean(process.stdout.isTTY) && !process.env.NO_COLOR;
const f = (code) => (text) => (farbig ? `${ESC}[${code}m${text}${ESC}[0m` : String(text));

const grau = f('90');
const fett = f('1');
const gruen = f('32');
const gelb = f('33');
const rot = f('31');
const blau = f('36');

const SYMBOLE = {
  ok: { zeichen: '✓', farbe: gruen, wort: 'OK' },
  warnung: { zeichen: '!', farbe: gelb, wort: 'WARNUNG' },
  fehler: { zeichen: '✗', farbe: rot, wort: 'FEHLER' },
  uebersprungen: { zeichen: '–', farbe: grau, wort: 'ÜBERSPRUNGEN' },
  info: { zeichen: 'i', farbe: blau, wort: 'INFO' },
};

function symbol(status) {
  const eintrag = SYMBOLE[status] || SYMBOLE.info;
  return eintrag.farbe(eintrag.zeichen);
}

function kopfzeile(text) {
  return `\n${fett(text)}\n${grau('─'.repeat(Math.max(text.length, 20)))}`;
}

/** Eine einzelne Pruefzeile, wie sie waehrend des Laufs erscheint. */
function pruefzeile(eintrag) {
  const dauer = grau(`${eintrag.dauerMs} ms`);
  return `  ${symbol(eintrag.status)} ${fett(eintrag.titel)} ${dauer}\n     ${eintrag.meldung}`;
}

function formatiereBericht(bericht, { ausfuehrlich = false } = {}) {
  const zeilen = [];
  const gruppen = new Map();

  for (const eintrag of bericht.pruefungen) {
    if (!gruppen.has(eintrag.gruppe)) gruppen.set(eintrag.gruppe, []);
    gruppen.get(eintrag.gruppe).push(eintrag);
  }

  for (const [gruppe, eintraege] of gruppen) {
    zeilen.push(kopfzeile(gruppe));
    for (const eintrag of eintraege) {
      zeilen.push(pruefzeile(eintrag));
      if (ausfuehrlich && Object.keys(eintrag.details || {}).length > 0) {
        zeilen.push(grau(`     ${JSON.stringify(eintrag.details)}`));
      }
    }
  }

  const z = bericht.zusammenfassung;
  const gesamt = SYMBOLE[bericht.gesamtstatus] || SYMBOLE.info;

  zeilen.push(kopfzeile('Diagnose'));
  zeilen.push(
    `  Gesamtstatus: ${gesamt.farbe(gesamt.wort)}  ` +
      grau(`(${z.ok} ok, ${z.warnungen} Warnungen, ${z.fehler} Fehler, ${z.uebersprungen} übersprungen)`),
  );
  zeilen.push(`  Ebene:        ${fett(bericht.diagnose.ebene)}`);
  zeilen.push(`  ${bericht.diagnose.text}`);

  const anwendbar = bericht.reparaturvorschlaege.filter((v) => v.anwendbar);
  if (anwendbar.length > 0) {
    zeilen.push(kopfzeile('Mögliche Reparaturen'));
    for (const vorschlag of anwendbar) {
      const risikoFarbe = { hoch: rot, mittel: gelb, niedrig: gruen }[vorschlag.risiko] || grau;
      const zusatz = vorschlag.bestaetigungNoetig ? ', Bestätigung nötig' : '';
      zeilen.push(`  ${blau(vorschlag.id)} ${grau('[Risiko: ')}${risikoFarbe(vorschlag.risiko)}${grau(`${zusatz}]`)}`);
      zeilen.push(`     ${vorschlag.titel} – ${vorschlag.grund}`);
    }
    const jaNoetig = anwendbar.some((v) => v.bestaetigungNoetig) ? ' --ja' : '';
    zeilen.push(`\n  ${grau('Simulation:')} npm run netz:reparieren`);
    zeilen.push(`  ${grau('Anwenden:  ')} npm run netz:reparieren -- --anwenden${jaNoetig}`);
  } else if (bericht.gesamtstatus !== 'ok') {
    zeilen.push(kopfzeile('Mögliche Reparaturen'));
    zeilen.push(`  ${grau('Für die gefundenen Probleme gibt es keine automatische Reparatur.')}`);
  }

  zeilen.push('');
  return zeilen.join('\n');
}

function formatiereReparaturen(ergebnis) {
  const zeilen = [kopfzeile(ergebnis.simuliert ? 'Reparatur (Simulation)' : 'Reparatur')];

  if (ergebnis.ergebnisse.length === 0) {
    zeilen.push(`  ${grau('Keine passende Maßnahme gefunden.')}`);
    zeilen.push('');
    return zeilen.join('\n');
  }

  for (const eintrag of ergebnis.ergebnisse) {
    if (eintrag.uebersprungen) {
      zeilen.push(`  ${symbol('uebersprungen')} ${fett(eintrag.id)}: ${eintrag.meldung}`);
      continue;
    }
    zeilen.push(`  ${symbol(eintrag.erfolg ? 'ok' : 'fehler')} ${fett(eintrag.id)}: ${eintrag.meldung}`);
    for (const schritt of eintrag.schritte || []) {
      zeilen.push(grau(`       · ${schritt}`));
    }
  }

  if (ergebnis.simuliert) {
    zeilen.push(`\n  ${grau('Nichts wurde verändert. Zum Anwenden: --anwenden')}`);
  }
  zeilen.push('');
  return zeilen.join('\n');
}

module.exports = {
  formatiereBericht,
  formatiereReparaturen,
  pruefzeile,
  kopfzeile,
  symbol,
  farben: { grau, fett, gruen, gelb, rot, blau },
};
