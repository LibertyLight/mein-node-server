'use strict';

/**
 * Claude auf WhatsApp.
 *
 * Setzt Konfiguration, Verlauf, Claude-Anbindung und Versand zusammen und
 * liefert einen fertigen Express-Router. Fehlen Zugangsdaten, bleibt der Bot
 * aus -- die Anwendung startet aber trotzdem und sagt, was fehlt.
 *
 * Einrichtung: siehe WHATSAPP.md
 */

const konfigModul = require('./konfig');
const nachrichtenModul = require('./nachrichten');
const verlaufModul = require('./verlauf');
const claudeModul = require('./claude');
const botModul = require('./bot');
const routenModul = require('./routen');

function erstelle({ db, umgebung = process.env, protokoll = console } = {}) {
  const konfig = konfigModul.lade(umgebung);
  const fehlt = konfigModul.fehlendeAngaben(konfig);

  if (fehlt.length > 0) {
    return {
      aktiv: false,
      konfig,
      router: null,
      hinweise: [
        'nicht aktiv – es fehlen folgende Umgebungsvariablen:',
        ...fehlt.map((name) => `  ${name}`),
        'Einrichtung: siehe WHATSAPP.md',
      ],
    };
  }

  const verlauf = verlaufModul.erstelleVerlauf(db, { maxNachrichten: konfig.verlaufNachrichten });
  verlauf.aufraeumen();

  const claude = claudeModul.erstelleClaude(konfig);
  const bot = botModul.erstelleBot({ konfig, verlauf, claude, protokoll });
  const router = routenModul.erstelleRouter({ konfig, bot, protokoll });

  return {
    aktiv: true,
    konfig,
    verlauf,
    bot,
    router,
    hinweise: [
      `aktiv mit Modell ${konfig.modell} (Aufwand: ${konfig.aufwand})`,
      konfig.alleErlaubt
        ? 'ACHTUNG: alle Absender sind freigegeben (WHATSAPP_ERLAUBTE_NUMMERN=alle)'
        : `freigegebene Nummern: ${konfig.erlaubteNummern.length}`,
    ],
  };
}

module.exports = { erstelle, konfig: konfigModul, nachrichten: nachrichtenModul };
