'use strict';

/**
 * Ablaufsteuerung: fuehrt die Pruefungen aus, leitet daraus eine Diagnose ab
 * und wendet auf Wunsch Reparaturen an.
 */

const { pruefungen } = require('./pruefungen');
const reparaturModul = require('./reparaturen');
const konfig = require('./konfig');

const VORGABE_OPTIONEN = {
  port: Number(process.env.PORT) || 3000,
  messungen: 4,
  nur: null, // Liste von Pruefungs-IDs
};

/**
 * Leitet aus den Einzelergebnissen eine Aussage zur Ursache ab.
 * Die Reihenfolge entspricht dem Weg des Datenpakets: Was zuerst kaputt ist,
 * erklaert in aller Regel alles Nachfolgende.
 */
function folgereDiagnose(ergebnisse) {
  const holen = (id) => ergebnisse.get(id);
  const kaputt = (id) => holen(id)?.status === 'fehler';

  if (kaputt('schnittstellen')) {
    return {
      ebene: 'Gerät',
      text: 'Das Gerät hat keine brauchbare Netzwerkadresse. WLAN oder mobile Daten einschalten und die Verbindung neu aufbauen.',
    };
  }
  if (kaputt('loopback')) {
    return {
      ebene: 'Gerät',
      text: 'Schon der lokale Netzwerk-Stack antwortet nicht. Das ist ein Problem des Systems, nicht der Internetverbindung.',
    };
  }
  if (kaputt('proxy')) {
    return {
      ebene: 'Gerät',
      text: 'Ein eingetragener Proxy ist nicht erreichbar. Solange er gesetzt ist, laufen alle Verbindungen dorthin ins Leere.',
    };
  }
  if (kaputt('gateway')) {
    return {
      ebene: 'Heimnetz',
      text: 'Der Router antwortet nicht. Die Verbindung endet also schon im eigenen Netz – Router und WLAN-Verbindung prüfen.',
    };
  }
  if (kaputt('captive-portal')) {
    return {
      ebene: 'Heimnetz',
      text: 'Das Netz fängt die Verbindungen ab und verlangt eine Anmeldung. Einmal im Browser eine beliebige Seite öffnen und anmelden.',
    };
  }
  if (kaputt('internet-tcp')) {
    return {
      ebene: 'Internet',
      text: 'Der Router ist erreichbar, das Internet dahinter nicht. Die Störung liegt beim Anschluss oder beim Anbieter.',
    };
  }
  if (kaputt('dns-system')) {
    return {
      ebene: 'Namensauflösung',
      text: 'Das Internet ist per IP-Adresse erreichbar, nur Namen werden nicht aufgelöst. Ein anderer DNS-Server behebt das (Reparatur "dns-fallback").',
    };
  }
  if (kaputt('https')) {
    return {
      ebene: 'Internet',
      text: 'Namen werden aufgelöst, aber HTTPS scheitert. Meist steckt ein aufbrechender Proxy oder eine falsche Systemzeit dahinter.',
    };
  }
  if (kaputt('ipv6')) {
    return {
      ebene: 'Internet',
      text: 'IPv6 ist eingerichtet, aber nicht nutzbar, und IPv4 hilft nicht aus. Reparatur "ipv4-bevorzugen" umgeht das.',
    };
  }

  const warnungen = [...ergebnisse.values()].filter((e) => e.status === 'warnung');
  if (warnungen.length > 0) {
    return {
      ebene: 'Qualität',
      text: `Grundsätzlich funktioniert alles, aber ${warnungen.length} Prüfung(en) melden Auffälligkeiten – Details in der Liste.`,
    };
  }

  return { ebene: 'Alles in Ordnung', text: 'Alle Prüfungen bestanden. Die Netzwerkverbindung ist unauffällig.' };
}

function gesamtstatus(ergebnisse) {
  const werte = [...ergebnisse.values()].map((e) => e.status);
  if (werte.includes('fehler')) return 'fehler';
  if (werte.includes('warnung')) return 'warnung';
  return 'ok';
}

/** Fuehrt die komplette Analyse aus. */
async function analysiere(optionen = {}) {
  const einstellungen = { ...VORGABE_OPTIONEN, ...optionen };
  const angewandteKonfig = konfig.anwenden();

  const ktx = { optionen: einstellungen, ergebnisse: new Map() };
  const auswahl = einstellungen.nur
    ? pruefungen.filter((p) => einstellungen.nur.includes(p.id))
    : pruefungen;

  const beginn = Date.now();
  const liste = [];

  for (const pruefung of auswahl) {
    const start = performance.now();
    let ergebnis;
    try {
      ergebnis = await pruefung.ausfuehren(ktx);
    } catch (fehler) {
      // Eine abstuerzende Pruefung darf die Analyse nicht beenden.
      ergebnis = {
        status: 'fehler',
        meldung: `Prüfung abgebrochen: ${fehler.message}`,
        details: { stapel: fehler.stack },
      };
    }

    const eintrag = {
      id: pruefung.id,
      titel: pruefung.titel,
      gruppe: pruefung.gruppe,
      dauerMs: Math.round(performance.now() - start),
      reparaturen: [],
      details: {},
      ...ergebnis,
    };

    ktx.ergebnisse.set(pruefung.id, eintrag);
    liste.push(eintrag);
    if (typeof einstellungen.beiFortschritt === 'function') einstellungen.beiFortschritt(eintrag);
  }

  const vorschlaege = await reparaturModul.bewerte(ktx);

  return {
    zeitpunkt: new Date().toISOString(),
    dauerMs: Date.now() - beginn,
    gesamtstatus: gesamtstatus(ktx.ergebnisse),
    diagnose: folgereDiagnose(ktx.ergebnisse),
    zusammenfassung: {
      gesamt: liste.length,
      ok: liste.filter((e) => e.status === 'ok').length,
      warnungen: liste.filter((e) => e.status === 'warnung').length,
      fehler: liste.filter((e) => e.status === 'fehler').length,
      uebersprungen: liste.filter((e) => e.status === 'uebersprungen').length,
    },
    aktiveKonfiguration: angewandteKonfig,
    pruefungen: liste,
    reparaturvorschlaege: vorschlaege,
    _ktx: ktx,
  };
}

/**
 * Wendet Reparaturen an. Ohne `anwenden: true` bleibt es bei einer Simulation.
 * Riskante Massnahmen verlangen zusaetzlich `bestaetigt: true`.
 */
async function repariere(bericht, { anwenden = false, bestaetigt = false, nur = null } = {}) {
  const ktx = bericht._ktx || { optionen: VORGABE_OPTIONEN, ergebnisse: new Map() };
  const ergebnisse = [];

  for (const vorschlag of bericht.reparaturvorschlaege) {
    if (nur && !nur.includes(vorschlag.id)) continue;

    if (!vorschlag.anwendbar) {
      ergebnisse.push({ ...vorschlag, ausgefuehrt: false, meldung: vorschlag.grund, uebersprungen: true });
      continue;
    }

    if (vorschlag.bestaetigungNoetig && anwenden && !bestaetigt) {
      ergebnisse.push({
        ...vorschlag,
        ausgefuehrt: false,
        uebersprungen: true,
        meldung: `Übersprungen: "${vorschlag.titel}" ist als ${vorschlag.risiko} eingestuft und braucht die ausdrückliche Bestätigung (--ja).`,
      });
      continue;
    }

    const reparatur = reparaturModul.nachId(vorschlag.id);
    const ausgang = await reparatur.anwenden(ktx, { simulieren: !anwenden });
    ergebnisse.push({ ...vorschlag, ausgefuehrt: anwenden, simuliert: !anwenden, ...ausgang });
  }

  return { simuliert: !anwenden, ergebnisse };
}

module.exports = { analysiere, repariere, folgereDiagnose, VORGABE_OPTIONEN };
