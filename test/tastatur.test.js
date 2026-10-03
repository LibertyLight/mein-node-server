'use strict';

/**
 * Tests fuer die KI-Schreibhilfe der Tastatur -- ohne Netz, Claude ist eine Attrappe.
 */

const test = require('node:test');
const assert = require('node:assert');
const express = require('express');
const Anthropic = require('@anthropic-ai/sdk');

const tastatur = require('../tastatur-ki');
const aufgaben = require('../tastatur-ki/aufgaben');
const { leseText, uebersetzeFehler } = require('../tastatur-ki/claude');
const { erstelleBremse } = require('../tastatur-ki/routen');

const TOKEN = 'ein-langes-zugangswort-123';

function attrappe(antwort = { stop_reason: 'end_turn', content: [{ type: 'text', text: 'Ergebnis' }] }) {
  const aufrufe = [];
  return {
    aufrufe,
    beta: {
      messages: {
        create: async (parameter) => {
          aufrufe.push(parameter);
          if (antwort instanceof Error) throw antwort;
          return antwort;
        },
      },
    },
  };
}

async function starte(client, zusatz = {}) {
  const ki = tastatur.erstelle({
    umgebung: { ANTHROPIC_API_KEY: 'test', TASTATUR_TOKEN: TOKEN, ...zusatz },
    client,
  });
  const app = express();
  app.use(express.json());
  app.use('/api/tastatur', ki.router);
  const server = await new Promise((fertig) => {
    const s = app.listen(0, '127.0.0.1', () => fertig(s));
  });
  const basis = `http://127.0.0.1:${server.address().port}/api/tastatur`;
  return {
    server,
    frage: (koerper, { token = TOKEN } = {}) =>
      fetch(`${basis}/ki`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Tastatur-Token': token },
        body: JSON.stringify(koerper),
      }),
    status: (token = TOKEN) => fetch(`${basis}/status`, { headers: { 'X-Tastatur-Token': token } }),
  };
}

test('ohne Zugangswort bleibt die Route aus und sagt, was fehlt', () => {
  const ki = tastatur.erstelle({ umgebung: { ANTHROPIC_API_KEY: 'x' } });
  assert.strictEqual(ki.aktiv, false);
  assert.strictEqual(ki.router, null);
  assert.match(ki.hinweise[0], /TASTATUR_TOKEN/);
});

test('zu kurzes Zugangswort wird abgelehnt', () => {
  const ki = tastatur.erstelle({ umgebung: { ANTHROPIC_API_KEY: 'x', TASTATUR_TOKEN: 'kurz' } });
  assert.strictEqual(ki.aktiv, false);
  assert.match(ki.hinweise[0], /16 Zeichen/);
});

test('jede Aktion baut eine Aufgabe, der Text steht getrennt in <text>-Tags', () => {
  const faelle = [
    { aktion: 'stil', ton: 'professionell' },
    { aktion: 'korrektur' },
    { aktion: 'zusammenfassen' },
    { aktion: 'stichpunkte' },
    { aktion: 'uebersetzen', ziel: 'en' },
    { aktion: 'verfassen', art: 'email' },
    { aktion: 'verfassen' },
  ];
  for (const fall of faelle) {
    const ergebnis = aufgaben.baue({ ...fall, text: 'hallo welt' }, { maxZeichen: 100 });
    assert.ok(!ergebnis.fehler, `${fall.aktion}: ${ergebnis.fehler}`);
    assert.match(ergebnis.nachrichten[0].content, /<text>\nhallo welt\n<\/text>$/);
  }
});

test('ungueltige Anfragen werden mit Begruendung abgewiesen', () => {
  const k = { maxZeichen: 10 };
  assert.match(aufgaben.baue({ aktion: 'zaubern', text: 'x' }, k).fehler, /Unbekannte Aktion/);
  assert.match(aufgaben.baue({ aktion: 'korrektur', text: '  ' }, k).fehler, /leer/);
  assert.match(aufgaben.baue({ aktion: 'korrektur', text: 'x'.repeat(11) }, k).fehler, /zu lang/);
  assert.match(aufgaben.baue({ aktion: 'stil', ton: 'piratig', text: 'x' }, k).fehler, /Option/);
  assert.match(aufgaben.baue({ aktion: 'uebersetzen', ziel: 'xx', text: 'x' }, k).fehler, /Option/);
  assert.match(aufgaben.baue({ aktion: 'toString', text: 'x' }, k).fehler, /Unbekannte Aktion/);
  assert.match(aufgaben.baue({ aktion: 'stil', ton: 'constructor', text: 'x' }, k).fehler, /Option/);
});

test('Ablehnung und leere Antworten werden zu verstaendlichen Fehlern', () => {
  assert.throws(() => leseText({ stop_reason: 'refusal', content: [] }), { status: 422 });
  assert.throws(() => leseText({ stop_reason: 'max_tokens', content: [] }), /zu lang/);
  assert.throws(() => leseText({ stop_reason: 'end_turn', content: [] }), { status: 502 });
  assert.strictEqual(
    leseText({ content: [{ type: 'thinking', thinking: '' }, { type: 'text', text: ' Hi ' }] }),
    'Hi',
  );
});

test('API-Fehler werden uebersetzt, ohne Interna weiterzugeben', () => {
  const verbindung = new Anthropic.APIConnectionError({ message: 'geheim: 10.0.0.1' });
  const meldung = uebersetzeFehler(verbindung);
  assert.strictEqual(meldung.status, 503);
  assert.doesNotMatch(meldung.message, /10\.0\.0\.1/);
  assert.strictEqual(uebersetzeFehler(new Error('x')).status, 500);
});

test('Bremse laesst nur n Anfragen pro Minute durch', () => {
  let zeit = 0;
  const darf = erstelleBremse(2, () => zeit);
  assert.ok(darf());
  assert.ok(darf());
  assert.ok(!darf());
  zeit = 60_001;
  assert.ok(darf());
});

test('Route: falsches Zugangswort -> 401, Claude wird nicht gefragt', async () => {
  const client = attrappe();
  const { server, frage, status } = await starte(client);
  try {
    assert.strictEqual((await frage({ aktion: 'korrektur', text: 'x' }, { token: 'falsch' })).status, 401);
    assert.strictEqual((await status('')).status, 401);
    assert.strictEqual(client.aufrufe.length, 0);
  } finally {
    server.close();
  }
});

test('Route: Status meldet Modell und Aktionen', async () => {
  const { server, status } = await starte(attrappe());
  try {
    const antwort = await status();
    assert.strictEqual(antwort.status, 200);
    const daten = await antwort.json();
    assert.strictEqual(daten.modell, 'claude-opus-5-5');
    assert.ok(daten.aktionen.includes('uebersetzen'));
  } finally {
    server.close();
  }
});

test('Route: Erfolg liefert den Text, Anfrage traegt Modell, Aufwand und Fallback', async () => {
  const client = attrappe();
  const { server, frage } = await starte(client, { TASTATUR_AUFWAND: 'medium' });
  try {
    const antwort = await frage({ aktion: 'uebersetzen', ziel: 'en', text: 'Guten Morgen' });
    assert.strictEqual(antwort.status, 200);
    assert.deepStrictEqual(await antwort.json(), { text: 'Ergebnis' });
    const [aufruf] = client.aufrufe;
    assert.strictEqual(aufruf.model, 'claude-opus-5-5');
    assert.deepStrictEqual(aufruf.output_config, { effort: 'medium' });
    assert.strictEqual(aufruf.fallbacks, 'default');
    assert.match(aufruf.messages[0].content, /Englische/);
  } finally {
    server.close();
  }
});

test('Route: ungueltige Anfrage -> 400, API-Fehler -> passender Status', async () => {
  const fehler = new Anthropic.RateLimitError(429, undefined, 'langsam', new Headers());
  const { server, frage } = await starte(attrappe(fehler));
  const protokoll = console.error;
  console.error = () => {};
  try {
    assert.strictEqual((await frage({ aktion: 'stil', text: 'x' })).status, 400);
    const antwort = await frage({ aktion: 'korrektur', text: 'x' });
    assert.strictEqual(antwort.status, 429);
    assert.match((await antwort.json()).fehler, /zu viele/i);
  } finally {
    console.error = protokoll;
    server.close();
  }
});
