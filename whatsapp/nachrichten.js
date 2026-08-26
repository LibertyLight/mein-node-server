'use strict';

/**
 * Reine Hilfsfunktionen rund um das Webhook-Format von WhatsApp.
 *
 * Bewusst ohne Netzwerk und ohne Zustand: so laesst sich das Format
 * vollstaendig testen, ohne bei Meta anzurufen.
 */

const crypto = require('node:crypto');

/** WhatsApp nimmt hoechstens 4096 Zeichen je Nachricht -- mit Sicherheitsabstand. */
const MAX_ZEICHEN = 3900;

/**
 * Prueft die Signatur, die Meta ueber den Header X-Hub-Signature-256 mitschickt.
 *
 * Ohne diese Pruefung koennte jeder, der die Webhook-Adresse kennt, dem Bot
 * beliebige Nachrichten unterschieben -- auf Kosten des API-Schluessels.
 */
function pruefeSignatur(rohkoerper, signatur, geheimnis) {
  if (!geheimnis || !signatur || !rohkoerper) return false;

  const erwartet = `sha256=${crypto.createHmac('sha256', geheimnis).update(rohkoerper).digest('hex')}`;
  const gesendet = Buffer.from(String(signatur), 'utf8');
  const berechnet = Buffer.from(erwartet, 'utf8');

  // timingSafeEqual verlangt gleiche Laenge und wirft sonst.
  if (gesendet.length !== berechnet.length) return false;
  return crypto.timingSafeEqual(gesendet, berechnet);
}

/** Den lesbaren Text einer Nachricht holen -- je nach Typ steht er woanders. */
function leseText(nachricht) {
  switch (nachricht.type) {
    case 'text':
      return nachricht.text?.body || '';
    case 'button':
      return nachricht.button?.text || '';
    case 'interactive':
      return (
        nachricht.interactive?.button_reply?.title ||
        nachricht.interactive?.list_reply?.title ||
        ''
      );
    default:
      return '';
  }
}

/**
 * Aus dem verschachtelten Webhook-Koerper die einzelnen Nachrichten schaelen.
 *
 * Meta bündelt mehrere Ereignisse in einem Aufruf und schickt auch reine
 * Zustellberichte (statuses) -- die interessieren uns hier nicht.
 */
function extrahiereEreignisse(koerper) {
  const ereignisse = [];
  const eintraege = Array.isArray(koerper?.entry) ? koerper.entry : [];

  for (const eintrag of eintraege) {
    for (const aenderung of eintrag?.changes || []) {
      const wert = aenderung?.value || {};
      const namen = new Map(
        (wert.contacts || []).map((kontakt) => [kontakt.wa_id, kontakt.profile?.name || null]),
      );

      for (const nachricht of wert.messages || []) {
        if (!nachricht?.id || !nachricht?.from) continue;
        ereignisse.push({
          id: nachricht.id,
          von: nachricht.from,
          name: namen.get(nachricht.from) || null,
          typ: nachricht.type || 'unbekannt',
          text: leseText(nachricht),
          zeitstempel: Number(nachricht.timestamp) || null,
        });
      }
    }
  }

  return ereignisse;
}

/**
 * Meta wiederholt Webhooks stundenlang, wenn eine Antwort einmal ausbleibt.
 * Uralte Nachrichten nachtraeglich zu beantworten wirkt auf der Gegenseite wie
 * ein Geist -- deshalb werden sie verworfen.
 */
function istVeraltet(zeitstempel, maxAlterSekunden = 3600, jetzt = Date.now()) {
  if (!zeitstempel) return false;
  return jetzt / 1000 - zeitstempel > maxAlterSekunden;
}

/** Kurzbefehle, die der Bot selbst beantwortet, ohne Claude zu fragen. */
function erkenneBefehl(text) {
  const wort = String(text || '').trim().toLowerCase();
  if (['/neu', '/reset', '/loeschen', '/löschen'].includes(wort)) return 'neu';
  if (['/hilfe', '/help', '/?'].includes(wort)) return 'hilfe';
  return null;
}

/**
 * Lange Antworten in versandfaehige Stuecke schneiden -- moeglichst an
 * Absatz-, sonst an Zeilen- oder Wortgrenzen, damit nichts mitten im Wort reisst.
 */
function teileText(text, maxLaenge = MAX_ZEICHEN) {
  const sauber = String(text ?? '').trim();
  if (!sauber) return [];
  if (sauber.length <= maxLaenge) return [sauber];

  const teile = [];
  let rest = sauber;

  while (rest.length > maxLaenge) {
    const fenster = rest.slice(0, maxLaenge);
    let schnitt = 0;

    for (const trenner of ['\n\n', '\n', ' ']) {
      const stelle = fenster.lastIndexOf(trenner);
      // Nur trennen, wenn dabei nicht der halbe Platz verschenkt wird.
      if (stelle > maxLaenge / 2) {
        schnitt = stelle;
        break;
      }
    }

    if (schnitt <= 0) schnitt = maxLaenge; // Notfall: harter Schnitt.

    const stueck = rest.slice(0, schnitt).trim();
    if (stueck) teile.push(stueck);
    rest = rest.slice(schnitt).trim();
  }

  if (rest) teile.push(rest);
  return teile;
}

module.exports = {
  MAX_ZEICHEN,
  pruefeSignatur,
  leseText,
  extrahiereEreignisse,
  istVeraltet,
  erkenneBefehl,
  teileText,
};
