'use strict';

/**
 * Tests fuer den Netzdoktor.
 *
 * Bewusst ohne echten Internetzugriff, wo es geht: die Testlaeufe sollen auch
 * offline und unter Termux durchlaufen. Geprueft wird die Logik -- Auswertung,
 * Diagnose, Argumente --, nicht die Erreichbarkeit fremder Server.
 */

const test = require('node:test');
const assert = require('node:assert');
const net = require('node:net');

const werkzeuge = require('../netz/werkzeuge');
const { folgereDiagnose } = require('../netz/analyse');
const { leseArgumente } = require('../netz/cli');
const { pruefungen } = require('../netz/pruefungen');
const { reparaturen } = require('../netz/reparaturen');

/** Hilfsserver, der eine Verbindung sofort wieder schliesst. */
function starteTestServer() {
  return new Promise((aufloesen) => {
    const server = net.createServer((verbindung) => verbindung.end());
    server.listen(0, '127.0.0.1', () => aufloesen({ server, port: server.address().port }));
  });
}

test('tcpVerbindung erkennt einen erreichbaren Port', async () => {
  const { server, port } = await starteTestServer();
  try {
    const ergebnis = await werkzeuge.tcpVerbindung('127.0.0.1', port, 2000);
    assert.equal(ergebnis.ok, true);
    assert.ok(ergebnis.ms >= 0);
  } finally {
    server.close();
  }
});

test('tcpVerbindung meldet einen geschlossenen Port als Fehler', async () => {
  const { server, port } = await starteTestServer();
  await new Promise((aufloesen) => server.close(aufloesen));

  const ergebnis = await werkzeuge.tcpVerbindung('127.0.0.1', port, 2000);
  assert.equal(ergebnis.ok, false);
  assert.equal(ergebnis.code, 'ECONNREFUSED');
});

test('portFrei unterscheidet belegte und freie Ports', async () => {
  const { server, port } = await starteTestServer();
  try {
    const belegt = await werkzeuge.portFrei(port, '127.0.0.1');
    assert.equal(belegt.frei, false);
  } finally {
    await new Promise((aufloesen) => server.close(aufloesen));
  }

  const frei = await werkzeuge.portFrei(port, '127.0.0.1');
  assert.equal(frei.frei, true);
});

test('messeLatenz liefert Kennzahlen ueber mehrere Versuche', async () => {
  const { server, port } = await starteTestServer();
  try {
    const messung = await werkzeuge.messeLatenz('127.0.0.1', port, 3, 2000);
    assert.equal(messung.erfolge, 3);
    assert.equal(messung.verlustQuote, 0);
    assert.ok(messung.schnitt >= 0);
    assert.ok(messung.jitter >= 0);
  } finally {
    server.close();
  }
});

test('befehl bricht bei einem fehlenden Programm nicht ab', async () => {
  const ergebnis = await werkzeuge.befehl('gibtesnichtxyz', ['--version']);
  assert.equal(ergebnis.ok, false);
  assert.ok(ergebnis.fehler);
});

test('schnittstellen liefert mindestens das Loopback-Gerät', () => {
  const liste = werkzeuge.schnittstellen();
  assert.ok(liste.length > 0);
  assert.ok(liste.some((s) => s.intern));
});

test('Diagnose nennt die unterste gestörte Ebene zuerst', () => {
  const ergebnisse = new Map([
    ['schnittstellen', { status: 'ok' }],
    ['loopback', { status: 'ok' }],
    ['gateway', { status: 'fehler' }],
    ['internet-tcp', { status: 'fehler' }],
    ['dns-system', { status: 'fehler' }],
  ]);

  const diagnose = folgereDiagnose(ergebnisse);
  assert.equal(diagnose.ebene, 'Heimnetz');
  assert.match(diagnose.text, /Router/);
});

test('Diagnose erkennt reines DNS-Problem bei erreichbarem Internet', () => {
  const ergebnisse = new Map([
    ['schnittstellen', { status: 'ok' }],
    ['loopback', { status: 'ok' }],
    ['gateway', { status: 'ok' }],
    ['internet-tcp', { status: 'ok' }],
    ['dns-system', { status: 'fehler' }],
  ]);

  const diagnose = folgereDiagnose(ergebnisse);
  assert.equal(diagnose.ebene, 'Namensauflösung');
  assert.match(diagnose.text, /dns-fallback/);
});

test('Diagnose meldet bei lauter OK-Ergebnissen keinen Handlungsbedarf', () => {
  const ergebnisse = new Map([
    ['schnittstellen', { status: 'ok' }],
    ['internet-tcp', { status: 'ok' }],
    ['dns-system', { status: 'ok' }],
  ]);
  assert.equal(folgereDiagnose(ergebnisse).ebene, 'Alles in Ordnung');
});

test('leseArgumente wertet Befehl und Optionen aus', () => {
  const optionen = leseArgumente(['reparieren', '--anwenden', '--ja', '--nur', 'dns-fallback,ipv4-bevorzugen']);
  assert.equal(optionen.befehl, 'reparieren');
  assert.equal(optionen.anwenden, true);
  assert.equal(optionen.ja, true);
  assert.deepEqual(optionen.nur, ['dns-fallback', 'ipv4-bevorzugen']);
});

test('leseArgumente nutzt "analyse" als Vorgabe', () => {
  const optionen = leseArgumente(['--json']);
  assert.equal(optionen.befehl, 'analyse');
  assert.equal(optionen.json, true);
  assert.equal(optionen.anwenden, false);
});

test('Prüfungen und Reparaturen haben eindeutige IDs', () => {
  const pruefIds = pruefungen.map((p) => p.id);
  const reparaturIds = reparaturen.map((r) => r.id);
  assert.equal(new Set(pruefIds).size, pruefIds.length);
  assert.equal(new Set(reparaturIds).size, reparaturIds.length);
});

test('Jede von einer Prüfung genannte Reparatur existiert auch', () => {
  const bekannt = new Set(reparaturen.map((r) => r.id));
  const genannt = new Set();

  // Die IDs stehen als Zeichenketten im Quelltext der Prüfungen.
  const quelltext = require('node:fs').readFileSync(require.resolve('../netz/pruefungen.js'), 'utf8');
  for (const treffer of quelltext.matchAll(/reparaturen:\s*\[([^\]]*)\]/g)) {
    for (const id of treffer[1].matchAll(/'([^']+)'/g)) genannt.add(id[1]);
  }

  assert.ok(genannt.size > 0, 'Es sollte mindestens eine Prüfung eine Reparatur vorschlagen.');
  for (const id of genannt) {
    assert.ok(bekannt.has(id), `Unbekannte Reparatur-ID in pruefungen.js: ${id}`);
  }
});

test('Reparaturen tragen eine gültige Risikostufe', () => {
  for (const reparatur of reparaturen) {
    assert.ok(['niedrig', 'mittel', 'hoch'].includes(reparatur.risiko), `${reparatur.id}: ${reparatur.risiko}`);
    assert.equal(typeof reparatur.pruefen, 'function');
    assert.equal(typeof reparatur.anwenden, 'function');
  }
});

test('Riskante Reparaturen verlangen eine Bestätigung', () => {
  for (const reparatur of reparaturen.filter((r) => r.risiko !== 'niedrig')) {
    assert.equal(reparatur.bestaetigungNoetig, true, `${reparatur.id} müsste eine Bestätigung verlangen.`);
  }
});

test('istGlobalesIPv6 unterscheidet routbare von lokalen Adressen', () => {
  const global = ['2003:a:b::1', '2606:4700:4700::1111', '3fff::1', '2000::1'];
  const lokal = ['fe80::1', 'fe80::1%wlan0', 'fc00::1', 'fd12:3456::1', '::1', 'ff02::1', '192.168.1.1', '', null];

  for (const adresse of global) {
    assert.equal(werkzeuge.istGlobalesIPv6(adresse), true, `${adresse} sollte global sein`);
  }
  for (const adresse of lokal) {
    assert.equal(werkzeuge.istGlobalesIPv6(adresse), false, `${adresse} sollte nicht global sein`);
  }
});

test('eine reine fe80-Adresse löst keine IPv6-Warnung aus', async () => {
  const { pruefungen: liste } = require('../netz/pruefungen');
  const schnittstellen = liste.find((p) => p.id === 'schnittstellen');
  const ipv6Pruefung = liste.find((p) => p.id === 'ipv6');

  // Schnittstellen so vorgeben, wie ein Handy im WLAN sie meldet:
  // eine IPv4-Adresse plus die Link-Local-Adresse, die jedes Gerät hat.
  const echt = werkzeuge.schnittstellen;
  werkzeuge.schnittstellen = () => [
    { name: 'wlan0', adresse: '192.168.1.20', familie: 'IPv4', intern: false, netzmaske: '255.255.255.0' },
    { name: 'wlan0', adresse: 'fe80::1c2b:3aff:fe4d:5e6f', familie: 'IPv6', intern: false },
    { name: 'lo', adresse: '127.0.0.1', familie: 'IPv4', intern: true },
  ];

  try {
    const ktx = { optionen: { port: 3000, messungen: 1 }, ergebnisse: new Map() };
    const ergebnisSchnittstellen = await schnittstellen.ausfuehren(ktx);
    ktx.ergebnisse.set('schnittstellen', ergebnisSchnittstellen);

    assert.equal(ergebnisSchnittstellen.details.ipv6Vorhanden, false);
    assert.equal(ergebnisSchnittstellen.details.ipv6NurLokal, true);

    const ergebnisIpv6 = await ipv6Pruefung.ausfuehren(ktx);
    assert.equal(ergebnisIpv6.status, 'uebersprungen');
    assert.match(ergebnisIpv6.meldung, /Link-Local/);
  } finally {
    werkzeuge.schnittstellen = echt;
  }
});

test('eine globale IPv6-Adresse wird weiterhin geprüft', async () => {
  const { pruefungen: liste } = require('../netz/pruefungen');
  const schnittstellen = liste.find((p) => p.id === 'schnittstellen');

  const echt = werkzeuge.schnittstellen;
  werkzeuge.schnittstellen = () => [
    { name: 'wlan0', adresse: '192.168.1.20', familie: 'IPv4', intern: false, netzmaske: '255.255.255.0' },
    { name: 'wlan0', adresse: 'fe80::1', familie: 'IPv6', intern: false },
    { name: 'wlan0', adresse: '2003:ab:cd::42', familie: 'IPv6', intern: false },
  ];

  try {
    const ergebnis = await schnittstellen.ausfuehren({ optionen: {}, ergebnisse: new Map() });
    assert.equal(ergebnis.details.ipv6Vorhanden, true);
    assert.equal(ergebnis.details.ipv6NurLokal, false);
    assert.deepEqual(ergebnis.details.ipv6Adressen, ['2003:ab:cd::42']);
    assert.match(ergebnis.meldung, /2003:ab:cd::42/);
  } finally {
    werkzeuge.schnittstellen = echt;
  }
});

test('stufeIPv6 trennt 6to4 von nativem IPv6', () => {
  assert.equal(werkzeuge.stufeIPv6('2a00:20:800e:9b4b::1'), 'global');
  assert.equal(werkzeuge.stufeIPv6('2002:bcc3:af77::1'), '6to4');
  assert.equal(werkzeuge.stufeIPv6('fe80::1'), 'link-local');
  assert.equal(werkzeuge.stufeIPv6('fd70:8096:13d9::1'), 'ula');
  assert.equal(werkzeuge.stufeIPv6('ff02::1'), 'multicast');
  assert.equal(werkzeuge.stufeIPv6('192.168.1.1'), 'unbekannt');
});

test('ein Router mit 6to4-Tunnel wird als solcher benannt', async () => {
  const { pruefungen: liste } = require('../netz/pruefungen');
  const schnittstellen = liste.find((p) => p.id === 'schnittstellen');
  const ipv6Pruefung = liste.find((p) => p.id === 'ipv6');

  // Echte Adressen eines Handys an einer FritzBox mit 6to4-Tunnel.
  const echteSchnittstellen = werkzeuge.schnittstellen;
  const echteVerbindung = werkzeuge.tcpVerbindung;
  werkzeuge.schnittstellen = () => [
    { name: 'wlan0', adresse: '192.168.178.38', familie: 'IPv4', intern: false, netzmaske: '255.255.255.0' },
    { name: 'wlan0', adresse: 'fe80::68ba:b12a:48cd:e474', familie: 'IPv6', intern: false },
    { name: 'wlan0', adresse: '2002:bcc3:af77:0:5797:5da4:4d82:8180', familie: 'IPv6', intern: false },
    { name: 'wlan0', adresse: 'fd70:8096:13d9:0:4878:4307:8861:f48d', familie: 'IPv6', intern: false },
  ];
  // Das IPv6-Ziel bleibt erwartungsgemäß unerreichbar.
  werkzeuge.tcpVerbindung = async () => ({ ok: false, fehler: 'Zeitlimit überschritten', ms: 4000 });

  try {
    const ktx = { optionen: { port: 3000, messungen: 1 }, ergebnisse: new Map() };
    const ergebnisSchnittstellen = await schnittstellen.ausfuehren(ktx);
    ktx.ergebnisse.set('schnittstellen', ergebnisSchnittstellen);
    ktx.ergebnisse.set('internet-tcp', { status: 'ok' });

    assert.equal(ergebnisSchnittstellen.details.ipv6Vorhanden, true, '6to4 ist formal eine globale Adresse');
    assert.equal(ergebnisSchnittstellen.details.ipv6Nur6to4, true);

    const ergebnisIpv6 = await ipv6Pruefung.ausfuehren(ktx);
    assert.equal(ergebnisIpv6.status, 'warnung');
    assert.equal(ergebnisIpv6.details.ursache, '6to4');
    assert.match(ergebnisIpv6.meldung, /6to4/);
    assert.match(ergebnisIpv6.meldung, /native IPv6-Anbindung/);
  } finally {
    werkzeuge.schnittstellen = echteSchnittstellen;
    werkzeuge.tcpVerbindung = echteVerbindung;
  }
});

test('natives IPv6 wird nicht als 6to4 gemeldet', async () => {
  const { pruefungen: liste } = require('../netz/pruefungen');
  const schnittstellen = liste.find((p) => p.id === 'schnittstellen');

  const echt = werkzeuge.schnittstellen;
  werkzeuge.schnittstellen = () => [
    { name: 'rmnet_data4', adresse: '100.71.169.12', familie: 'IPv4', intern: false, netzmaske: '255.255.255.248' },
    { name: 'rmnet_data4', adresse: '2a00:20:800e:9b4b:d0d8:2ff:fe65:657b', familie: 'IPv6', intern: false },
  ];

  try {
    const ergebnis = await schnittstellen.ausfuehren({ optionen: {}, ergebnisse: new Map() });
    assert.equal(ergebnis.details.ipv6Vorhanden, true);
    assert.equal(ergebnis.details.ipv6Nur6to4, false);
  } finally {
    werkzeuge.schnittstellen = echt;
  }
});
