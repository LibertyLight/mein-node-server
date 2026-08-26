'use strict';

/**
 * Sprachnachrichten in Text umwandeln.
 *
 * Angesprochen wird die uebliche Whisper-Schnittstelle
 * (POST /audio/transcriptions, multipart). Ueber WHISPER_URL laesst sich
 * statt des gehosteten Dienstes ein lokaler Whisper-Server eintragen, der
 * dieselbe Schnittstelle anbietet -- der Rest des Codes bleibt gleich.
 */

/**
 * Die Schnittstelle erkennt das Format an der Dateiendung, nicht am MIME-Typ.
 * WhatsApp-Sprachnachrichten sind immer Opus in einem Ogg-Behaelter.
 */
const ENDUNGEN = {
  'audio/ogg': 'ogg',
  'audio/oga': 'oga',
  'audio/opus': 'ogg',
  'audio/mpeg': 'mp3',
  'audio/mp3': 'mp3',
  'audio/mp4': 'm4a',
  'audio/m4a': 'm4a',
  'audio/x-m4a': 'm4a',
  'audio/aac': 'm4a',
  'audio/wav': 'wav',
  'audio/x-wav': 'wav',
  'audio/webm': 'webm',
  'audio/flac': 'flac',
};

/** Formate, die Whisper nicht annimmt -- AMR kommt von aelteren Telefonen. */
const UNBEKANNT = 'unbekannt';

function endung(mimeTyp) {
  return ENDUNGEN[String(mimeTyp || '').toLowerCase()] || UNBEKANNT;
}

class WhisperFehler extends Error {
  constructor(nachricht, grund) {
    super(nachricht);
    this.name = 'WhisperFehler';
    this.grund = grund; // 'format' | 'dienst'
  }
}

function erstelleWhisper(konfig, { fetchImpl = fetch } = {}) {
  const basis = String(konfig.whisperUrl).replace(/\/+$/, '');

  async function transkribiere({ daten, mimeTyp }) {
    const dateiEndung = endung(mimeTyp);
    if (dateiEndung === UNBEKANNT) {
      throw new WhisperFehler(`Nicht unterstütztes Audioformat: ${mimeTyp}`, 'format');
    }

    const formular = new FormData();
    formular.append('model', konfig.whisperModell);
    formular.append('response_format', 'json');
    // Ohne Sprachangabe erkennt Whisper sie selbst; das kostet etwas Genauigkeit
    // bei kurzen Aufnahmen, hilft aber bei Sprachwechseln.
    if (konfig.whisperSprache) formular.append('language', konfig.whisperSprache);
    formular.append(
      'file',
      new Blob([daten], { type: mimeTyp || 'application/octet-stream' }),
      `sprachnachricht.${dateiEndung}`,
    );

    const kopfzeilen = {};
    // Ein lokaler Whisper-Server braucht in der Regel keinen Schlüssel.
    if (konfig.whisperSchluessel) kopfzeilen.Authorization = `Bearer ${konfig.whisperSchluessel}`;

    const antwort = await fetchImpl(`${basis}/audio/transcriptions`, {
      method: 'POST',
      headers: kopfzeilen,
      body: formular,
    });

    if (!antwort.ok) {
      const text = await antwort.text().catch(() => '');
      throw new WhisperFehler(`Transkription fehlgeschlagen (${antwort.status}): ${text}`, 'dienst');
    }

    const ergebnis = await antwort.json();
    return String(ergebnis?.text ?? '').trim();
  }

  return { transkribiere };
}

module.exports = { erstelleWhisper, WhisperFehler, endung, ENDUNGEN };
