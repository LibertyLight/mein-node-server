'use strict';

/**
 * Ersatz fuer dns.lookup.
 *
 * Wichtig zu wissen: dns.setServers() wirkt ausschliesslich auf dns.resolve*().
 * Verbindungen ueber net, http oder fetch gehen dagegen ueber dns.lookup(),
 * und das fragt immer den Auffloeser des Betriebssystems. Ein DNS-Fallback,
 * der nur setServers aufruft, waere also wirkungslos -- genau der Fehler, der
 * die Reparatur "dns-fallback" wertlos gemacht haette.
 *
 * Deshalb wird dns.lookup hier gegen eine eigene Fassung getauscht, die ueber
 * die konfigurierten Server aufloest und nur im Notfall auf das System
 * zurueckfaellt. net.lookupAndConnect greift dns.lookup zur Laufzeit ab, der
 * Austausch wirkt damit auch auf bereits geladene Module.
 */

const dns = require('node:dns');
const net = require('node:net');
const util = require('node:util');

// Die Systemfassung wird einmal gesichert und nie wieder verworfen: Module,
// die sich dns.lookup fruehzeitig merken, sollen auch nach dem Abschalten
// noch eine funktionierende Funktion in der Hand halten.
const systemLookup = dns.lookup;
const systemLookupPromise = dns.promises.lookup;
const systemServer = dns.getServers();

/** Promise-Fassung des System-Auffloesers, unabhaengig vom Ersatz. */
const systemLookupAsync = util.promisify(systemLookup);

let aktiv = false;
let aktiveServer = [];

/** Normalisiert die drei erlaubten Aufrufformen von dns.lookup. */
function leseArgumente(hostname, optionen, rueckruf) {
  if (typeof optionen === 'function') return { optionen: {}, rueckruf: optionen };
  if (typeof optionen === 'number') return { optionen: { family: optionen }, rueckruf };
  return { optionen: optionen || {}, rueckruf };
}

function nichtGefunden(hostname) {
  const fehler = new Error(`getaddrinfo ENOTFOUND ${hostname}`);
  fehler.code = 'ENOTFOUND';
  fehler.errno = -3008;
  fehler.syscall = 'getaddrinfo';
  fehler.hostname = hostname;
  return fehler;
}

/** Fragt die konfigurierten Server ab, IPv4 zuerst. */
async function ueberServer(hostname, family) {
  const aufloeser = new dns.promises.Resolver({ timeout: 4000, tries: 2 });
  aufloeser.setServers(aktiveServer);

  const treffer = [];
  const holen = async (art, familie) => {
    try {
      const adressen = await aufloeser[art](hostname);
      for (const adresse of adressen) treffer.push({ address: adresse, family: familie });
    } catch {
      // Ein leeres Ergebnis ist hier kein Fehler: die andere Familie kann noch liefern.
    }
  };

  if (family !== 6) await holen('resolve4', 4);
  if (family !== 4 && (family === 6 || treffer.length === 0)) await holen('resolve6', 6);

  return treffer;
}

/** Die Ersatzfassung von dns.lookup. */
function eigenesLookup(hostname, optionen, rueckruf) {
  const { optionen: opt, rueckruf: fertig } = leseArgumente(hostname, optionen, rueckruf);
  const family = Number(opt.family) || 0;

  // Abgeschaltet oder Sonderfall? Dann direkt ans System. IP-Literale und der
  // lokale Rechner brauchen kein DNS, und /etc/hosts soll weiter gelten.
  if (!aktiv || !hostname || net.isIP(hostname) || hostname === 'localhost') {
    return systemLookup(hostname, opt, fertig);
  }

  ueberServer(hostname, family)
    .then((treffer) => {
      if (treffer.length === 0) {
        // Kein Treffer: dem System noch eine Chance geben (hosts-Datei, mDNS).
        return systemLookup(hostname, opt, fertig);
      }
      if (opt.all) return fertig(null, treffer);
      return fertig(null, treffer[0].address, treffer[0].family);
    })
    .catch(() => {
      try {
        systemLookup(hostname, opt, fertig);
      } catch {
        fertig(nichtGefunden(hostname));
      }
    });

  return undefined;
}

/**
 * dns.lookup traegt ein util.promisify.custom-Symbol, das {address, family}
 * liefert statt nur der Adresse. Ohne dieses Symbol wuerde jeder Aufrufer, der
 * promisify benutzt, ploetzlich einen String bekommen.
 */
function eigenesLookupPromise(hostname, optionen = {}) {
  return new Promise((aufloesen, ablehnen) => {
    eigenesLookup(hostname, optionen, (fehler, adresse, familie) => {
      if (fehler) return ablehnen(fehler);
      if (optionen && optionen.all) return aufloesen(adresse);
      return aufloesen({ address: adresse, family: familie });
    });
  });
}

eigenesLookup[util.promisify.custom] = eigenesLookupPromise;

/** Schaltet den Ersatz-Auffloeser ein. */
function aktiviere(server) {
  if (!Array.isArray(server) || server.length === 0) return false;

  aktiveServer = [...server];
  aktiv = true;
  dns.lookup = eigenesLookup;
  dns.promises.lookup = eigenesLookupPromise;
  dns.setServers(aktiveServer);
  return true;
}

/** Stellt den Auffloeser des Betriebssystems wieder her. */
function deaktiviere() {
  if (!aktiv) return false;

  aktiv = false;
  aktiveServer = [];
  dns.lookup = systemLookup;
  dns.promises.lookup = systemLookupPromise;
  // Auch die Serverliste zuruecksetzen, sonst blieben dns.resolve*-Aufrufe
  // beim Ersatzserver haengen.
  if (systemServer.length > 0) dns.setServers(systemServer);
  return true;
}

function istAktiv() {
  return aktiv;
}

module.exports = { aktiviere, deaktiviere, istAktiv, leseArgumente, systemLookupAsync };
