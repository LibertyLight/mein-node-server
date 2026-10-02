'use strict';

/**
 * KI-Schreibhilfe fuer die Android-Tastatur (siehe TASTATUR.md).
 * Liefert einen Router fuer /api/tastatur -- oder Hinweise, was noch fehlt.
 */

const konfigModul = require('./konfig');
const { erstelleClaude } = require('./claude');
const { erstelleRouter } = require('./routen');

function erstelle({ umgebung = process.env, client } = {}) {
  const konfig = konfigModul.lade(umgebung);
  const fehlend = konfigModul.pruefe(konfig);

  if (fehlend.length > 0) {
    return {
      aktiv: false,
      router: null,
      hinweise: [`Schreibhilfe für die Tastatur aus – es fehlt: ${fehlend.join(', ')}`],
    };
  }

  const claude = erstelleClaude(konfig, { client });
  return {
    aktiv: true,
    router: erstelleRouter({ konfig, claude }),
    hinweise: [`Schreibhilfe für die Tastatur aktiv (${konfig.modell}, Aufwand ${konfig.aufwand})`],
  };
}

module.exports = { erstelle };
