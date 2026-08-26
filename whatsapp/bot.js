'use strict';

/**
 * Der eigentliche Ablauf: eingehende WhatsApp-Nachricht -> Claude -> Antwort.
 *
 * Alles laeuft nach der Webhook-Antwort weiter. Meta erwartet innerhalb
 * weniger Sekunden ein 200 und wiederholt die Zustellung sonst -- Claude
 * braucht laenger als das. Die Route quittiert deshalb sofort und uebergibt
 * hierher.
 */

const nachrichtenModul = require('./nachrichten');
const konfigModul = require('./konfig');
const claudeModul = require('./claude');
const versandModul = require('./versand');

const HILFE = [
  'Ich bin Claude auf WhatsApp. Schreib mir einfach.',
  '',
  'Befehle:',
  '/neu – Gesprächsverlauf vergessen und neu anfangen',
  '/hilfe – diese Übersicht',
].join('\n');

const NUR_TEXT = 'Ich kann im Moment nur Text lesen – Bilder, Sprachnachrichten und Dateien noch nicht.';

function erstelleBot({ konfig, verlauf, claude, versand = versandModul, protokoll = console }) {
  const claudeDienst = claude || claudeModul.erstelleClaude(konfig);

  // Je Nummer eine Warteschlange: schreibt jemand zweimal schnell
  // hintereinander, wuerde sonst die zweite Anfrage einen Verlauf sehen, in
  // dem die erste Antwort noch fehlt.
  const warteschlangen = new Map();

  function inWarteschlange(nummer, aufgabe) {
    const vorher = warteschlangen.get(nummer) || Promise.resolve();
    const laufend = vorher.then(aufgabe, aufgabe);
    warteschlangen.set(
      nummer,
      laufend.catch(() => {}).finally(() => {
        // Nur aufraeumen, wenn seither nichts Neues dazugekommen ist.
        if (warteschlangen.get(nummer) === laufend) warteschlangen.delete(nummer);
      }),
    );
    return laufend;
  }

  async function antworteMit(nummer, text) {
    await versand.sendeText(konfig, nummer, text);
  }

  async function behandle(ereignis) {
    const nummer = ereignis.von;

    if (ereignis.typ !== 'text' && !ereignis.text) {
      await antworteMit(nummer, NUR_TEXT);
      return;
    }

    const befehl = nachrichtenModul.erkenneBefehl(ereignis.text);
    if (befehl === 'hilfe') {
      await antworteMit(nummer, HILFE);
      return;
    }
    if (befehl === 'neu') {
      const geloescht = verlauf.leeren(nummer);
      await antworteMit(nummer, `Alles klar, ich habe ${geloescht} Nachricht(en) vergessen. Worum geht es?`);
      return;
    }

    verlauf.anhaengen(nummer, 'user', ereignis.text);

    let antwort;
    try {
      antwort = await claudeDienst.antworte(verlauf.holen(nummer));
    } catch (fehler) {
      protokoll.error(`[whatsapp] Claude-Anfrage fehlgeschlagen: ${fehler.message}`);
      await antworteMit(nummer, claudeModul.fehlerText(fehler));
      return;
    }

    verlauf.anhaengen(nummer, 'assistant', antwort);
    await antworteMit(nummer, antwort);
  }

  /** Eine einzelne Nachricht bearbeiten -- mit allen Vorpruefungen. */
  async function verarbeite(ereignis) {
    if (!konfigModul.istFreigegeben(konfig, ereignis.von)) {
      protokoll.warn(`[whatsapp] Nachricht von nicht freigegebener Nummer verworfen: ${ereignis.von}`);
      return 'gesperrt';
    }

    if (nachrichtenModul.istVeraltet(ereignis.zeitstempel)) {
      protokoll.warn(`[whatsapp] Veraltete Nachricht verworfen: ${ereignis.id}`);
      return 'veraltet';
    }

    if (verlauf.schonGesehen(ereignis.id)) {
      protokoll.log(`[whatsapp] Wiederholte Zustellung übersprungen: ${ereignis.id}`);
      return 'doppelt';
    }

    return inWarteschlange(ereignis.von, async () => {
      // Blaue Haken sind Beiwerk: scheitern sie, geht die Antwort trotzdem raus.
      try {
        await versand.markiereGelesen(konfig, ereignis.id);
      } catch (fehler) {
        protokoll.warn(`[whatsapp] Lesebestätigung fehlgeschlagen: ${fehler.message}`);
      }

      try {
        await behandle(ereignis);
        return 'beantwortet';
      } catch (fehler) {
        protokoll.error(`[whatsapp] Nachricht ${ereignis.id} fehlgeschlagen: ${fehler.message}`);
        return 'fehler';
      }
    });
  }

  /** Einen kompletten Webhook-Koerper abarbeiten. */
  async function verarbeiteKoerper(koerper) {
    const ereignisse = nachrichtenModul.extrahiereEreignisse(koerper);
    const ergebnisse = [];
    for (const ereignis of ereignisse) {
      // eslint-disable-next-line no-await-in-loop
      ergebnisse.push(await verarbeite(ereignis));
    }
    return ergebnisse;
  }

  return { verarbeite, verarbeiteKoerper, HILFE, NUR_TEXT };
}

module.exports = { erstelleBot, HILFE, NUR_TEXT };
