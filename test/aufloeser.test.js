'use strict';

/**
 * Tests fuer den Ersatz-Auffloeser.
 *
 * Alle Faelle kommen ohne fremde DNS-Server aus: geprueft wird das Verhalten
 * der Umschaltung, nicht die Erreichbarkeit von 1.1.1.1.
 */

const test = require('node:test');
const assert = require('node:assert');
const dns = require('node:dns');
const util = require('node:util');

const aufloeser = require('../netz/aufloeser');

test.afterEach(() => aufloeser.deaktiviere());

test('leseArgumente versteht alle drei Aufrufformen', () => {
  const rueckruf = () => {};

  const nurRueckruf = aufloeser.leseArgumente('a', rueckruf, undefined);
  assert.deepEqual(nurRueckruf.optionen, {});
  assert.equal(nurRueckruf.rueckruf, rueckruf);

  const mitZahl = aufloeser.leseArgumente('a', 4, rueckruf);
  assert.deepEqual(mitZahl.optionen, { family: 4 });
  assert.equal(mitZahl.rueckruf, rueckruf);

  const mitObjekt = aufloeser.leseArgumente('a', { all: true }, rueckruf);
  assert.deepEqual(mitObjekt.optionen, { all: true });
});

test('aktiviere tauscht dns.lookup aus, deaktiviere stellt es wieder her', () => {
  const vorher = dns.lookup;

  assert.equal(aufloeser.aktiviere(['192.0.2.53']), true);
  assert.equal(aufloeser.istAktiv(), true);
  assert.notEqual(dns.lookup, vorher);

  assert.equal(aufloeser.deaktiviere(), true);
  assert.equal(aufloeser.istAktiv(), false);
  assert.equal(dns.lookup, vorher);
});

test('aktiviere lehnt eine leere Serverliste ab', () => {
  assert.equal(aufloeser.aktiviere([]), false);
  assert.equal(aufloeser.aktiviere(null), false);
  assert.equal(aufloeser.istAktiv(), false);
});

test('deaktiviere stellt die DNS-Server des Systems wieder her', () => {
  const vorher = dns.getServers();
  aufloeser.aktiviere(['192.0.2.53']);
  assert.deepEqual(dns.getServers(), ['192.0.2.53']);

  aufloeser.deaktiviere();
  assert.deepEqual(dns.getServers(), vorher);
});

test('localhost wird am Ersatz-Auflöser vorbeigereicht', async () => {
  // 192.0.2.53 ist per RFC 5737 nicht erreichbar. Käme localhost dort an,
  // liefe der Aufruf ins Zeitlimit statt sofort zu antworten.
  aufloeser.aktiviere(['192.0.2.53']);
  const treffer = await util.promisify(dns.lookup)('localhost');
  assert.ok(['127.0.0.1', '::1'].includes(treffer.address));
});

test('IP-Literale werden unverändert durchgereicht', async () => {
  aufloeser.aktiviere(['192.0.2.53']);
  const treffer = await util.promisify(dns.lookup)('93.184.216.34');
  assert.equal(treffer.address, '93.184.216.34');
  assert.equal(treffer.family, 4);
});

test('eine früh abgegriffene Referenz funktioniert auch nach dem Abschalten', async () => {
  aufloeser.aktiviere(['192.0.2.53']);
  const gemerkt = util.promisify(dns.lookup);

  aufloeser.deaktiviere();
  const treffer = await gemerkt('localhost');
  assert.ok(treffer.address);
});

test('promisify liefert weiterhin {address, family}', async () => {
  aufloeser.aktiviere(['192.0.2.53']);

  const treffer = await util.promisify(dns.lookup)('localhost');
  assert.equal(typeof treffer, 'object', 'promisify muss ein Objekt liefern, keine Zeichenkette');
  assert.ok(treffer.address);
  assert.ok([4, 6].includes(treffer.family));
});

test('dns.promises.lookup wird mit umgeschaltet und wieder zurückgesetzt', async () => {
  const vorher = dns.promises.lookup;

  aufloeser.aktiviere(['192.0.2.53']);
  assert.notEqual(dns.promises.lookup, vorher);
  const treffer = await dns.promises.lookup('localhost');
  assert.ok(treffer.address);

  aufloeser.deaktiviere();
  assert.equal(dns.promises.lookup, vorher);
});

test('all=true liefert eine Liste von Adressobjekten', async () => {
  aufloeser.aktiviere(['192.0.2.53']);
  const treffer = await util.promisify(dns.lookup)('localhost', { all: true });
  assert.ok(Array.isArray(treffer));
  assert.ok(treffer.every((e) => e.address && [4, 6].includes(e.family)));
});
