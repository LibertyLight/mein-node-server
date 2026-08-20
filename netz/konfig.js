'use strict';

/**
 * Laufzeit-Konfiguration des Netzdoktors.
 *
 * Reparaturen, die den Prozess dauerhaft beeinflussen (etwa ein DNS-Fallback),
 * werden hier abgelegt und beim Start wieder angewandt. Ohne Root-Rechte kann
 * unter Termux nichts am System selbst geaendert werden -- die Korrekturen
 * gelten deshalb bewusst nur fuer diese Anwendung.
 */

const fs = require('node:fs');
const path = require('node:path');
const dns = require('node:dns');
const aufloeser = require('./aufloeser');

const KONFIG_DATEI = path.join(__dirname, '..', 'netz-konfig.json');

const VORGABE = {
  dnsServer: null, // z. B. ['1.1.1.1', '8.8.8.8']
  ergebnisReihenfolge: null, // 'ipv4first' | 'verbatim'
  proxyBereinigt: false,
};

function lade() {
  try {
    const inhalt = fs.readFileSync(KONFIG_DATEI, 'utf8');
    return { ...VORGABE, ...JSON.parse(inhalt) };
  } catch (fehler) {
    if (fehler.code !== 'ENOENT') {
      console.warn(`[netzdoktor] Konfiguration nicht lesbar (${fehler.message}) – nutze Vorgaben.`);
    }
    return { ...VORGABE };
  }
}

function speichere(konfig) {
  const zusammengefuehrt = { ...lade(), ...konfig };
  fs.writeFileSync(KONFIG_DATEI, `${JSON.stringify(zusammengefuehrt, null, 2)}\n`, 'utf8');
  return zusammengefuehrt;
}

/** Setzt die gespeicherten Einstellungen im laufenden Prozess durch. */
function anwenden(konfig = lade()) {
  const angewandt = [];

  if (Array.isArray(konfig.dnsServer) && konfig.dnsServer.length > 0) {
    // aktiviere() ersetzt zusätzlich dns.lookup – ohne das würden Verbindungen
    // über net/http weiterhin den defekten System-Auflöser benutzen.
    aufloeser.aktiviere(konfig.dnsServer);
    angewandt.push(`DNS-Server: ${konfig.dnsServer.join(', ')}`);
  }

  if (konfig.ergebnisReihenfolge) {
    dns.setDefaultResultOrder(konfig.ergebnisReihenfolge);
    angewandt.push(`Adressreihenfolge: ${konfig.ergebnisReihenfolge}`);
  }

  if (konfig.proxyBereinigt) {
    for (const name of ['HTTP_PROXY', 'http_proxy', 'HTTPS_PROXY', 'https_proxy']) {
      delete process.env[name];
    }
    angewandt.push('Proxy-Variablen entfernt');
  }

  return angewandt;
}

function zuruecksetzen() {
  aufloeser.deaktiviere();
  try {
    fs.unlinkSync(KONFIG_DATEI);
    return true;
  } catch (fehler) {
    if (fehler.code === 'ENOENT') return false;
    throw fehler;
  }
}

module.exports = { KONFIG_DATEI, VORGABE, lade, speichere, anwenden, zuruecksetzen };
