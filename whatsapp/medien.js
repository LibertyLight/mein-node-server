'use strict';

/**
 * Mediendateien von WhatsApp herunterladen.
 *
 * Der Webhook liefert nur eine Medien-ID, keine Datei. Der Abruf geht in zwei
 * Schritten: erst die Metadaten samt befristeter Adresse, dann die Datei selbst.
 * Auch der zweite Aufruf braucht den Zugriffstoken -- die Adresse allein
 * genuegt nicht.
 */

const VORGABE_MAX_BYTES = 20 * 1024 * 1024;

/** Kennzeichnet Faelle, die dem Absender erklaert werden koennen. */
class MedienFehler extends Error {
  constructor(nachricht, grund) {
    super(nachricht);
    this.name = 'MedienFehler';
    this.grund = grund; // 'zu-gross' | 'abruf'
  }
}

function kopfzeilen(konfig) {
  return {
    Authorization: `Bearer ${konfig.token}`,
    // Ohne erkennbaren Client liefert der Medienserver von Meta gelegentlich 400.
    'User-Agent': 'mein-node-server/1.0',
  };
}

/**
 * @returns {Promise<{daten: Buffer, mimeTyp: string, groesse: number}>}
 */
async function hole(konfig, medienId, { fetchImpl = fetch, maxBytes = VORGABE_MAX_BYTES } = {}) {
  const infoAntwort = await fetchImpl(
    `https://graph.facebook.com/${konfig.graphVersion}/${encodeURIComponent(medienId)}`,
    { headers: kopfzeilen(konfig) },
  );

  if (!infoAntwort.ok) {
    const text = await infoAntwort.text().catch(() => '');
    throw new MedienFehler(`Medien-Metadaten nicht abrufbar (${infoAntwort.status}): ${text}`, 'abruf');
  }

  const info = await infoAntwort.json();
  const groesse = Number(info.file_size) || 0;

  // Vor dem Herunterladen abbrechen, nicht erst danach.
  if (groesse > maxBytes) {
    throw new MedienFehler(`Datei zu groß: ${groesse} Bytes (erlaubt: ${maxBytes})`, 'zu-gross');
  }

  const dateiAntwort = await fetchImpl(info.url, { headers: kopfzeilen(konfig) });
  if (!dateiAntwort.ok) {
    const text = await dateiAntwort.text().catch(() => '');
    throw new MedienFehler(`Mediendatei nicht abrufbar (${dateiAntwort.status}): ${text}`, 'abruf');
  }

  const daten = Buffer.from(await dateiAntwort.arrayBuffer());

  // Meta gibt file_size nicht immer verlaesslich an -- deshalb noch einmal messen.
  if (daten.length > maxBytes) {
    throw new MedienFehler(`Datei zu groß: ${daten.length} Bytes (erlaubt: ${maxBytes})`, 'zu-gross');
  }

  return {
    daten,
    mimeTyp: String(info.mime_type || '').split(';')[0].trim(),
    groesse: daten.length,
  };
}

module.exports = { hole, MedienFehler, VORGABE_MAX_BYTES };
