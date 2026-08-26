'use strict';

/**
 * Tests fuer den WhatsApp-Bot.
 *
 * Ohne Netzzugriff: Claude und der Versand an Meta werden durch Attrappen
 * ersetzt. Geprueft wird das, was schiefgehen kann -- Signaturen, doppelte
 * Zustellungen, Verlauf, Reihenfolge --, nicht die Erreichbarkeit fremder Server.
 */

const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const express = require('express');
const { DatabaseSync } = require('node:sqlite');

const konfigModul = require('../whatsapp/konfig');
const nachrichten = require('../whatsapp/nachrichten');
const verlaufModul = require('../whatsapp/verlauf');
const botModul = require('../whatsapp/bot');
const routenModul = require('../whatsapp/routen');
const claudeModul = require('../whatsapp/claude');

const GEHEIMNIS = 'app-geheimnis';

function testKonfig(zusatz = {}) {
  return konfigModul.lade({
    ANTHROPIC_API_KEY: 'test',
    WHATSAPP_TOKEN: 'token',
    WHATSAPP_TELEFON_ID: '111',
    WHATSAPP_PRUEF_TOKEN: 'pruef',
    WHATSAPP_APP_GEHEIMNIS: GEHEIMNIS,
    WHATSAPP_ERLAUBTE_NUMMERN: '491701234567',
    ...zusatz,
  });
}

function webhookKoerper(text, { id = 'wamid.1', von = '491701234567', typ = 'text' } = {}) {
  const nachricht = { id, from: von, type: typ, timestamp: String(Math.floor(Date.now() / 1000)) };
  if (typ === 'text') nachricht.text = { body: text };
  return { object: 'whatsapp_business_account', entry: [{ changes: [{ value: { messages: [nachricht] } }] }] };
}

/** Bot mit Attrappen fuer Claude und Versand. */
function testBot({ antwort = 'Antwort von Claude', fehler = null, konfig = testKonfig() } = {}) {
  const gesendet = [];
  const gesehen = [];
  const verlauf = verlaufModul.erstelleVerlauf(new DatabaseSync(':memory:'), { maxNachrichten: 10 });

  const claude = {
    antworte: async (verlaufFuerApi) => {
      gesehen.push(verlaufFuerApi);
      if (fehler) throw fehler;
      return typeof antwort === 'function' ? antwort(verlaufFuerApi) : antwort;
    },
  };

  const versand = {
    sendeText: async (_konfig, an, text) => {
      gesendet.push({ an, text });
    },
    markiereGelesen: async () => {},
  };

  const still = { log() {}, warn() {}, error() {} };
  const bot = botModul.erstelleBot({ konfig, verlauf, claude, versand, protokoll: still });
  return { bot, verlauf, gesendet, gesehen, konfig };
}

// --- Konfiguration -------------------------------------------------------

test('fehlende Zugangsdaten werden vollständig gemeldet', () => {
  const fehlt = konfigModul.fehlendeAngaben(konfigModul.lade({}));
  assert.ok(fehlt.includes('ANTHROPIC_API_KEY'));
  assert.ok(fehlt.includes('WHATSAPP_APP_GEHEIMNIS'));
  // Ohne Freigabeliste koennte jeder Fremde auf fremde Rechnung plaudern.
  assert.ok(fehlt.includes('WHATSAPP_ERLAUBTE_NUMMERN'));
});

test('Freigabeliste erkennt Nummern unabhängig von der Schreibweise', () => {
  const konfig = testKonfig({ WHATSAPP_ERLAUBTE_NUMMERN: '+49 170 123 45 67, 4915112345' });
  assert.ok(konfigModul.istFreigegeben(konfig, '491701234567'));
  assert.ok(konfigModul.istFreigegeben(konfig, '+49 170 1234567'));
  assert.ok(!konfigModul.istFreigegeben(konfig, '4917099999'));
});

test('"alle" öffnet die Freigabeliste bewusst', () => {
  const konfig = testKonfig({ WHATSAPP_ERLAUBTE_NUMMERN: 'alle' });
  assert.deepStrictEqual(konfigModul.fehlendeAngaben(konfig), []);
  assert.ok(konfigModul.istFreigegeben(konfig, '999'));
});

// --- Webhook-Format ------------------------------------------------------

test('gültige Signatur wird angenommen, verfälschter Inhalt nicht', () => {
  const koerper = JSON.stringify(webhookKoerper('Hallo'));
  const signatur = `sha256=${crypto.createHmac('sha256', GEHEIMNIS).update(koerper).digest('hex')}`;

  assert.ok(nachrichten.pruefeSignatur(koerper, signatur, GEHEIMNIS));
  assert.ok(!nachrichten.pruefeSignatur(`${koerper} `, signatur, GEHEIMNIS));
  assert.ok(!nachrichten.pruefeSignatur(koerper, signatur, 'falsches-geheimnis'));
  assert.ok(!nachrichten.pruefeSignatur(koerper, 'sha256=kurz', GEHEIMNIS));
  assert.ok(!nachrichten.pruefeSignatur(koerper, undefined, GEHEIMNIS));
});

test('Nachrichten werden aus dem verschachtelten Webhook geschält', () => {
  const koerper = {
    entry: [
      {
        changes: [
          {
            value: {
              contacts: [{ wa_id: '4917', profile: { name: 'Eric' } }],
              messages: [
                { id: 'a', from: '4917', type: 'text', timestamp: '1700000000', text: { body: 'Hallo' } },
                { id: 'b', from: '4917', type: 'image', timestamp: '1700000001' },
              ],
            },
          },
        ],
      },
    ],
  };

  const ereignisse = nachrichten.extrahiereEreignisse(koerper);
  assert.strictEqual(ereignisse.length, 2);
  assert.deepStrictEqual(ereignisse[0], {
    id: 'a', von: '4917', name: 'Eric', typ: 'text', text: 'Hallo', zeitstempel: 1700000000,
  });
  assert.strictEqual(ereignisse[1].text, '');
});

test('Zustellberichte enthalten keine Nachrichten', () => {
  const koerper = { entry: [{ changes: [{ value: { statuses: [{ id: 'a', status: 'delivered' }] } }] }] };
  assert.deepStrictEqual(nachrichten.extrahiereEreignisse(koerper), []);
  assert.deepStrictEqual(nachrichten.extrahiereEreignisse(null), []);
});

test('lange Antworten werden verlustfrei in versandfähige Teile geschnitten', () => {
  const text = `${'Erster Absatz. '.repeat(300)}\n\n${'Zweiter Absatz. '.repeat(300)}`;
  const teile = nachrichten.teileText(text);

  assert.ok(teile.length > 1);
  for (const teil of teile) assert.ok(teil.length <= nachrichten.MAX_ZEICHEN);
  // Nichts darf verloren gehen -- bis auf die Trennzeichen an den Schnittstellen.
  assert.strictEqual(
    teile.join(' ').replace(/\s+/g, ' ').trim(),
    text.replace(/\s+/g, ' ').trim(),
  );
});

test('kurze Texte bleiben ein Stück, leere Texte ergeben nichts', () => {
  assert.deepStrictEqual(nachrichten.teileText('Kurz'), ['Kurz']);
  assert.deepStrictEqual(nachrichten.teileText('   '), []);
});

test('Befehle werden unabhängig von Groß- und Kleinschreibung erkannt', () => {
  assert.strictEqual(nachrichten.erkenneBefehl(' /Neu '), 'neu');
  assert.strictEqual(nachrichten.erkenneBefehl('/HILFE'), 'hilfe');
  assert.strictEqual(nachrichten.erkenneBefehl('Was ist /neu bei dir?'), null);
});

test('veraltete Wiederholungen werden erkannt', () => {
  const jetzt = Date.now();
  assert.ok(nachrichten.istVeraltet(jetzt / 1000 - 7200, 3600, jetzt));
  assert.ok(!nachrichten.istVeraltet(jetzt / 1000 - 10, 3600, jetzt));
});

// --- Verlauf -------------------------------------------------------------

test('Verlauf liefert die letzten Nachrichten in Reihenfolge', () => {
  const verlauf = verlaufModul.erstelleVerlauf(new DatabaseSync(':memory:'), { maxNachrichten: 4 });
  verlauf.anhaengen('49', 'user', 'eins');
  verlauf.anhaengen('49', 'assistant', 'zwei');
  verlauf.anhaengen('49', 'user', 'drei');

  assert.deepStrictEqual(verlauf.holen('49'), [
    { role: 'user', content: 'eins' },
    { role: 'assistant', content: 'zwei' },
    { role: 'user', content: 'drei' },
  ]);
});

test('gekürzter Verlauf beginnt immer mit einer Nutzernachricht', () => {
  const verlauf = verlaufModul.erstelleVerlauf(new DatabaseSync(':memory:'), { maxNachrichten: 3 });
  verlauf.anhaengen('49', 'user', 'eins');
  verlauf.anhaengen('49', 'assistant', 'zwei');
  verlauf.anhaengen('49', 'user', 'drei');
  verlauf.anhaengen('49', 'assistant', 'vier');

  const verlaufFuerApi = verlauf.holen('49');
  assert.strictEqual(verlaufFuerApi[0].role, 'user');
  assert.deepStrictEqual(verlaufFuerApi.map((n) => n.content), ['drei', 'vier']);
});

test('Verläufe verschiedener Nummern bleiben getrennt', () => {
  const verlauf = verlaufModul.erstelleVerlauf(new DatabaseSync(':memory:'));
  verlauf.anhaengen('49', 'user', 'meins');
  verlauf.anhaengen('43', 'user', 'deins');

  assert.deepStrictEqual(verlauf.holen('49'), [{ role: 'user', content: 'meins' }]);
  assert.strictEqual(verlauf.leeren('49'), 1);
  assert.deepStrictEqual(verlauf.holen('43'), [{ role: 'user', content: 'deins' }]);
});

test('leere Texte landen nicht im Verlauf', () => {
  const verlauf = verlaufModul.erstelleVerlauf(new DatabaseSync(':memory:'));
  assert.strictEqual(verlauf.anhaengen('49', 'user', '   '), false);
  assert.strictEqual(verlauf.anhaengen('49', 'system', 'nicht erlaubt'), false);
  assert.deepStrictEqual(verlauf.holen('49'), []);
});

test('dieselbe Nachrichten-ID wird nur einmal angenommen', () => {
  const verlauf = verlaufModul.erstelleVerlauf(new DatabaseSync(':memory:'));
  assert.strictEqual(verlauf.schonGesehen('wamid.1'), false);
  assert.strictEqual(verlauf.schonGesehen('wamid.1'), true);
  assert.strictEqual(verlauf.schonGesehen('wamid.2'), false);
});

// --- Ablauf --------------------------------------------------------------

test('eine Nachricht wird beantwortet und landet im Verlauf', async () => {
  const { bot, verlauf, gesendet, gesehen } = testBot();
  const ergebnis = await bot.verarbeiteKoerper(webhookKoerper('Wie spät ist es?'));

  assert.deepStrictEqual(ergebnis, ['beantwortet']);
  assert.deepStrictEqual(gesehen[0], [{ role: 'user', content: 'Wie spät ist es?' }]);
  assert.deepStrictEqual(gesendet, [{ an: '491701234567', text: 'Antwort von Claude' }]);
  assert.deepStrictEqual(verlauf.holen('491701234567'), [
    { role: 'user', content: 'Wie spät ist es?' },
    { role: 'assistant', content: 'Antwort von Claude' },
  ]);
});

test('nicht freigegebene Nummern bekommen keine Antwort', async () => {
  const { bot, gesendet } = testBot();
  const ergebnis = await bot.verarbeiteKoerper(webhookKoerper('Hallo', { von: '4915199999' }));

  assert.deepStrictEqual(ergebnis, ['gesperrt']);
  assert.deepStrictEqual(gesendet, []);
});

test('wiederholte Zustellung derselben Nachricht wird übersprungen', async () => {
  const { bot, gesendet } = testBot();
  await bot.verarbeiteKoerper(webhookKoerper('Hallo'));
  const zweites = await bot.verarbeiteKoerper(webhookKoerper('Hallo'));

  assert.deepStrictEqual(zweites, ['doppelt']);
  assert.strictEqual(gesendet.length, 1);
});

test('/neu löscht den Verlauf', async () => {
  const { bot, verlauf, gesendet } = testBot();
  await bot.verarbeiteKoerper(webhookKoerper('Merk dir: blau', { id: 'wamid.1' }));
  await bot.verarbeiteKoerper(webhookKoerper('/neu', { id: 'wamid.2' }));

  assert.deepStrictEqual(verlauf.holen('491701234567'), []);
  assert.match(gesendet[1].text, /vergessen/);
});

test('/hilfe beantwortet der Bot selbst, ohne Claude zu fragen', async () => {
  const { bot, gesendet, gesehen } = testBot();
  await bot.verarbeiteKoerper(webhookKoerper('/hilfe'));

  assert.strictEqual(gesehen.length, 0);
  assert.match(gesendet[0].text, /Befehle/);
});

test('Sprachnachrichten und Bilder bekommen einen Hinweis', async () => {
  const { bot, gesendet, gesehen } = testBot();
  await bot.verarbeiteKoerper(webhookKoerper(null, { typ: 'audio' }));

  assert.strictEqual(gesehen.length, 0);
  assert.strictEqual(gesendet[0].text, botModul.NUR_TEXT);
});

test('ein Fehler bei Claude wird dem Absender verständlich gemeldet', async () => {
  const { bot, gesendet } = testBot({ fehler: new Error('Zeitüberschreitung') });
  const ergebnis = await bot.verarbeiteKoerper(webhookKoerper('Hallo'));

  assert.deepStrictEqual(ergebnis, ['beantwortet']);
  assert.strictEqual(gesendet.length, 1);
  // Interna gehoeren ins Log, nicht in den Chat.
  assert.ok(!gesendet[0].text.includes('Zeitüberschreitung'));
  assert.match(gesendet[0].text, /schiefgelaufen/);
});

test('schnell aufeinander folgende Nachrichten werden nacheinander bearbeitet', async () => {
  const { bot, gesehen } = testBot({
    antwort: async (verlaufFuerApi) => {
      await new Promise((weiter) => setTimeout(weiter, 20));
      return `Antwort auf ${verlaufFuerApi.at(-1).content}`;
    },
  });

  await Promise.all([
    bot.verarbeiteKoerper(webhookKoerper('erste', { id: 'wamid.1' })),
    bot.verarbeiteKoerper(webhookKoerper('zweite', { id: 'wamid.2' })),
  ]);

  // Die zweite Anfrage muss die Antwort auf die erste bereits kennen.
  assert.deepStrictEqual(gesehen[0].map((n) => n.content), ['erste']);
  assert.deepStrictEqual(gesehen[1].map((n) => n.content), [
    'erste', 'Antwort auf erste', 'zweite',
  ]);
});

// --- Antwortauswertung ---------------------------------------------------

test('Textblöcke werden zusammengesetzt, Denkblöcke ignoriert', () => {
  const text = claudeModul.leseAntwort({
    stop_reason: 'end_turn',
    content: [
      { type: 'thinking', thinking: 'intern' },
      { type: 'text', text: 'Teil eins' },
      { type: 'text', text: 'Teil zwei' },
    ],
  });
  assert.strictEqual(text, 'Teil eins\nTeil zwei');
});

test('Ablehnung und abgeschnittene Antwort erzeugen einen lesbaren Hinweis', () => {
  assert.strictEqual(
    claudeModul.leseAntwort({ stop_reason: 'refusal', content: [] }),
    claudeModul.HINWEISE.abgelehnt,
  );
  assert.strictEqual(
    claudeModul.leseAntwort({ stop_reason: 'max_tokens', content: [{ type: 'thinking', thinking: 'x' }] }),
    claudeModul.HINWEISE.abgeschnitten,
  );
});

// --- Express-Router ------------------------------------------------------

/** Server mit eingehängtem Webhook starten -- so wie app.js es tut. */
async function starteServer(bot, konfig = testKonfig()) {
  const app = express();
  const still = { log() {}, warn() {}, error() {} };
  app.use('/whatsapp', routenModul.erstelleRouter({ konfig, bot, protokoll: still }));
  app.use(express.json());

  const server = await new Promise((fertig) => {
    const s = app.listen(0, '127.0.0.1', () => fertig(s));
  });
  const basis = `http://127.0.0.1:${server.address().port}/whatsapp`;
  return { server, basis, schliessen: () => new Promise((fertig) => server.close(fertig)) };
}

test('Meta kann den Webhook mit dem richtigen Prüf-Token bestätigen', async (t) => {
  const { bot } = testBot();
  const { basis, schliessen } = await starteServer(bot);
  t.after(schliessen);

  const gut = await fetch(`${basis}/webhook?hub.mode=subscribe&hub.verify_token=pruef&hub.challenge=12345`);
  assert.strictEqual(gut.status, 200);
  assert.strictEqual(await gut.text(), '12345');

  const schlecht = await fetch(`${basis}/webhook?hub.mode=subscribe&hub.verify_token=falsch&hub.challenge=12345`);
  assert.strictEqual(schlecht.status, 403);
});

test('Webhook nimmt nur korrekt signierte Anfragen an', async (t) => {
  const { bot, gesendet } = testBot();
  const { basis, schliessen } = await starteServer(bot);
  t.after(schliessen);

  const koerper = JSON.stringify(webhookKoerper('Hallo Claude'));

  const ohne = await fetch(`${basis}/webhook`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: koerper,
  });
  assert.strictEqual(ohne.status, 403);
  assert.strictEqual(gesendet.length, 0);

  const signatur = `sha256=${crypto.createHmac('sha256', GEHEIMNIS).update(koerper).digest('hex')}`;
  const mit = await fetch(`${basis}/webhook`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': signatur },
    body: koerper,
  });
  assert.strictEqual(mit.status, 200);

  // Die Antwort kommt sofort, die Bearbeitung laeuft danach weiter.
  await new Promise((weiter) => setTimeout(weiter, 50));
  assert.deepStrictEqual(gesendet, [{ an: '491701234567', text: 'Antwort von Claude' }]);
});

test('Status verrät den Zustand, aber keine Zugangsdaten', async (t) => {
  const { bot, konfig } = testBot();
  const { basis, schliessen } = await starteServer(bot, konfig);
  t.after(schliessen);

  const antwort = await fetch(`${basis}/status`);
  const status = await antwort.json();

  assert.strictEqual(status.aktiv, true);
  assert.strictEqual(status.freigegebeneNummern, 1);
  const alsText = JSON.stringify(status);
  assert.ok(!alsText.includes(GEHEIMNIS));
  assert.ok(!alsText.includes('token'));
});
