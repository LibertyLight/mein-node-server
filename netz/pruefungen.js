'use strict';

/**
 * Die einzelnen Diagnoseschritte.
 *
 * Jede Pruefung liefert ein Ergebnis der Form
 *   { status, meldung, details, reparaturen }
 * mit status aus: ok | warnung | fehler | uebersprungen | info.
 *
 * Die Reihenfolge ist bewusst gewaehlt: erst das lokale Geraet, dann das
 * Heimnetz, dann das Internet, zum Schluss Namensaufloesung und Qualitaet.
 * So laesst sich eine Stoerung eingrenzen, statt nur "kein Internet" zu melden.
 */

const werkzeuge = require('./werkzeuge');

// Feste IP-Adressen oeffentlicher DNS-Anbieter: erreichbar ohne Namensaufloesung.
const ZIELE = {
  ipv4: [
    { name: 'Cloudflare', host: '1.1.1.1', port: 443 },
    { name: 'Google', host: '8.8.8.8', port: 53 },
    { name: 'Quad9', host: '9.9.9.9', port: 443 },
  ],
  ipv6: [{ name: 'Cloudflare (IPv6)', host: '2606:4700:4700::1111', port: 443 }],
  dnsPruefName: 'example.com',
  oeffentlicherDns: '1.1.1.1',
  httpsZiel: 'https://cloudflare.com/cdn-cgi/trace',
  portalZiel: 'http://cp.cloudflare.com/generate_204',
  latenzZiel: { host: '1.1.1.1', port: 443 },
};

/** Bequemer Zugriff auf das Ergebnis einer frueheren Pruefung. */
function ergebnis(ktx, id) {
  return ktx.ergebnisse.get(id);
}

const pruefungen = [
  {
    id: 'schnittstellen',
    titel: 'Netzwerkschnittstellen',
    gruppe: 'Gerät',
    async ausfuehren() {
      const alle = werkzeuge.schnittstellen();
      const aktiv = alle.filter((s) => !s.intern);
      const ipv4 = aktiv.filter((s) => s.familie === 'IPv4');
      const ipv6 = aktiv.filter((s) => s.familie === 'IPv6');

      if (aktiv.length === 0) {
        return {
          status: 'fehler',
          meldung: 'Keine aktive Netzwerkschnittstelle gefunden – das Gerät ist offline.',
          details: { schnittstellen: alle },
        };
      }

      const selbstZugewiesen = ipv4.filter((s) => s.adresse.startsWith('169.254.'));
      if (ipv4.length > 0 && selbstZugewiesen.length === ipv4.length) {
        return {
          status: 'fehler',
          meldung: 'Nur eine selbst vergebene Adresse (169.254.x.x) – vom DHCP-Server kam keine Antwort.',
          details: { schnittstellen: aktiv },
        };
      }

      if (ipv4.length === 0) {
        return {
          status: 'warnung',
          meldung: 'Nur IPv6-Adressen vorhanden. Dienste ohne IPv6 bleiben unerreichbar.',
          details: { schnittstellen: aktiv },
        };
      }

      return {
        status: 'ok',
        meldung: `${aktiv.length} aktive Adresse(n): ${ipv4.map((s) => `${s.name} ${s.adresse}`).join(', ')}`,
        details: { schnittstellen: aktiv, ipv6Vorhanden: ipv6.length > 0 },
      };
    },
  },

  {
    id: 'loopback',
    titel: 'Lokaler Netzwerk-Stack',
    gruppe: 'Gerät',
    async ausfuehren() {
      const net = require('node:net');
      const server = net.createServer((verbindung) => verbindung.end());

      try {
        const port = await new Promise((aufloesen, ablehnen) => {
          server.once('error', ablehnen);
          server.listen(0, '127.0.0.1', () => aufloesen(server.address().port));
        });

        const treffer = await werkzeuge.tcpVerbindung('127.0.0.1', port, 2000);
        if (!treffer.ok) {
          return {
            status: 'fehler',
            meldung: `Loopback antwortet nicht (${treffer.fehler}). Der Netzwerk-Stack ist gestört.`,
            details: treffer,
          };
        }
        return { status: 'ok', meldung: `Loopback erreichbar (${treffer.ms} ms).`, details: treffer };
      } catch (fehler) {
        return {
          status: 'fehler',
          meldung: `Kein lokaler Port zu öffnen: ${fehler.message}`,
          details: { code: fehler.code },
        };
      } finally {
        server.close();
      }
    },
  },

  {
    id: 'anwendungs-port',
    titel: 'Port der Anwendung',
    gruppe: 'Gerät',
    async ausfuehren(ktx) {
      const port = ktx.optionen.port;
      const eigenerDienst = await werkzeuge.httpAbruf(`http://127.0.0.1:${port}/`, { zeitlimit: 2000 });

      if (eigenerDienst.ok) {
        return {
          status: 'ok',
          meldung: `Auf Port ${port} antwortet bereits ein Dienst (HTTP ${eigenerDienst.status}).`,
          details: { port, status: eigenerDienst.status, ms: eigenerDienst.ms },
        };
      }

      const frei = await werkzeuge.portFrei(port);
      if (frei.frei) {
        return {
          status: 'info',
          meldung: `Port ${port} ist frei – der Server kann gestartet werden.`,
          details: { port, belegt: false },
        };
      }

      const belegung = await werkzeuge.prozesseAufPort(port);
      return {
        status: 'warnung',
        meldung:
          `Port ${port} ist belegt, antwortet aber nicht auf HTTP` +
          (belegung.prozesse.length > 0
            ? ` (${belegung.prozesse.map((p) => `${p.befehl}, PID ${p.pid}`).join('; ')}).`
            : '.'),
        details: { port, belegt: true, code: frei.code, ...belegung },
        reparaturen: ['port-freigeben'],
      };
    },
  },

  {
    id: 'proxy',
    titel: 'Proxy-Konfiguration',
    gruppe: 'Gerät',
    async ausfuehren() {
      const variablen = {};
      for (const name of ['HTTP_PROXY', 'http_proxy', 'HTTPS_PROXY', 'https_proxy', 'NO_PROXY', 'no_proxy']) {
        if (process.env[name]) variablen[name] = process.env[name];
      }

      const gesetzt = Object.entries(variablen).filter(([name]) => !/^no_proxy$/i.test(name));
      if (gesetzt.length === 0) {
        return { status: 'ok', meldung: 'Kein Proxy gesetzt – direkte Verbindungen.', details: {} };
      }

      const ziele = [];
      for (const [name, wert] of gesetzt) {
        try {
          const url = new URL(wert);
          const port = Number(url.port) || (url.protocol === 'https:' ? 443 : 80);
          const treffer = await werkzeuge.tcpVerbindung(url.hostname, port, 3000);
          ziele.push({ name, wert, erreichbar: treffer.ok, fehler: treffer.fehler, ms: treffer.ms });
        } catch {
          ziele.push({ name, wert, erreichbar: false, fehler: 'Keine gültige URL' });
        }
      }

      const kaputt = ziele.filter((z) => !z.erreichbar);
      if (kaputt.length > 0) {
        return {
          status: 'fehler',
          meldung: `Proxy konfiguriert, aber nicht erreichbar: ${kaputt.map((z) => z.wert).join(', ')}. Alle Verbindungen laufen dadurch ins Leere.`,
          details: { variablen, ziele },
          reparaturen: ['proxy-bereinigen'],
        };
      }

      return {
        status: 'info',
        meldung: `Proxy aktiv und erreichbar: ${ziele.map((z) => z.wert).join(', ')}.`,
        details: { variablen, ziele },
      };
    },
  },

  {
    id: 'gateway',
    titel: 'Router / Standard-Gateway',
    gruppe: 'Heimnetz',
    async ausfuehren(ktx) {
      if (ergebnis(ktx, 'schnittstellen')?.status === 'fehler') {
        return { status: 'uebersprungen', meldung: 'Ohne aktive Schnittstelle nicht prüfbar.', details: {} };
      }

      const gateway = await werkzeuge.standardGateway();
      if (!gateway.ok) {
        return {
          status: 'warnung',
          meldung: 'Standard-Gateway konnte nicht ermittelt werden (unter Termux ohne Root üblich).',
          details: gateway,
        };
      }

      // Router antworten je nach Modell auf unterschiedlichen Ports.
      const versuche = await Promise.all(
        [80, 443, 53].map((port) => werkzeuge.tcpVerbindung(gateway.adresse, port, 2000)),
      );
      const erreichbar = versuche.filter((v) => v.ok);

      if (erreichbar.length === 0) {
        // ECONNREFUSED heisst: der Router ist da, der Port nur zu.
        const abgelehnt = versuche.some((v) => v.code === 'ECONNREFUSED');
        if (abgelehnt) {
          return {
            status: 'ok',
            meldung: `Gateway ${gateway.adresse} antwortet (Verbindung aktiv abgelehnt, Gerät also erreichbar).`,
            details: { gateway, versuche },
          };
        }
        return {
          status: 'fehler',
          meldung: `Gateway ${gateway.adresse} nicht erreichbar – Verbindung zum Router prüfen.`,
          details: { gateway, versuche },
        };
      }

      const schnellste = erreichbar.sort((a, b) => a.ms - b.ms)[0];
      return {
        status: 'ok',
        meldung: `Gateway ${gateway.adresse} erreichbar über Port ${schnellste.port} (${schnellste.ms} ms).`,
        details: { gateway, versuche },
      };
    },
  },

  {
    id: 'internet-tcp',
    titel: 'Internet-Erreichbarkeit (ohne DNS)',
    gruppe: 'Internet',
    async ausfuehren(ktx) {
      if (ergebnis(ktx, 'schnittstellen')?.status === 'fehler') {
        return { status: 'uebersprungen', meldung: 'Ohne aktive Schnittstelle nicht prüfbar.', details: {} };
      }

      const treffer = await Promise.all(
        ZIELE.ipv4.map((ziel) =>
          werkzeuge.tcpVerbindung(ziel.host, ziel.port, 4000).then((r) => ({ ...r, name: ziel.name })),
        ),
      );
      const erreichbar = treffer.filter((t) => t.ok);

      if (erreichbar.length === 0) {
        return {
          status: 'fehler',
          meldung: 'Kein einziges Internet-Ziel per IP erreichbar – die Verbindung endet vor dem Internet.',
          details: { treffer },
        };
      }
      if (erreichbar.length < treffer.length) {
        return {
          status: 'warnung',
          meldung: `Nur ${erreichbar.length} von ${treffer.length} Zielen erreichbar – mögliche Filterung.`,
          details: { treffer },
        };
      }

      return {
        status: 'ok',
        meldung: `Alle ${treffer.length} Ziele erreichbar (schnellstes: ${erreichbar.sort((a, b) => a.ms - b.ms)[0].ms} ms).`,
        details: { treffer },
      };
    },
  },

  {
    id: 'ipv6',
    titel: 'IPv6-Erreichbarkeit',
    gruppe: 'Internet',
    async ausfuehren(ktx) {
      const vorhanden = ergebnis(ktx, 'schnittstellen')?.details?.ipv6Vorhanden;
      if (!vorhanden) {
        return { status: 'uebersprungen', meldung: 'Keine globale IPv6-Adresse vorhanden.', details: {} };
      }

      const treffer = await werkzeuge.tcpVerbindung(ZIELE.ipv6[0].host, ZIELE.ipv6[0].port, 4000);
      if (treffer.ok) {
        return { status: 'ok', meldung: `IPv6 funktioniert (${treffer.ms} ms).`, details: treffer };
      }

      // Der klassische Fall: IPv6-Adresse vorhanden, Route fehlt. Programme
      // waehlen dann bevorzugt IPv6 und laufen in jedes Zeitlimit.
      const ipv4Ok = ergebnis(ktx, 'internet-tcp')?.status === 'ok';
      return {
        status: ipv4Ok ? 'warnung' : 'fehler',
        meldung: `IPv6-Adresse vorhanden, aber kein IPv6-Ziel erreichbar (${treffer.fehler}). Das bremst Verbindungen aus, die IPv6 zuerst versuchen.`,
        details: treffer,
        reparaturen: ['ipv4-bevorzugen'],
      };
    },
  },

  {
    id: 'dns-system',
    titel: 'Namensauflösung (System-DNS)',
    gruppe: 'Namensauflösung',
    async ausfuehren(ktx) {
      const treffer = await werkzeuge.dnsAufloesung(ZIELE.dnsPruefName);
      if (treffer.ok) {
        const langsam = treffer.ms > 1000;
        return {
          status: langsam ? 'warnung' : 'ok',
          meldung: langsam
            ? `Namensauflösung funktioniert, ist mit ${treffer.ms} ms aber träge.`
            : `${ZIELE.dnsPruefName} → ${treffer.adressen.join(', ')} (${treffer.ms} ms).`,
          details: treffer,
          reparaturen: langsam ? ['dns-fallback'] : [],
        };
      }

      const internetOk = ergebnis(ktx, 'internet-tcp')?.status === 'ok';
      return {
        status: 'fehler',
        meldung: internetOk
          ? `Namensauflösung schlägt fehl (${treffer.fehler}), obwohl das Internet per IP erreichbar ist – der DNS-Server ist die Ursache.`
          : `Namensauflösung schlägt fehl (${treffer.fehler}).`,
        details: treffer,
        reparaturen: internetOk ? ['dns-fallback'] : [],
      };
    },
  },

  {
    id: 'dns-oeffentlich',
    titel: 'Namensauflösung (öffentlicher DNS)',
    gruppe: 'Namensauflösung',
    async ausfuehren(ktx) {
      if (ergebnis(ktx, 'internet-tcp')?.status === 'fehler') {
        return { status: 'uebersprungen', meldung: 'Ohne Internet-Verbindung nicht aussagekräftig.', details: {} };
      }

      const treffer = await werkzeuge.dnsUeberServer(ZIELE.dnsPruefName, ZIELE.oeffentlicherDns);
      const systemOk = ergebnis(ktx, 'dns-system')?.status === 'ok';

      if (!treffer.ok) {
        return {
          status: systemOk ? 'info' : 'warnung',
          meldung: `Öffentlicher DNS ${ZIELE.oeffentlicherDns} antwortet nicht (${treffer.fehler}) – oft blockiert das Netz fremde DNS-Server.`,
          details: treffer,
        };
      }

      return {
        status: 'ok',
        meldung: `Öffentlicher DNS ${ZIELE.oeffentlicherDns} antwortet in ${treffer.ms} ms.`,
        details: { ...treffer, alsFallbackNutzbar: true },
        reparaturen: systemOk ? [] : ['dns-fallback'],
      };
    },
  },

  {
    id: 'https',
    titel: 'HTTPS-Abruf',
    gruppe: 'Internet',
    async ausfuehren(ktx) {
      if (ergebnis(ktx, 'dns-system')?.status === 'fehler') {
        return { status: 'uebersprungen', meldung: 'Ohne Namensauflösung nicht prüfbar.', details: {} };
      }

      const treffer = await werkzeuge.httpAbruf(ZIELE.httpsZiel, { zeitlimit: 8000 });
      if (!treffer.ok) {
        const zertifikatsproblem = /certificate|self-signed|CERT_/i.test(treffer.fehler || '');
        return {
          status: 'fehler',
          meldung: zertifikatsproblem
            ? `TLS-Fehler: ${treffer.fehler}. Deutet auf einen aufbrechenden Proxy oder eine falsche Systemzeit hin.`
            : `HTTPS-Abruf fehlgeschlagen: ${treffer.fehler}`,
          details: { ...treffer, zertifikatsproblem },
        };
      }

      if (treffer.status >= 400) {
        return {
          status: 'warnung',
          meldung: `HTTPS antwortet mit Status ${treffer.status} – Zugriff wird möglicherweise gefiltert.`,
          details: treffer,
        };
      }

      return {
        status: 'ok',
        meldung: `HTTPS in Ordnung (Status ${treffer.status}, ${treffer.ms} ms).`,
        details: treffer,
      };
    },
  },

  {
    id: 'captive-portal',
    titel: 'Anmeldeseite (Captive Portal)',
    gruppe: 'Internet',
    async ausfuehren(ktx) {
      if (ergebnis(ktx, 'internet-tcp')?.status === 'fehler') {
        return { status: 'uebersprungen', meldung: 'Ohne Internet-Verbindung nicht prüfbar.', details: {} };
      }

      const treffer = await werkzeuge.httpAbruf(ZIELE.portalZiel, { zeitlimit: 6000 });
      if (!treffer.ok) {
        return { status: 'warnung', meldung: `Portal-Prüfung nicht möglich: ${treffer.fehler}`, details: treffer };
      }

      // Erwartet wird 204 ohne Inhalt. Alles andere heisst: jemand antwortet
      // stellvertretend -- typischerweise die Anmeldeseite eines WLANs.
      if (treffer.status === 204) {
        return { status: 'ok', meldung: 'Keine Anmeldeseite dazwischen, das Netz ist offen.', details: treffer };
      }

      const beschreibung = `Statt 204 kam Status ${treffer.status}${treffer.ort ? ` (Weiterleitung nach ${treffer.ort})` : ''}`;

      // Ein aktiver Unternehmens- oder Entwicklungsproxy antwortet ebenfalls
      // stellvertretend. Das ist Filterung, keine Anmeldeseite.
      if (ergebnis(ktx, 'proxy')?.status === 'info') {
        return {
          status: 'warnung',
          meldung: `${beschreibung} – vermutlich filtert der eingetragene Proxy, nicht eine Anmeldeseite.`,
          details: { ...treffer, ursache: 'proxy' },
        };
      }

      return {
        status: 'fehler',
        meldung: `${beschreibung} – das Netz verlangt vermutlich eine Anmeldung im Browser.`,
        details: { ...treffer, ursache: 'portal' },
      };
    },
  },

  {
    id: 'latenz',
    titel: 'Verbindungsqualität',
    gruppe: 'Qualität',
    async ausfuehren(ktx) {
      if (ergebnis(ktx, 'internet-tcp')?.status === 'fehler') {
        return { status: 'uebersprungen', meldung: 'Ohne Internet-Verbindung nicht messbar.', details: {} };
      }

      const messung = await werkzeuge.messeLatenz(
        ZIELE.latenzZiel.host,
        ZIELE.latenzZiel.port,
        ktx.optionen.messungen,
      );

      if (messung.erfolge === 0) {
        return { status: 'fehler', meldung: `Keine Messung möglich: ${messung.fehler}`, details: messung };
      }

      const verlustProzent = Math.round(messung.verlustQuote * 100);
      const beschreibung = `Ø ${messung.schnitt} ms, Jitter ${messung.jitter} ms, Verlust ${verlustProzent} %`;

      if (messung.verlustQuote > 0.25 || messung.schnitt > 400 || messung.jitter > 150) {
        return { status: 'warnung', meldung: `Instabile Verbindung – ${beschreibung}.`, details: messung };
      }

      return { status: 'ok', meldung: `Verbindung stabil – ${beschreibung}.`, details: messung };
    },
  },
];

module.exports = { pruefungen, ZIELE };
