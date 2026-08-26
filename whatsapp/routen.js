'use strict';

/**
 * Express-Anbindung des WhatsApp-Bots.
 *
 * Wird in app.js eingehaengt. Wichtig: dieser Router muss VOR einem globalen
 * express.json() stehen. Fuer die Signaturpruefung wird der Koerper Byte fuer
 * Byte gebraucht -- ein bereits geparster Koerper laesst sich nicht
 * zeichengenau rekonstruieren.
 */

const express = require('express');
const nachrichtenModul = require('./nachrichten');
const konfigModul = require('./konfig');

/**
 * Die Selbstauskunft verraet zwar keine Zugangsdaten, aber sehr wohl, dass hier
 * ein Bot laeuft und wie viele Nummern freigegeben sind. Wie beim Netzdoktor
 * gilt deshalb: nur vom Geraet selbst.
 */
function nurLokal(req, res, next) {
  const adresse = (req.socket.remoteAddress || '').replace(/^::ffff:/, '');
  if (adresse === '127.0.0.1' || adresse === '::1') return next();
  return res.sendStatus(403);
}

/** Rohen Koerper mitschneiden, waehrend express ihn parst. */
function merkeRohkoerper(req, res, puffer) {
  req.rohkoerper = puffer;
}

function erstelleRouter({ konfig, bot, protokoll = console }) {
  const router = express.Router();

  // Meta prueft die Adresse einmalig beim Einrichten des Webhooks.
  router.get('/webhook', (req, res) => {
    const modus = req.query['hub.mode'];
    const token = req.query['hub.verify_token'];

    if (modus === 'subscribe' && token === konfig.pruefToken) {
      protokoll.log('[whatsapp] Webhook von Meta bestätigt.');
      return res.status(200).type('text/plain').send(String(req.query['hub.challenge'] ?? ''));
    }

    protokoll.warn('[whatsapp] Webhook-Bestätigung mit falschem Prüf-Token abgelehnt.');
    return res.sendStatus(403);
  });

  router.post('/webhook', express.json({ verify: merkeRohkoerper, limit: '1mb' }), (req, res) => {
    if (!req.rohkoerper) {
      protokoll.error(
        '[whatsapp] Kein roher Anfragekörper vorhanden – erwartet wird Content-Type: application/json,\n' +
          '           und der WhatsApp-Router muss vor einem globalen express.json() eingehängt werden.',
      );
      return res.sendStatus(500);
    }

    const signatur = req.get('x-hub-signature-256');
    if (!nachrichtenModul.pruefeSignatur(req.rohkoerper, signatur, konfig.appGeheimnis)) {
      protokoll.warn('[whatsapp] Anfrage mit ungültiger Signatur abgewiesen.');
      return res.sendStatus(403);
    }

    // Sofort quittieren: Meta wartet nur wenige Sekunden und wiederholt sonst
    // die Zustellung. Claude braucht laenger.
    res.sendStatus(200);

    const koerper = req.body;
    setImmediate(() => {
      bot.verarbeiteKoerper(koerper).catch((fehler) => {
        protokoll.error(`[whatsapp] Verarbeitung fehlgeschlagen: ${fehler.message}`);
      });
    });

    return undefined;
  });

  // Kleine Selbstauskunft fuers Dashboard -- ohne Zugangsdaten.
  router.get('/status', nurLokal, (req, res) => {
    res.json({
      aktiv: konfigModul.istVollstaendig(konfig),
      modell: konfig.modell,
      aufwand: konfig.aufwand,
      graphVersion: konfig.graphVersion,
      verlaufNachrichten: konfig.verlaufNachrichten,
      sprachnachrichten: konfig.transkription
        ? { modell: konfig.whisperModell, dienst: konfig.whisperUrl }
        : false,
      freigegebeneNummern: konfig.alleErlaubt ? 'alle' : konfig.erlaubteNummern.length,
      fehlendeAngaben: konfigModul.fehlendeAngaben(konfig),
    });
  });

  return router;
}

module.exports = { erstelleRouter, merkeRohkoerper, nurLokal };
