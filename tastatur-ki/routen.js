'use strict';

const crypto = require('node:crypto');
const express = require('express');
const aufgaben = require('./aufgaben');
const { uebersetzeFehler } = require('./claude');

/** Vergleich in konstanter Zeit, damit sich das Zugangswort nicht erraten laesst. */
function gleich(a, b) {
  const x = crypto.createHash('sha256').update(String(a)).digest();
  const y = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(x, y);
}

/** Einfache Bremse: hoechstens n Anfragen pro Minute, egal von wem. */
function erstelleBremse(proMinute, jetzt = Date.now) {
  let zeitstempel = [];
  return () => {
    const grenze = jetzt() - 60_000;
    zeitstempel = zeitstempel.filter((t) => t > grenze);
    if (zeitstempel.length >= proMinute) return false;
    zeitstempel.push(jetzt());
    return true;
  };
}

function erstelleRouter({ konfig, claude, protokoll = console, jetzt }) {
  const router = express.Router();
  const darf = erstelleBremse(konfig.anfragenProMinute, jetzt);

  router.use(express.json({ limit: '64kb' }));

  router.use((req, res, next) => {
    if (!gleich(req.get('X-Tastatur-Token') || '', konfig.token)) {
      return res.status(401).json({ fehler: 'Zugangswort fehlt oder ist falsch' });
    }
    next();
  });

  // Verbindungstest aus den Tastatur-Einstellungen
  router.get('/status', (req, res) => {
    res.json({ ok: true, modell: konfig.modell, aktionen: Object.keys(aufgaben.AUFGABEN) });
  });

  router.post('/ki', async (req, res) => {
    const anfrage = aufgaben.baue(req.body, konfig);
    if (anfrage.fehler) return res.status(400).json({ fehler: anfrage.fehler });
    if (!darf()) return res.status(429).json({ fehler: 'Zu viele Anfragen. Bitte kurz warten.' });

    try {
      res.json({ text: await claude.bearbeite(anfrage) });
    } catch (fehler) {
      const meldung = uebersetzeFehler(fehler);
      protokoll.error(`[tastatur] ${req.body.aktion}: ${fehler.message}`);
      res.status(meldung.status).json({ fehler: meldung.message });
    }
  });

  return router;
}

module.exports = { erstelleRouter, erstelleBremse };
