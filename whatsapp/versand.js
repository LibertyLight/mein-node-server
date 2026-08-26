'use strict';

/**
 * Versand ueber die WhatsApp Cloud API (Graph API von Meta).
 *
 * Bewusst ohne zusaetzliche Abhaengigkeit: fetch bringt Node seit Version 18
 * selbst mit. Der fetch-Aufruf ist austauschbar, damit Tests ohne Netz laufen.
 */

const { teileText } = require('./nachrichten');

const WARTEZEITEN_MS = [500, 2000]; // zwei Wiederholungen, dann aufgeben

function schlafe(ms) {
  return new Promise((weiter) => setTimeout(weiter, ms));
}

function adresse(konfig) {
  return `https://graph.facebook.com/${konfig.graphVersion}/${konfig.telefonId}/messages`;
}

/**
 * Einen Aufruf an die Graph-API schicken.
 *
 * Bei 429 und 5xx wird wiederholt -- das sind vorruebergehende Zustaende.
 * Bei 4xx waere jede Wiederholung sinnlos, da ist die Anfrage selbst falsch.
 */
async function sende(konfig, koerper, { fetchImpl = fetch } = {}) {
  let letzterFehler = null;

  for (let versuch = 0; versuch <= WARTEZEITEN_MS.length; versuch += 1) {
    if (versuch > 0) await schlafe(WARTEZEITEN_MS[versuch - 1]);

    let antwort;
    try {
      antwort = await fetchImpl(adresse(konfig), {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${konfig.token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ messaging_product: 'whatsapp', ...koerper }),
      });
    } catch (fehler) {
      letzterFehler = fehler; // Netzproblem -- noch einmal versuchen.
      continue;
    }

    if (antwort.ok) return antwort.json();

    const text = await antwort.text().catch(() => '');
    letzterFehler = new Error(`WhatsApp-Versand fehlgeschlagen (${antwort.status}): ${text}`);
    letzterFehler.status = antwort.status;

    if (antwort.status !== 429 && antwort.status < 500) throw letzterFehler;
  }

  throw letzterFehler;
}

/** Eine Textnachricht schicken; zu lange Texte werden aufgeteilt. */
async function sendeText(konfig, an, text, optionen = {}) {
  const teile = teileText(text);
  const ergebnisse = [];

  for (const teil of teile) {
    ergebnisse.push(
      // Nacheinander, nicht parallel: sonst kommen die Teile in falscher Reihenfolge an.
      // eslint-disable-next-line no-await-in-loop
      await sende(konfig, { to: String(an), type: 'text', text: { body: teil, preview_url: false } }, optionen),
    );
  }

  return ergebnisse;
}

/** Blaue Haken setzen. Rein kosmetisch -- Fehler duerfen den Ablauf nicht stoppen. */
async function markiereGelesen(konfig, nachrichtId, optionen = {}) {
  return sende(konfig, { status: 'read', message_id: String(nachrichtId) }, optionen);
}

module.exports = { sendeText, markiereGelesen, sende, adresse };
