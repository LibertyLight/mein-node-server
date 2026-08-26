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
const medienModul = require('../whatsapp/medien');
const whisperModul = require('../whatsapp/whisper');

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

function webhookKoerper(text, { id = 'wamid.1', von = '491701234567', typ = 'text', medien = null } = {}) {
  const nachricht = { id, from: von, type: typ, timestamp: String(Math.floor(Date.now() / 1000)) };
  if (typ === 'text') nachricht.text = { body: text };
  if (medien) nachricht[typ] = { id: 'MEDIA1', mime_type: 'audio/ogg; codecs=opus', voice: true, ...medien };
  return { object: 'whatsapp_business_account', entry: [{ changes: [{ value: { messages: [nachricht] } }] }] };
}

/** Bild als Webhook-Körper; die Bildunterschrift ist der Text. */
function bildKoerper({ unterschrift = null, mimeTyp = 'image/jpeg', ...optionen } = {}) {
  const koerper = webhookKoerper(null, {
    typ: 'image',
    medien: { mime_type: mimeTyp, voice: false },
    ...optionen,
  });
  const nachricht = koerper.entry[0].changes[0].value.messages[0];
  if (unterschrift) nachricht.image.caption = unterschrift;
  return koerper;
}

/** Sprachnachricht als Webhook-Körper. */
function sprachKoerper(optionen = {}) {
  return webhookKoerper(null, { typ: 'audio', medien: {}, ...optionen });
}

/** Bot mit Attrappen fuer Claude und Versand. */
function testBot({
  antwort = 'Antwort von Claude',
  fehler = null,
  konfig = testKonfig(),
  transkript = null,
  transkriptFehler = null,
  medienFehler = null,
  datei = { daten: Buffer.from('audio'), mimeTyp: 'audio/ogg', groesse: 5 },
} = {}) {
  const gesendet = [];
  const gesehen = [];
  const gehoert = [];
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

  // Sprachnachrichten: nur eingehängt, wenn der Test eins davon braucht.
  const whisper =
    transkript === null && !transkriptFehler
      ? null
      : {
          transkribiere: async (datei) => {
            gehoert.push(datei);
            if (transkriptFehler) throw transkriptFehler;
            return transkript;
          },
        };

  const geladen = [];
  const medien = {
    hole: async (k, id, optionen) => {
      geladen.push({ id, optionen });
      if (medienFehler) throw medienFehler;
      return datei;
    },
  };

  const still = { log() {}, warn() {}, error() {} };
  const bot = botModul.erstelleBot({ konfig, verlauf, claude, whisper, medien, versand, protokoll: still });
  return { bot, verlauf, gesendet, gesehen, gehoert, geladen, konfig };
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
    id: 'a', von: '4917', name: 'Eric', typ: 'text', text: 'Hallo', medien: null, zeitstempel: 1700000000,
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

test('Bilder und Dateien bekommen einen Hinweis', async () => {
  const { bot, gesendet, gesehen } = testBot();
  await bot.verarbeiteKoerper(webhookKoerper(null, { typ: 'image' }));

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

// --- Sprachnachrichten ---------------------------------------------------

test('die Medienangaben einer Sprachnachricht werden mitgelesen', () => {
  const [ereignis] = nachrichten.extrahiereEreignisse(sprachKoerper());

  assert.strictEqual(ereignis.typ, 'audio');
  // Der Webhook liefert nur die ID; die Datei wird getrennt abgeholt.
  assert.deepStrictEqual(ereignis.medien, { id: 'MEDIA1', mimeTyp: 'audio/ogg', stimme: true });
});

test('Medien werden in zwei Schritten und jeweils mit Token geholt', async () => {
  const aufrufe = [];
  const fetchImpl = async (url, opt) => {
    aufrufe.push({ url, autorisierung: opt.headers.Authorization });
    if (aufrufe.length === 1) {
      return {
        ok: true,
        json: async () => ({ url: 'https://lookaside.fbsbx.com/datei', mime_type: 'audio/ogg; codecs=opus', file_size: 5 }),
      };
    }
    return { ok: true, arrayBuffer: async () => Buffer.from('audio') };
  };

  const datei = await medienModul.hole(testKonfig(), 'MEDIA1', { fetchImpl });

  assert.strictEqual(aufrufe.length, 2);
  assert.match(aufrufe[0].url, /graph\.facebook\.com\/v23\.0\/MEDIA1$/);
  assert.strictEqual(aufrufe[1].url, 'https://lookaside.fbsbx.com/datei');
  // Auch die Datei-Adresse verlangt den Token -- sie allein genügt nicht.
  for (const aufruf of aufrufe) assert.strictEqual(aufruf.autorisierung, 'Bearer token');
  assert.deepStrictEqual(datei, { daten: Buffer.from('audio'), mimeTyp: 'audio/ogg', groesse: 5 });
});

test('zu große Dateien werden vor dem Herunterladen abgelehnt', async () => {
  let dateiAbgerufen = false;
  const fetchImpl = async (url) => {
    if (url.includes('graph.facebook.com')) {
      return { ok: true, json: async () => ({ url: 'https://lookaside.fbsbx.com/datei', mime_type: 'audio/ogg', file_size: 99_000_000 }) };
    }
    dateiAbgerufen = true;
    return { ok: true, arrayBuffer: async () => Buffer.alloc(0) };
  };

  await assert.rejects(
    () => medienModul.hole(testKonfig(), 'MEDIA1', { fetchImpl, maxBytes: 1024 }),
    (fehler) => fehler.grund === 'zu-gross',
  );
  assert.strictEqual(dateiAbgerufen, false);
});

test('auch eine falsch angegebene Dateigröße fliegt auf', async () => {
  const fetchImpl = async (url) => {
    if (url.includes('graph.facebook.com')) {
      // file_size behauptet 5 Bytes, geliefert werden 4096.
      return { ok: true, json: async () => ({ url: 'https://lookaside.fbsbx.com/datei', mime_type: 'audio/ogg', file_size: 5 }) };
    }
    return { ok: true, arrayBuffer: async () => Buffer.alloc(4096) };
  };

  await assert.rejects(
    () => medienModul.hole(testKonfig(), 'MEDIA1', { fetchImpl, maxBytes: 1024 }),
    (fehler) => fehler.grund === 'zu-gross',
  );
});

test('Whisper bekommt Datei, Modell und Sprache als Formular', async () => {
  let aufruf = null;
  const fetchImpl = async (url, opt) => {
    aufruf = { url, opt };
    return { ok: true, json: async () => ({ text: '  Wie wird das Wetter morgen?  ' }) };
  };

  const konfig = testKonfig({ WHISPER_API_KEY: 'sk-whisper', WHISPER_SPRACHE: 'de' });
  const whisper = whisperModul.erstelleWhisper(konfig, { fetchImpl });
  const text = await whisper.transkribiere({ daten: Buffer.from('audio'), mimeTyp: 'audio/ogg' });

  assert.strictEqual(text, 'Wie wird das Wetter morgen?');
  assert.strictEqual(aufruf.url, 'https://api.openai.com/v1/audio/transcriptions');
  assert.strictEqual(aufruf.opt.headers.Authorization, 'Bearer sk-whisper');
  assert.strictEqual(aufruf.opt.body.get('model'), 'whisper-1');
  assert.strictEqual(aufruf.opt.body.get('language'), 'de');
  // Die Schnittstelle erkennt das Format an der Endung, nicht am MIME-Typ.
  assert.strictEqual(aufruf.opt.body.get('file').name, 'sprachnachricht.ogg');
});

test('ein lokaler Whisper-Server wird ohne Schlüssel angesprochen', async () => {
  let aufruf = null;
  const fetchImpl = async (url, opt) => {
    aufruf = { url, opt };
    return { ok: true, json: async () => ({ text: 'lokal verstanden' }) };
  };

  const konfig = testKonfig({ WHISPER_URL: 'http://127.0.0.1:8080/v1/' });
  const whisper = whisperModul.erstelleWhisper(konfig, { fetchImpl });

  assert.strictEqual(await whisper.transkribiere({ daten: Buffer.from('x'), mimeTyp: 'audio/ogg' }), 'lokal verstanden');
  assert.strictEqual(aufruf.url, 'http://127.0.0.1:8080/v1/audio/transcriptions');
  assert.strictEqual(aufruf.opt.headers.Authorization, undefined);
});

test('unlesbare Formate und Dienstfehler werden unterschieden', async () => {
  const konfig = testKonfig({ WHISPER_API_KEY: 'sk-whisper' });

  const nieAufgerufen = whisperModul.erstelleWhisper(konfig, {
    fetchImpl: async () => assert.fail('bei unbekanntem Format darf gar nicht erst gefragt werden'),
  });
  await assert.rejects(
    () => nieAufgerufen.transkribiere({ daten: Buffer.from('x'), mimeTyp: 'audio/amr' }),
    (fehler) => fehler.grund === 'format',
  );

  const kaputt = whisperModul.erstelleWhisper(konfig, {
    fetchImpl: async () => ({ ok: false, status: 500, text: async () => 'serverfehler' }),
  });
  await assert.rejects(
    () => kaputt.transkribiere({ daten: Buffer.from('x'), mimeTyp: 'audio/ogg' }),
    (fehler) => fehler.grund === 'dienst',
  );
});

test('eine Sprachnachricht wird transkribiert, beantwortet und gemerkt', async () => {
  const { bot, verlauf, gesendet, gesehen, gehoert } = testBot({
    transkript: 'Wie wird das Wetter morgen?',
    antwort: 'Morgen bleibt es trocken.',
  });

  const ergebnis = await bot.verarbeiteKoerper(sprachKoerper());

  assert.deepStrictEqual(ergebnis, ['beantwortet']);
  assert.strictEqual(gehoert.length, 1);
  // Claude sieht das Transkript wie eine getippte Nachricht.
  assert.deepStrictEqual(gesehen[0], [{ role: 'user', content: 'Wie wird das Wetter morgen?' }]);
  assert.strictEqual(gesendet[0].text, '🎙 _Wie wird das Wetter morgen?_\n\nMorgen bleibt es trocken.');
  assert.deepStrictEqual(verlauf.holen('491701234567'), [
    { role: 'user', content: 'Wie wird das Wetter morgen?' },
    { role: 'assistant', content: 'Morgen bleibt es trocken.' },
  ]);
});

test('Rückfragen nach einer Sprachnachricht kennen den Verlauf', async () => {
  const { bot, gesehen } = testBot({ transkript: 'Merk dir die Zahl sieben.' });

  await bot.verarbeiteKoerper(sprachKoerper({ id: 'wamid.1' }));
  await bot.verarbeiteKoerper(webhookKoerper('Welche Zahl war das?', { id: 'wamid.2' }));

  assert.deepStrictEqual(gesehen[1].map((n) => n.content), [
    'Merk dir die Zahl sieben.',
    'Antwort von Claude',
    'Welche Zahl war das?',
  ]);
});

test('ohne eingerichtete Transkription gibt es einen Hinweis statt einer Antwort', async () => {
  const { bot, gesendet, gesehen } = testBot();
  await bot.verarbeiteKoerper(sprachKoerper());

  assert.strictEqual(gesehen.length, 0);
  assert.strictEqual(gesendet[0].text, botModul.OHNE_WHISPER);
});

test('eine stille Aufnahme wird nicht an Claude weitergereicht', async () => {
  const { bot, gesendet, gesehen } = testBot({ transkript: '   ' });
  await bot.verarbeiteKoerper(sprachKoerper());

  assert.strictEqual(gesehen.length, 0);
  assert.strictEqual(gesendet[0].text, botModul.NICHTS_GEHOERT);
});

test('Probleme beim Transkribieren werden nach Grund erklärt', async () => {
  const zuGross = new medienModul.MedienFehler('zu groß', 'zu-gross');
  const einer = testBot({ transkript: 'egal', medienFehler: zuGross });
  await einer.bot.verarbeiteKoerper(sprachKoerper());
  assert.strictEqual(einer.gesendet[0].text, botModul.AUDIO_ZU_GROSS);

  const format = new whisperModul.WhisperFehler('audio/amr', 'format');
  const zweiter = testBot({ transkript: 'egal', transkriptFehler: format });
  await zweiter.bot.verarbeiteKoerper(sprachKoerper());
  assert.strictEqual(zweiter.gesendet[0].text, botModul.AUDIO_FORMAT);

  const dienst = new whisperModul.WhisperFehler('500', 'dienst');
  const dritter = testBot({ transkript: 'egal', transkriptFehler: dienst });
  await dritter.bot.verarbeiteKoerper(sprachKoerper());
  assert.strictEqual(dritter.gesendet[0].text, botModul.AUDIO_FEHLER);
  // Ohne Transkript darf Claude gar nicht erst gefragt werden.
  assert.strictEqual(dritter.gesehen.length, 0);
});

test('gesprochene Befehle wirken wie getippte', async () => {
  const { bot, verlauf, gesendet } = testBot({ transkript: '/neu' });

  await bot.verarbeiteKoerper(webhookKoerper('Merk dir: blau', { id: 'wamid.1' }));
  await bot.verarbeiteKoerper(sprachKoerper({ id: 'wamid.2' }));

  assert.deepStrictEqual(verlauf.holen('491701234567'), []);
  assert.match(gesendet[1].text, /vergessen/);
});

test('das vorangestellte Transkript lässt sich abschalten', async () => {
  const konfig = testKonfig({ WHATSAPP_TRANSKRIPT_ZEIGEN: '0' });
  const { bot, gesendet } = testBot({ konfig, transkript: 'Hallo Claude' });

  await bot.verarbeiteKoerper(sprachKoerper());
  assert.strictEqual(gesendet[0].text, 'Antwort von Claude');
});

test('sehr lange Transkripte werden in der Vorschau gekürzt', () => {
  const zeile = botModul.transkriptZeile(`${'sehr langes Gerede. '.repeat(50)}\nmit Umbruch`);

  assert.ok(zeile.startsWith('🎙 _'));
  assert.ok(zeile.endsWith('…_'));
  assert.ok(zeile.length < 320);
  assert.ok(!zeile.includes('\n'));
});

// --- Bilder --------------------------------------------------------------

test('die Bildunterschrift ist der Text der Nachricht', () => {
  const [ereignis] = nachrichten.extrahiereEreignisse(bildKoerper({ unterschrift: 'Was kostet das?' }));

  assert.strictEqual(ereignis.typ, 'image');
  assert.strictEqual(ereignis.text, 'Was kostet das?');
  assert.deepStrictEqual(ereignis.medien, { id: 'MEDIA1', mimeTyp: 'image/jpeg', stimme: false });
});

test('ein Bild geht als Bildblock vor dem Text an Claude', async () => {
  const bild = { daten: Buffer.from([1, 2, 3]), mimeTyp: 'image/jpeg', groesse: 3 };
  const { bot, gesendet, gesehen, geladen } = testBot({ datei: bild, antwort: 'Ein Fahrrad.' });

  await bot.verarbeiteKoerper(bildKoerper({ unterschrift: 'Was ist das?' }));

  // Beim Herunterladen gilt die Bild-Obergrenze, nicht die für Audio.
  assert.strictEqual(geladen[0].optionen.maxBytes, 5 * 1024 * 1024);
  assert.deepStrictEqual(gesehen[0], [
    {
      role: 'user',
      content: [
        { type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: Buffer.from([1, 2, 3]).toString('base64') } },
        { type: 'text', text: 'Was ist das?' },
      ],
    },
  ]);
  assert.deepStrictEqual(gesendet, [{ an: '491701234567', text: 'Ein Fahrrad.' }]);
});

test('ein Bild ohne Unterschrift wird als Frage verstanden', async () => {
  const { bot, gesehen, konfig } = testBot({ datei: { daten: Buffer.from('x'), mimeTyp: 'image/png', groesse: 1 } });

  await bot.verarbeiteKoerper(bildKoerper({ mimeTyp: 'image/png' }));

  assert.strictEqual(gesehen[0][0].content[1].text, konfig.bildFrage);
});

test('Rückfragen zu einem Bild behalten es im Verlauf', async () => {
  const { bot, gesehen } = testBot({ datei: { daten: Buffer.from('bild'), mimeTyp: 'image/jpeg', groesse: 4 } });

  await bot.verarbeiteKoerper(bildKoerper({ unterschrift: 'Was ist das?', id: 'wamid.1' }));
  await bot.verarbeiteKoerper(webhookKoerper('Und was kostet so etwas?', { id: 'wamid.2' }));

  // Die zweite Anfrage schickt das Bild erneut mit -- die API ist zustandslos.
  assert.strictEqual(gesehen[1][0].content[0].type, 'image');
  assert.strictEqual(gesehen[1].at(-1).content, 'Und was kostet so etwas?');
});

test('nur die jüngsten Bilder gehen vollständig mit', () => {
  const verlauf = verlaufModul.erstelleVerlauf(new DatabaseSync(':memory:'), { maxBilder: 1 });
  verlauf.anhaengen('49', 'user', 'Bild eins', { daten: Buffer.from('ALT'), mimeTyp: 'image/jpeg' });
  verlauf.anhaengen('49', 'assistant', 'Ein Baum.');
  verlauf.anhaengen('49', 'user', 'Bild zwei', { daten: Buffer.from('NEU'), mimeTyp: 'image/png' });

  const [alt, , neu] = verlauf.holen('49');
  // Das ältere Bild schrumpft auf einen Hinweis, sonst zahlt man es ewig mit.
  assert.strictEqual(alt.content, '[Bild] Bild eins');
  assert.strictEqual(neu.content[0].source.media_type, 'image/png');
  assert.strictEqual(neu.content[0].source.data, Buffer.from('NEU').toString('base64'));
});

test('ein Verlauf aus einer Fassung ohne Bilder wird nachgerüstet', () => {
  const db = new DatabaseSync(':memory:');
  // Tabelle wie vor dieser Änderung anlegen und füllen.
  db.exec(`
    CREATE TABLE wa_verlauf (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      nummer TEXT NOT NULL,
      rolle TEXT NOT NULL,
      text TEXT NOT NULL,
      erstellt_am TEXT NOT NULL
    )
  `);
  db.prepare('INSERT INTO wa_verlauf (nummer, rolle, text, erstellt_am) VALUES (?, ?, ?, ?)')
    .run('49', 'user', 'alte Nachricht', '2026-01-01');

  const verlauf = verlaufModul.erstelleVerlauf(db);

  // Der alte Verlauf bleibt erhalten, neue Bilder funktionieren trotzdem.
  assert.deepStrictEqual(verlauf.holen('49'), [{ role: 'user', content: 'alte Nachricht' }]);
  verlauf.anhaengen('49', 'user', 'neu mit Bild', { daten: Buffer.from('B'), mimeTyp: 'image/png' });
  assert.strictEqual(verlauf.holen('49').at(-1).content[0].type, 'image');
});

test('unlesbare Bildformate werden gar nicht erst geladen', async () => {
  const { bot, gesendet, gesehen, geladen } = testBot();

  await bot.verarbeiteKoerper(bildKoerper({ mimeTyp: 'image/tiff' }));

  assert.strictEqual(geladen.length, 0);
  assert.strictEqual(gesehen.length, 0);
  assert.strictEqual(gesendet[0].text, botModul.BILD_FORMAT);
});

test('ein zu großes Bild wird erklärt', async () => {
  const { bot, gesendet } = testBot({ medienFehler: new medienModul.MedienFehler('zu groß', 'zu-gross') });

  await bot.verarbeiteKoerper(bildKoerper());
  assert.strictEqual(gesendet[0].text, botModul.BILD_ZU_GROSS);
});

test('ein Befehl in der Bildunterschrift spart den Download', async () => {
  const { bot, verlauf, gesendet, geladen } = testBot();

  await bot.verarbeiteKoerper(webhookKoerper('Merk dir: blau', { id: 'wamid.1' }));
  await bot.verarbeiteKoerper(bildKoerper({ unterschrift: '/neu', id: 'wamid.2' }));

  assert.strictEqual(geladen.length, 0);
  assert.deepStrictEqual(verlauf.holen('491701234567'), []);
  assert.match(gesendet[1].text, /vergessen/);
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
