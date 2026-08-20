'use strict';

/**
 * Reparaturmassnahmen.
 *
 * Grundsaetze:
 *  - Nichts wird ungefragt geaendert. Ohne --anwenden laeuft alles als
 *    Simulation und zeigt nur, was passieren wuerde.
 *  - Ohne Root-Rechte (Termux!) laesst sich die Systemkonfiguration nicht
 *    anfassen. Die Korrekturen wirken deshalb auf Prozessebene und werden in
 *    netz-konfig.json festgehalten, damit sie den Neustart ueberleben.
 *  - Alles, was fremde Prozesse beendet, gilt als riskant und verlangt eine
 *    ausdrueckliche Bestaetigung.
 */

const aufloeser = require('./aufloeser');
const konfig = require('./konfig');
const werkzeuge = require('./werkzeuge');

const DNS_KANDIDATEN = [
  { name: 'Cloudflare', adresse: '1.1.1.1' },
  { name: 'Google', adresse: '8.8.8.8' },
  { name: 'Quad9', adresse: '9.9.9.9' },
];

/** Ergebnis einer Pruefung nachschlagen. */
function ergebnis(ktx, id) {
  return ktx.ergebnisse.get(id);
}

const reparaturen = [
  {
    id: 'dns-fallback',
    titel: 'Auf funktionierenden DNS-Server umstellen',
    beschreibung:
      'Sucht einen erreichbaren öffentlichen DNS-Server und trägt ihn als Auflöser für diese Anwendung ein.',
    risiko: 'niedrig',
    bestaetigungNoetig: false,

    async pruefen(ktx) {
      const system = ergebnis(ktx, 'dns-system');
      if (!system || system.status === 'ok') {
        return { anwendbar: false, grund: 'Die Namensauflösung des Systems funktioniert bereits.' };
      }
      if (ergebnis(ktx, 'internet-tcp')?.status === 'fehler') {
        return { anwendbar: false, grund: 'Ohne Internet-Verbindung bringt ein anderer DNS-Server nichts.' };
      }
      return { anwendbar: true, grund: 'Die Namensauflösung ist gestört, das Internet aber erreichbar.' };
    },

    async anwenden(ktx, { simulieren }) {
      const schritte = [];
      const funktionierend = [];

      for (const kandidat of DNS_KANDIDATEN) {
        const treffer = await werkzeuge.dnsUeberServer('example.com', kandidat.adresse, 4000);
        schritte.push(
          `${kandidat.name} (${kandidat.adresse}): ${treffer.ok ? `antwortet in ${treffer.ms} ms` : `keine Antwort (${treffer.fehler})`}`,
        );
        if (treffer.ok) funktionierend.push(kandidat.adresse);
      }

      if (funktionierend.length === 0) {
        return {
          erfolg: false,
          meldung: 'Kein öffentlicher DNS-Server erreichbar – vermutlich sperrt das Netz fremde Auflöser.',
          schritte,
        };
      }

      if (simulieren) {
        return {
          erfolg: true,
          meldung: `Würde ${funktionierend.join(', ')} als DNS-Server eintragen.`,
          schritte,
        };
      }

      konfig.speichere({ dnsServer: funktionierend });
      aufloeser.aktiviere(funktionierend);
      schritte.push('dns.lookup umgeleitet – wirkt auch auf Verbindungen über net, http und fetch.');
      schritte.push(`In ${konfig.KONFIG_DATEI} gespeichert und sofort aktiv.`);

      const nachher = await werkzeuge.dnsAufloesung('example.com');
      return {
        erfolg: nachher.ok,
        meldung: nachher.ok
          ? `DNS-Server auf ${funktionierend.join(', ')} umgestellt – Auflösung funktioniert wieder (${nachher.ms} ms).`
          : `Umgestellt, die Auflösung schlägt aber weiterhin fehl: ${nachher.fehler}`,
        schritte,
      };
    },
  },

  {
    id: 'ipv4-bevorzugen',
    titel: 'IPv4 bevorzugen',
    beschreibung:
      'Stellt die Adressreihenfolge auf "ipv4first" um, damit Verbindungen nicht in ein totes IPv6-Netz laufen.',
    risiko: 'niedrig',
    bestaetigungNoetig: false,

    async pruefen(ktx) {
      const ipv6 = ergebnis(ktx, 'ipv6');
      if (!ipv6 || ipv6.status === 'ok' || ipv6.status === 'uebersprungen') {
        return { anwendbar: false, grund: 'IPv6 ist entweder in Ordnung oder gar nicht im Einsatz.' };
      }
      if (konfig.lade().ergebnisReihenfolge === 'ipv4first') {
        return { anwendbar: false, grund: 'IPv4 wird bereits bevorzugt.' };
      }
      return { anwendbar: true, grund: 'IPv6 ist konfiguriert, aber nicht nutzbar.' };
    },

    async anwenden(ktx, { simulieren }) {
      if (simulieren) {
        return {
          erfolg: true,
          meldung: 'Würde die Adressreihenfolge auf "ipv4first" setzen.',
          schritte: ['dns.setDefaultResultOrder("ipv4first")'],
        };
      }

      konfig.speichere({ ergebnisReihenfolge: 'ipv4first' });
      require('node:dns').setDefaultResultOrder('ipv4first');
      return {
        erfolg: true,
        meldung: 'IPv4 wird jetzt bevorzugt. Verbindungen laufen nicht mehr in IPv6-Zeitlimits.',
        schritte: ['Adressreihenfolge gesetzt', `In ${konfig.KONFIG_DATEI} gespeichert`],
      };
    },
  },

  {
    id: 'proxy-bereinigen',
    titel: 'Defekte Proxy-Einstellungen entfernen',
    beschreibung:
      'Entfernt nicht erreichbare Proxy-Variablen aus der Umgebung dieses Prozesses und zeigt den passenden Shell-Befehl.',
    risiko: 'mittel',
    bestaetigungNoetig: true,

    async pruefen(ktx) {
      const proxy = ergebnis(ktx, 'proxy');
      if (!proxy || proxy.status !== 'fehler') {
        return { anwendbar: false, grund: 'Es gibt keinen defekten Proxy-Eintrag.' };
      }
      return { anwendbar: true, grund: 'Der eingetragene Proxy ist nicht erreichbar.' };
    },

    async anwenden(ktx, { simulieren }) {
      const proxy = ergebnis(ktx, 'proxy');
      const betroffen = (proxy?.details?.ziele || []).filter((z) => !z.erreichbar).map((z) => z.name);
      const schritte = betroffen.map((name) => `unset ${name}`);

      if (simulieren) {
        return {
          erfolg: true,
          meldung: `Würde ${betroffen.join(', ')} entfernen.`,
          schritte,
        };
      }

      for (const name of betroffen) delete process.env[name];
      konfig.speichere({ proxyBereinigt: true });

      return {
        erfolg: true,
        meldung: `${betroffen.join(', ')} entfernt. Für die Shell dauerhaft: "${schritte.join(' && ')}" in die ~/.bashrc eintragen.`,
        schritte: [...schritte, `In ${konfig.KONFIG_DATEI} gespeichert`],
      };
    },
  },

  {
    id: 'port-freigeben',
    titel: 'Belegten Port freigeben',
    beschreibung: 'Beendet den Prozess, der den Port der Anwendung blockiert (SIGTERM).',
    risiko: 'hoch',
    bestaetigungNoetig: true,

    async pruefen(ktx) {
      const portPruefung = ergebnis(ktx, 'anwendungs-port');
      if (!portPruefung || portPruefung.status !== 'warnung') {
        return { anwendbar: false, grund: 'Der Port ist frei oder wird von der eigenen Anwendung bedient.' };
      }
      const prozesse = (portPruefung.details?.prozesse || []).filter((p) => p.pid !== process.pid);
      if (prozesse.length === 0) {
        return {
          anwendbar: false,
          grund: 'Der blockierende Prozess ist nicht ermittelbar (lsof/ss fehlen oder es ist dieser Prozess selbst).',
        };
      }
      return { anwendbar: true, grund: `Blockiert von: ${prozesse.map((p) => `${p.befehl} (PID ${p.pid})`).join(', ')}` };
    },

    async anwenden(ktx, { simulieren }) {
      const portPruefung = ergebnis(ktx, 'anwendungs-port');
      const port = portPruefung?.details?.port;
      const prozesse = (portPruefung?.details?.prozesse || []).filter((p) => p.pid !== process.pid);
      const schritte = [];

      if (simulieren) {
        return {
          erfolg: true,
          meldung: `Würde ${prozesse.map((p) => `PID ${p.pid} (${p.befehl})`).join(', ')} mit SIGTERM beenden.`,
          schritte: prozesse.map((p) => `kill ${p.pid}`),
        };
      }

      for (const prozess of prozesse) {
        try {
          process.kill(prozess.pid, 'SIGTERM');
          schritte.push(`SIGTERM an ${prozess.befehl} (PID ${prozess.pid}) gesendet.`);
        } catch (fehler) {
          schritte.push(`PID ${prozess.pid} nicht beendbar: ${fehler.message}`);
        }
      }

      // Kurz warten -- ein sauberes Herunterfahren braucht einen Moment.
      await new Promise((aufloesen) => setTimeout(aufloesen, 800));
      const frei = await werkzeuge.portFrei(port);

      return {
        erfolg: frei.frei,
        meldung: frei.frei
          ? `Port ${port} ist wieder frei.`
          : `Port ${port} ist weiterhin belegt – der Prozess reagiert nicht auf SIGTERM.`,
        schritte,
      };
    },
  },
];

/** Bewertet alle Reparaturen gegen den aktuellen Analysestand. */
async function bewerte(ktx) {
  const bewertet = [];
  for (const reparatur of reparaturen) {
    const pruefung = await reparatur.pruefen(ktx);
    bewertet.push({
      id: reparatur.id,
      titel: reparatur.titel,
      beschreibung: reparatur.beschreibung,
      risiko: reparatur.risiko,
      bestaetigungNoetig: reparatur.bestaetigungNoetig,
      ...pruefung,
    });
  }
  return bewertet;
}

function nachId(id) {
  return reparaturen.find((r) => r.id === id);
}

module.exports = { reparaturen, bewerte, nachId };
