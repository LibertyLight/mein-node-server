'use strict';

/**
 * Konfiguration der KI-Schreibhilfe fuer die Android-Tastatur.
 *
 * Der API-Schluessel bleibt auf dem Server; die Tastatur kennt nur ein eigenes
 * Zugangswort (TASTATUR_TOKEN). Ohne dieses Wort wird die Route gar nicht erst
 * eingehaengt -- der Server lauscht auf allen Netzwerkschnittstellen, und sonst
 * koennte jeder im selben WLAN auf deine Kosten Claude benutzen.
 */

const VORGABEN = {
  modell: 'claude-opus-5-5',
  // Tippen ist ungeduldig: "low" haelt die Wartezeit kurz, fuer Umformulieren reicht das.
  aufwand: 'low',
  maxTokens: 4000,
  maxZeichen: 8000,
  anfragenProMinute: 30,
};

function zahl(wert, vorgabe) {
  const geparst = Number(wert);
  return Number.isFinite(geparst) && geparst > 0 ? geparst : vorgabe;
}

function lade(umgebung = process.env) {
  return {
    apiSchluessel: umgebung.ANTHROPIC_API_KEY || '',
    token: umgebung.TASTATUR_TOKEN || '',
    modell: umgebung.TASTATUR_MODELL || VORGABEN.modell,
    aufwand: umgebung.TASTATUR_AUFWAND || VORGABEN.aufwand,
    maxTokens: zahl(umgebung.TASTATUR_MAX_TOKENS, VORGABEN.maxTokens),
    maxZeichen: zahl(umgebung.TASTATUR_MAX_ZEICHEN, VORGABEN.maxZeichen),
    anfragenProMinute: zahl(umgebung.TASTATUR_ANFRAGEN_PRO_MINUTE, VORGABEN.anfragenProMinute),
  };
}

/** Was fehlt, damit die Route laufen kann -- leer heisst: alles da. */
function pruefe(konfig) {
  const fehlend = [];
  if (!konfig.apiSchluessel) fehlend.push('ANTHROPIC_API_KEY');
  if (!konfig.token) fehlend.push('TASTATUR_TOKEN');
  else if (konfig.token.length < 16) fehlend.push('TASTATUR_TOKEN (mindestens 16 Zeichen)');
  return fehlend;
}

module.exports = { lade, pruefe, VORGABEN };
