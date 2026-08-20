'use strict';

const test = require('node:test');
const assert = require('node:assert');

const { extrahiereMetadaten } = require('../lib/artikel-metadaten');
const { bereinigeTitel, sucheUrl, findeBibliothek } = require('../lib/bibliotheken');
const { holeArtikelSeite, pruefeUrl, istPrivateAdresse } = require('../lib/artikel-abruf');

const ARTIKEL_HTML = `
<html><head>
  <title>Irgendein Seitentitel</title>
  <meta property="og:site_name" content="DER SPIEGEL">
  <meta property="article:published_time" content="2026-08-01T09:12:00+02:00">
  <script type="application/ld+json">{"kaputt": </script>
  <script type="application/ld+json">
  {"@context":"https://schema.org","@type":"NewsArticle",
   "headline":"Die gro&szlig;e Frage nach dem Wandel",
   "datePublished":"2026-08-01T09:12:00+02:00",
   "author":[{"@type":"Person","name":"Erika Muster"}],
   "publisher":{"@type":"Organization","name":"DER SPIEGEL"},
   "articleBody":"HIER STEHT DER BEZAHLTE ARTIKELTEXT"}
  </script>
</head><body><p>HIER STEHT DER BEZAHLTE ARTIKELTEXT</p></body></html>`;

test('liest Kopfdaten aus JSON-LD', () => {
  const daten = extrahiereMetadaten(ARTIKEL_HTML);
  assert.strictEqual(daten.titel, 'Die große Frage nach dem Wandel');
  assert.strictEqual(daten.autor, 'Erika Muster');
  assert.strictEqual(daten.publikation, 'DER SPIEGEL');
  assert.strictEqual(daten.datum, '2026-08-01');
});

test('gibt den Artikeltext nicht weiter', () => {
  const daten = extrahiereMetadaten(ARTIKEL_HTML);
  assert.ok(!JSON.stringify(daten).includes('BEZAHLTE ARTIKELTEXT'));
});

test('faellt auf og- und title-Angaben zurueck', () => {
  const daten = extrahiereMetadaten(
    '<html><head><title>Nur der Titel</title></head><body></body></html>'
  );
  assert.strictEqual(daten.titel, 'Nur der Titel');
  assert.strictEqual(daten.autor, null);
  assert.strictEqual(daten.datum, null);
});

test('entfernt nur echte Zeitungs-Anhaengsel aus dem Titel', () => {
  assert.strictEqual(bereinigeTitel('Habeck im Interview - DER SPIEGEL'), 'Habeck im Interview');
  assert.strictEqual(bereinigeTitel('Was nun? | ZEIT ONLINE'), 'Was nun?');
  assert.strictEqual(bereinigeTitel('Rot-Grün-Debatte geht weiter'), 'Rot-Grün-Debatte geht weiter');
  assert.strictEqual(bereinigeTitel('Ein Titel - mit Gedankenstrich'), 'Ein Titel - mit Gedankenstrich');
});

test('baut die GENIOS-Suchadresse', () => {
  const url = sucheUrl('bib-voebb.genios.de', 'Habeck im Interview - DER SPIEGEL');
  assert.strictEqual(url, 'https://bib-voebb.genios.de/searchResult/Alle?requestText=Habeck+im+Interview');
});

test('weist unsaubere Domains ab', () => {
  assert.strictEqual(sucheUrl('evil.com/pfad?x=1', 'Titel'), null);
  assert.strictEqual(sucheUrl('bib-voebb.genios.de', '   '), null);
  assert.ok(findeBibliothek('voebb'));
  assert.strictEqual(findeBibliothek('gibtsnicht'), null);
});

test('erkennt Adressen aus dem lokalen Netz', () => {
  for (const host of ['localhost', '127.0.0.1', '192.168.178.38', '10.1.2.3', '172.16.0.1', 'fritzbox.local']) {
    assert.ok(istPrivateAdresse(host), `${host} sollte gesperrt sein`);
  }
  for (const host of ['www.spiegel.de', '172.32.0.1', '8.8.8.8']) {
    assert.ok(!istPrivateAdresse(host), `${host} sollte erlaubt sein`);
  }
});

test('erlaubt nur http und https', () => {
  assert.throws(() => pruefeUrl('file:///etc/passwd'), /http/);
  assert.throws(() => pruefeUrl('kein-url'), /Adresse/);
  assert.doesNotThrow(() => pruefeUrl('https://www.zeit.de/artikel'));
});

test('prueft auch das Ziel einer Weiterleitung', async () => {
  const fetchStub = async () =>
    new Response(null, { status: 302, headers: { location: 'http://192.168.178.1/admin' } });

  await assert.rejects(
    () => holeArtikelSeite('https://www.example.com/artikel', fetchStub),
    /lokalen Netz/
  );
});

test('holt HTML und meldet die Endadresse', async () => {
  const fetchStub = async (url) => {
    if (url.endsWith('/kurz')) {
      return new Response(null, { status: 301, headers: { location: 'https://www.example.com/lang' } });
    }
    return new Response(ARTIKEL_HTML, { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' } });
  };

  const { html, endUrl } = await holeArtikelSeite('https://www.example.com/kurz', fetchStub);
  assert.strictEqual(endUrl, 'https://www.example.com/lang');
  assert.ok(html.includes('NewsArticle'));
});

test('lehnt Seiten ohne HTML ab', async () => {
  const fetchStub = async () =>
    new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });

  await assert.rejects(() => holeArtikelSeite('https://www.example.com/a', fetchStub), /HTML/);
});
