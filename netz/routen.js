'use strict';

/**
 * Express-Anbindung des Netzdoktors.
 *
 * Wird in app.js eingehaengt und bedient das Dashboard unter /netz.html.
 */

const express = require('express');
const analyseModul = require('./analyse');
const reparaturModul = require('./reparaturen');
const konfig = require('./konfig');

/** Den internen Kontext nicht mit ausliefern -- er enthaelt Funktionen. */
function ohneKontext(bericht) {
  const { _ktx, ...rest } = bericht;
  return rest;
}

/**
 * Reparaturen koennen fremde Prozesse beenden. Aus dem Netz erreichbar waere
 * das ein Sicherheitsproblem, deshalb nur ueber die Loopback-Adresse -- oder
 * ausdruecklich per NETZ_REPARATUR_ENTFERNT=1 freigegeben.
 */
function nurLokal(req, res, next) {
  if (process.env.NETZ_REPARATUR_ENTFERNT === '1') return next();

  const adresse = (req.socket.remoteAddress || '').replace(/^::ffff:/, '');
  if (adresse === '127.0.0.1' || adresse === '::1') return next();

  return res.status(403).json({
    fehler: 'Reparaturen sind nur vom Gerät selbst erlaubt.',
    hinweis: 'Zum Freigeben NETZ_REPARATUR_ENTFERNT=1 setzen – nur in vertrauenswürdigen Netzen.',
  });
}

function erstelleRouter(vorgaben = {}) {
  const router = express.Router();

  // Ergebnis kurz vorhalten: die Analyse dauert einige Sekunden und das
  // Dashboard fragt Analyse und Reparaturvorschlaege getrennt ab.
  let letzterBericht = null;
  let letzterZeitpunkt = 0;
  const GUELTIG_MS = 15000;

  async function berichtHolen({ frisch = false, optionen = {} } = {}) {
    if (!frisch && letzterBericht && Date.now() - letzterZeitpunkt < GUELTIG_MS) {
      return letzterBericht;
    }
    letzterBericht = await analyseModul.analysiere({ ...vorgaben, ...optionen });
    letzterZeitpunkt = Date.now();
    return letzterBericht;
  }

  router.get('/analyse', async (req, res, next) => {
    try {
      const optionen = {};
      if (req.query.messungen) optionen.messungen = Number(req.query.messungen);
      if (req.query.port) optionen.port = Number(req.query.port);

      const bericht = await berichtHolen({ frisch: req.query.frisch === '1', optionen });
      res.json(ohneKontext(bericht));
    } catch (fehler) {
      next(fehler);
    }
  });

  router.get('/reparaturen', (req, res) => {
    res.json(
      reparaturModul.reparaturen.map(({ id, titel, beschreibung, risiko, bestaetigungNoetig }) => ({
        id,
        titel,
        beschreibung,
        risiko,
        bestaetigungNoetig,
      })),
    );
  });

  router.post('/reparieren', nurLokal, async (req, res, next) => {
    try {
      const { ids = null, anwenden = false, bestaetigt = false } = req.body || {};
      if (ids !== null && !Array.isArray(ids)) {
        return res.status(400).json({ fehler: '"ids" muss eine Liste von Reparatur-IDs sein.' });
      }

      // Immer auf frischen Daten arbeiten: eine veraltete Analyse koennte
      // Massnahmen anstossen, die gar nicht mehr noetig sind.
      const bericht = await berichtHolen({ frisch: true });
      const ergebnis = await analyseModul.repariere(bericht, {
        anwenden: Boolean(anwenden),
        bestaetigt: Boolean(bestaetigt),
        nur: ids,
      });

      letzterBericht = null; // Nach einem Eingriff ist der Zwischenspeicher hinfällig.
      res.json(ergebnis);
    } catch (fehler) {
      next(fehler);
    }
  });

  router.get('/konfig', (req, res) => {
    res.json({ datei: konfig.KONFIG_DATEI, einstellungen: konfig.lade() });
  });

  router.delete('/konfig', nurLokal, (req, res) => {
    const geloescht = konfig.zuruecksetzen();
    letzterBericht = null;
    res.json({ erfolg: true, geloescht });
  });

  return router;
}

module.exports = { erstelleRouter, nurLokal };
