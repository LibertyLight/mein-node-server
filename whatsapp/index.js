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
const whisperModul = require('./whisper');
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
  // Ohne Whisper laeuft alles weiter -- nur Sprachnachrichten bekommen dann
  // statt einer Antwort einen Hinweis.
  const whisper = konfig.transkription ? whisperModul.erstelleWhisper(konfig) : null;
  const bot = botModul.erstelleBot({ konfig, verlauf, claude, whisper, protokoll });
  const router = routenModul.erstelleRouter({ konfig, bot, protokoll });

  return {
    aktiv: true,
    konfig,
    verlauf,
    bot,
    whisper,
    router,
    hinweise: [
      `aktiv mit Modell ${konfig.modell} (Aufwand: ${konfig.aufwand})`,
      konfig.alleErlaubt
        ? 'ACHTUNG: alle Absender sind freigegeben (WHATSAPP_ERLAUBTE_NUMMERN=alle)'
        : `freigegebene Nummern: ${konfig.erlaubteNummern.length}`,
      konfig.transkription
        ? `Sprachnachrichten: ${konfig.whisperModell} über ${konfig.whisperUrl}`
        : 'Sprachnachrichten: aus (WHISPER_API_KEY oder WHISPER_URL setzen)',
    ],
  };
}

module.exports = { erstelle, konfig: konfigModul, nachrichten: nachrichtenModul };
