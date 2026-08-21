'use strict';

/**
 * Werkzeugkasten fuer die Netzwerkanalyse.
 *
 * Bewusst nur mit Node-Bordmitteln umgesetzt: unter Termux sind weder ip,
 * ping, ss noch netstat garantiert vorhanden. Externe Befehle werden daher
 * immer nur als Zusatzinformation genutzt, nie als Voraussetzung.
 */

const net = require('node:net');
const os = require('node:os');
const dns = require('node:dns');
const dnsPromises = dns.promises;
const https = require('node:https');
const http = require('node:http');
const { execFile } = require('node:child_process');
const aufloeser = require('./aufloeser');

/** Millisekunden seit einem Startzeitpunkt, auf eine Nachkommastelle gerundet. */
function seit(start) {
  return Math.round((performance.now() - start) * 10) / 10;
}

/**
 * Baut eine TCP-Verbindung auf und misst die Zeit bis zum Handshake.
 * Ersatz fuer ping: ICMP ist auf Android und in vielen Netzen gesperrt.
 */
function tcpVerbindung(host, port, zeitlimit = 3000) {
  return new Promise((aufloesen) => {
    const start = performance.now();
    const socket = new net.Socket();
    let erledigt = false;

    const beenden = (ergebnis) => {
      if (erledigt) return;
      erledigt = true;
      socket.destroy();
      aufloesen({ host, port, ...ergebnis });
    };

    socket.setTimeout(zeitlimit);
    socket.once('connect', () => beenden({ ok: true, ms: seit(start) }));
    socket.once('timeout', () => beenden({ ok: false, ms: seit(start), fehler: 'Zeitlimit überschritten' }));
    socket.once('error', (fehler) => beenden({ ok: false, ms: seit(start), fehler: fehler.message, code: fehler.code }));

    socket.connect({ host, port });
  });
}

/**
 * Mehrere TCP-Handshakes hintereinander, um Latenz, Jitter und Paketverlust
 * zu schaetzen. Ein einzelner Messwert sagt ueber ein WLAN wenig aus.
 */
async function messeLatenz(host, port, versuche = 4, zeitlimit = 3000) {
  const zeiten = [];
  let fehlschlaege = 0;
  let letzterFehler = null;

  for (let i = 0; i < versuche; i += 1) {
    const ergebnis = await tcpVerbindung(host, port, zeitlimit);
    if (ergebnis.ok) {
      zeiten.push(ergebnis.ms);
    } else {
      fehlschlaege += 1;
      letzterFehler = ergebnis.fehler;
    }
  }

  if (zeiten.length === 0) {
    return { host, port, versuche, erfolge: 0, verlustQuote: 1, fehler: letzterFehler };
  }

  const summe = zeiten.reduce((a, b) => a + b, 0);
  const schnitt = summe / zeiten.length;
  // Jitter als mittlere Abweichung vom Durchschnitt.
  const jitter = zeiten.reduce((a, ms) => a + Math.abs(ms - schnitt), 0) / zeiten.length;

  return {
    host,
    port,
    versuche,
    erfolge: zeiten.length,
    verlustQuote: fehlschlaege / versuche,
    min: Math.min(...zeiten),
    max: Math.max(...zeiten),
    schnitt: Math.round(schnitt * 10) / 10,
    jitter: Math.round(jitter * 10) / 10,
    fehler: letzterFehler,
  };
}

/**
 * HTTP(S)-Abruf ohne Weiterleitungen zu folgen. Der rohe Statuscode ist
 * gerade bei Captive Portals aussagekraeftiger als der Inhalt.
 */
function httpAbruf(url, { zeitlimit = 5000, methode = 'GET' } = {}) {
  return new Promise((aufloesen) => {
    const start = performance.now();
    const ziel = new URL(url);
    const modul = ziel.protocol === 'https:' ? https : http;

    const anfrage = modul.request(
      ziel,
      { method: methode, timeout: zeitlimit, headers: { 'user-agent': 'netzdoktor' } },
      (antwort) => {
        let laenge = 0;
        antwort.on('data', (stueck) => {
          laenge += stueck.length;
          // Der Rumpf interessiert nicht, nur dass Daten fliessen.
          if (laenge > 8192) antwort.destroy();
        });
        antwort.on('close', () =>
          aufloesen({
            ok: true,
            status: antwort.statusCode,
            ort: antwort.headers.location || null,
            ms: seit(start),
          }),
        );
      },
    );

    anfrage.on('timeout', () => {
      anfrage.destroy();
      aufloesen({ ok: false, ms: seit(start), fehler: 'Zeitlimit überschritten' });
    });
    anfrage.on('error', (fehler) =>
      aufloesen({ ok: false, ms: seit(start), fehler: fehler.message, code: fehler.code }),
    );
    anfrage.end();
  });
}

/**
 * Namensaufloesung ueber die Systemkonfiguration (respektiert /etc/hosts).
 *
 * Bewusst immer der echte System-Auffloeser, auch wenn der Ersatz-Auffloeser
 * gerade aktiv ist: fuer die Diagnose zaehlt, ob das System selbst noch
 * aufloest -- sonst wuerde die Reparatur ihren eigenen Erfolg messen.
 */
async function dnsAufloesung(name, zeitlimit = 5000) {
  const start = performance.now();
  try {
    const treffer = await Promise.race([
      aufloeser.systemLookupAsync(name, { all: true }),
      new Promise((_, ablehnen) =>
        setTimeout(() => ablehnen(new Error('Zeitlimit überschritten')), zeitlimit).unref(),
      ),
    ]);
    return { ok: true, name, ms: seit(start), adressen: treffer.map((t) => t.address) };
  } catch (fehler) {
    return { ok: false, name, ms: seit(start), fehler: fehler.message, code: fehler.code };
  }
}

/** Namensaufloesung gezielt ueber einen bestimmten DNS-Server. */
async function dnsUeberServer(name, server, zeitlimit = 5000) {
  const start = performance.now();
  const serverAufloeser = new dnsPromises.Resolver({ timeout: zeitlimit, tries: 1 });
  serverAufloeser.setServers([server]);
  try {
    const adressen = await serverAufloeser.resolve4(name);
    return { ok: true, name, server, ms: seit(start), adressen };
  } catch (fehler) {
    return { ok: false, name, server, ms: seit(start), fehler: fehler.message, code: fehler.code };
  }
}

/** Externen Befehl ausfuehren, ohne dass ein fehlendes Programm alles abbricht. */
function befehl(programm, argumente = [], zeitlimit = 4000) {
  return new Promise((aufloesen) => {
    execFile(programm, argumente, { timeout: zeitlimit, encoding: 'utf8' }, (fehler, stdout, stderr) => {
      if (fehler) {
        aufloesen({ ok: false, fehler: fehler.message, stdout: stdout || '', stderr: stderr || '' });
        return;
      }
      aufloesen({ ok: true, stdout, stderr });
    });
  });
}

/**
 * Ist die Adresse global routbar (2000::/3)?
 *
 * Wichtig fuer die Diagnose: fe80::-Adressen (Link-Local) hat jedes Geraet,
 * fc00::/7 (Unique Local) sind wie private IPv4-Netze. Beide kommen nie ins
 * Internet. Wer sie als "IPv6 ist eingerichtet" wertet, meldet auf praktisch
 * jedem Geraet eine Stoerung, die keine ist.
 */
function istGlobalesIPv6(adresse) {
  return stufeIPv6(adresse) === 'global' || stufeIPv6(adresse) === '6to4';
}

/**
 * Feinere Einstufung einer IPv6-Adresse.
 *
 * 2002::/16 verdient eine eigene Kategorie: Das ist 6to4, ein Tunnelverfahren,
 * das RFC 7526 fuer ueberholt erklaert hat. Die oeffentlichen Relays dafuer
 * sind groesstenteils abgeschaltet. Solche Adressen sehen global aus und sind
 * es formal auch -- nur kommt nichts mehr an. Wer das nicht benennt, meldet
 * "IPv6 nicht erreichbar" und laesst den Nutzer im Router nach der falschen
 * Ursache suchen.
 */
function stufeIPv6(adresse) {
  if (typeof adresse !== 'string') return 'unbekannt';
  const ersterBlock = adresse.split('%')[0].split(':')[0];
  if (!/^[0-9a-fA-F]{1,4}$/.test(ersterBlock)) return 'unbekannt';

  const wert = parseInt(ersterBlock, 16);
  if (wert >= 0xfe80 && wert <= 0xfebf) return 'link-local';
  if (wert >= 0xfc00 && wert <= 0xfdff) return 'ula';
  if (wert >= 0xff00) return 'multicast';
  if (wert === 0x2002) return '6to4';
  if (wert >= 0x2000 && wert <= 0x3fff) return 'global';
  return 'unbekannt';
}

/** Aktive, nicht-lokale Netzwerkschnittstellen. */
function schnittstellen() {
  const roh = os.networkInterfaces();
  const liste = [];

  for (const [name, adressen] of Object.entries(roh)) {
    for (const adresse of adressen || []) {
      liste.push({
        name,
        adresse: adresse.address,
        familie: String(adresse.family).includes('6') ? 'IPv6' : 'IPv4',
        intern: adresse.internal,
        netzmaske: adresse.netmask,
        mac: adresse.mac,
      });
    }
  }
  return liste;
}

/**
 * Standard-Gateway ermitteln. Node kennt die Routingtabelle nicht, deshalb
 * werden mehrere Systembefehle der Reihe nach probiert.
 */
async function standardGateway() {
  const versuche = [
    { programm: 'ip', argumente: ['route', 'show', 'default'], muster: /default\s+via\s+([0-9.]+)/ },
    { programm: 'route', argumente: ['-n'], muster: /^0\.0\.0\.0\s+([0-9.]+)/m },
    { programm: 'netstat', argumente: ['-rn'], muster: /^(?:0\.0\.0\.0|default)\s+([0-9.]+)/m },
  ];

  for (const versuch of versuche) {
    const ergebnis = await befehl(versuch.programm, versuch.argumente);
    if (!ergebnis.ok) continue;
    const treffer = ergebnis.stdout.match(versuch.muster);
    if (treffer) return { ok: true, adresse: treffer[1], quelle: versuch.programm };
  }

  // Notloesung: bei einer /24-Schnittstelle ist das Gateway fast immer .1
  const kandidat = schnittstellen().find(
    (s) => !s.intern && s.familie === 'IPv4' && s.netzmaske === '255.255.255.0',
  );
  if (kandidat) {
    const geraten = kandidat.adresse.replace(/\.\d+$/, '.1');
    return { ok: true, adresse: geraten, quelle: 'geschätzt', geschaetzt: true };
  }

  return { ok: false, fehler: 'Kein Standard-Gateway ermittelbar' };
}

/** Prueft, ob ein lokaler TCP-Port noch frei ist. */
function portFrei(port, host = '0.0.0.0') {
  return new Promise((aufloesen) => {
    const server = net.createServer();
    server.once('error', (fehler) => aufloesen({ frei: false, code: fehler.code }));
    server.once('listening', () => server.close(() => aufloesen({ frei: true })));
    server.listen(port, host);
  });
}

/** Prozesse ermitteln, die auf einem Port lauschen (best effort). */
async function prozesseAufPort(port) {
  const lsof = await befehl('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN']);
  if (lsof.ok && lsof.stdout.trim()) {
    const zeilen = lsof.stdout.trim().split('\n').slice(1);
    const prozesse = zeilen
      .map((zeile) => zeile.trim().split(/\s+/))
      .filter((teile) => teile.length >= 2)
      .map((teile) => ({ befehl: teile[0], pid: Number(teile[1]) }))
      .filter((p) => Number.isInteger(p.pid));
    if (prozesse.length > 0) return { ok: true, quelle: 'lsof', prozesse };
  }

  const ss = await befehl('ss', ['-ltnp', `sport = :${port}`]);
  if (ss.ok && ss.stdout.includes('pid=')) {
    const prozesse = [...ss.stdout.matchAll(/\("([^"]+)",pid=(\d+)/g)].map((t) => ({
      befehl: t[1],
      pid: Number(t[2]),
    }));
    if (prozesse.length > 0) return { ok: true, quelle: 'ss', prozesse };
  }

  return { ok: false, prozesse: [], fehler: 'Weder lsof noch ss lieferten ein Ergebnis' };
}

module.exports = {
  befehl,
  istGlobalesIPv6,
  stufeIPv6,
  dnsAufloesung,
  dnsUeberServer,
  httpAbruf,
  messeLatenz,
  portFrei,
  prozesseAufPort,
  schnittstellen,
  standardGateway,
  tcpVerbindung,
};
