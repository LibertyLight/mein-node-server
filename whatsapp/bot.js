'use strict';

/**
 * Der eigentliche Ablauf: eingehende WhatsApp-Nachricht -> Claude -> Antwort.
 *
 * Alles laeuft nach der Webhook-Antwort weiter. Meta erwartet innerhalb
 * weniger Sekunden ein 200 und wiederholt die Zustellung sonst -- Claude
 * braucht laenger als das. Die Route quittiert deshalb sofort und uebergibt
 * hierher.
 *
 * Sprachnachrichten nehmen denselben Weg: sie werden vorher zu Text gemacht
 * (siehe whisper.js) und sind ab da nicht mehr von getippten zu unterscheiden.
 *
 * Bilder gehen dagegen unveraendert an Claude -- sehen kann er selbst, dafuer
 * braucht es keinen zweiten Dienst.
 */

const nachrichtenModul = require('./nachrichten');
const konfigModul = require('./konfig');
const claudeModul = require('./claude');
const versandModul = require('./versand');
const medienModul = require('./medien');

const HILFE = [
  'Ich bin Claude auf WhatsApp. Schreib mir einfach – oder schick eine Sprachnachricht.',
  '',
  'Befehle:',
  '/neu – Gesprächsverlauf vergessen und neu anfangen',
  '/hilfe – diese Übersicht',
].join('\n');

const NUR_TEXT =
  'Ich kann Text, Sprachnachrichten und Bilder lesen – Videos, Sticker und Dateien noch nicht.';
const OHNE_WHISPER =
  'Sprachnachrichten kann ich gerade nicht anhören – dafür ist die Transkription nicht eingerichtet.';
const NICHTS_GEHOERT = 'In der Sprachnachricht war für mich nichts zu verstehen.';
const AUDIO_ZU_GROSS = 'Die Sprachnachricht ist zu lang für mich. Schick sie bitte in kürzeren Stücken.';
const AUDIO_FORMAT = 'Dieses Audioformat kann ich leider nicht lesen.';
const AUDIO_FEHLER =
  'Ich konnte die Sprachnachricht nicht verarbeiten. Versuch es bitte noch einmal – oder schreib mir.';
const BILD_ZU_GROSS = 'Das Bild ist zu groß für mich. Schick es bitte etwas kleiner.';
const BILD_FORMAT = 'Dieses Bildformat kann ich nicht lesen – JPEG, PNG, GIF und WebP gehen.';
const BILD_FEHLER = 'Ich konnte das Bild nicht laden. Versuch es bitte noch einmal.';

/** Formate, die Claude ansehen kann. Bewegte Bilder werden zum ersten Einzelbild. */
const BILD_TYPEN = new Set(['image/jpeg', 'image/png', 'image/gif', 'image/webp']);

/** Hoechstens so viel Transkript wird der Antwort vorangestellt. */
const TRANSKRIPT_VORSCHAU = 300;

function transkriptZeile(transkript) {
  const einzeilig = transkript.replace(/\s+/g, ' ').trim();
  const gekuerzt =
    einzeilig.length > TRANSKRIPT_VORSCHAU
      ? `${einzeilig.slice(0, TRANSKRIPT_VORSCHAU).trim()}…`
      : einzeilig;
  return `🎙 _${gekuerzt}_`;
}

function erstelleBot({
  konfig,
  verlauf,
  claude,
  whisper = null,
  medien = medienModul,
  versand = versandModul,
  protokoll = console,
}) {
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

  /**
   * Sprachnachricht zu Text machen.
   *
   * Gibt null zurueck, wenn das nicht geklappt hat -- der Absender hat dann
   * bereits einen passenden Hinweis bekommen.
   */
  async function transkribiere(ereignis) {
    const nummer = ereignis.von;

    if (!whisper) {
      await antworteMit(nummer, OHNE_WHISPER);
      return null;
    }

    try {
      const datei = await medien.hole(konfig, ereignis.medien.id, { maxBytes: konfig.maxAudioBytes });

      // Eine stille Aufnahme ergibt leeren oder reinen Leerraum-Text. Der darf
      // nicht in den Verlauf: die API lehnt leere Nachrichten ab.
      const transkript = String((await whisper.transkribiere(datei)) ?? '').trim();

      if (!transkript) {
        await antworteMit(nummer, NICHTS_GEHOERT);
        return null;
      }

      protokoll.log(`[whatsapp] Sprachnachricht ${ereignis.id} transkribiert (${datei.groesse} Bytes).`);
      return transkript;
    } catch (fehler) {
      protokoll.error(`[whatsapp] Sprachnachricht ${ereignis.id} fehlgeschlagen: ${fehler.message}`);

      if (fehler.grund === 'zu-gross') await antworteMit(nummer, AUDIO_ZU_GROSS);
      else if (fehler.grund === 'format') await antworteMit(nummer, AUDIO_FORMAT);
      else await antworteMit(nummer, AUDIO_FEHLER);

      return null;
    }
  }

  /**
   * Bild herunterladen. Gibt null zurueck, wenn das nicht geklappt hat --
   * der Absender hat dann bereits einen Hinweis bekommen.
   */
  async function holeBild(ereignis) {
    const nummer = ereignis.von;

    if (!BILD_TYPEN.has(ereignis.medien.mimeTyp)) {
      await antworteMit(nummer, BILD_FORMAT);
      return null;
    }

    try {
      const bild = await medien.hole(konfig, ereignis.medien.id, { maxBytes: konfig.maxBildBytes });
      protokoll.log(`[whatsapp] Bild ${ereignis.id} geladen (${bild.groesse} Bytes).`);
      return bild;
    } catch (fehler) {
      protokoll.error(`[whatsapp] Bild ${ereignis.id} fehlgeschlagen: ${fehler.message}`);
      await antworteMit(nummer, fehler.grund === 'zu-gross' ? BILD_ZU_GROSS : BILD_FEHLER);
      return null;
    }
  }

  /** Claude fragen und die Antwort verschicken. */
  async function beantworte(nummer, text, transkript, bild = null) {
    verlauf.anhaengen(nummer, 'user', text, bild);

    let antwort;
    try {
      antwort = await claudeDienst.antworte(verlauf.holen(nummer));
    } catch (fehler) {
      protokoll.error(`[whatsapp] Claude-Anfrage fehlgeschlagen: ${fehler.message}`);
      await antworteMit(nummer, claudeModul.fehlerText(fehler));
      return;
    }

    verlauf.anhaengen(nummer, 'assistant', antwort);

    // Bei Sprachnachrichten zeigt die erste Zeile, was verstanden wurde --
    // sonst raetselt der Absender bei einer unpassenden Antwort.
    const ausgabe =
      transkript && konfig.transkriptZeigen ? `${transkriptZeile(transkript)}\n\n${antwort}` : antwort;

    await antworteMit(nummer, ausgabe);
  }

  async function behandle(ereignis) {
    const nummer = ereignis.von;
    let text = ereignis.text;
    let transkript = null;
    let bild = null;

    if (ereignis.typ === 'audio' && ereignis.medien) {
      transkript = await transkribiere(ereignis);
      if (!transkript) return;
      text = transkript;
    }

    // Steht in der Bildunterschrift ein Befehl, ist das Bild nicht gemeint --
    // dann gar nicht erst herunterladen.
    if (ereignis.typ === 'image' && ereignis.medien && !nachrichtenModul.erkenneBefehl(text)) {
      bild = await holeBild(ereignis);
      if (!bild) return;
      // Ein Bild ohne Bildunterschrift ist als Frage gemeint.
      text = text || konfig.bildFrage;
    }

    if (!text) {
      await antworteMit(nummer, NUR_TEXT);
      return;
    }

    const befehl = nachrichtenModul.erkenneBefehl(text);
    if (befehl === 'hilfe') {
      await antworteMit(nummer, HILFE);
      return;
    }
    if (befehl === 'neu') {
      const geloescht = verlauf.leeren(nummer);
      await antworteMit(nummer, `Alles klar, ich habe ${geloescht} Nachricht(en) vergessen. Worum geht es?`);
      return;
    }

    await beantworte(nummer, text, transkript, bild);
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

module.exports = {
  erstelleBot,
  transkriptZeile,
  BILD_TYPEN,
  HILFE,
  NUR_TEXT,
  BILD_ZU_GROSS,
  BILD_FORMAT,
  BILD_FEHLER,
  OHNE_WHISPER,
  NICHTS_GEHOERT,
  AUDIO_ZU_GROSS,
  AUDIO_FORMAT,
  AUDIO_FEHLER,
};
