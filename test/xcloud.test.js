const test = require('node:test');
const assert = require('node:assert');
const { TASTE, ACHSE, PAD_ID, erstellePad, stickVektor } = require('../public/xcloud-touch.user.js');

test('stickVektor: Totzone liefert 0', () => {
  assert.deepStrictEqual(stickVektor(2, 1, 50, 0.1), { x: 0, y: 0 });
  assert.deepStrictEqual(stickVektor(0, 0, 50, 0.1), { x: 0, y: 0 });
});

test('stickVektor: volle Auslenkung wird auf 1 begrenzt', () => {
  const v = stickVektor(500, 0, 50, 0.1);
  assert.strictEqual(v.x, 1);
  assert.strictEqual(v.y, 0);
});

test('stickVektor: Richtung bleibt erhalten, Betrag ueberschreitet 1 nie', () => {
  const v = stickVektor(-30, 40, 50, 0.1);
  assert.ok(v.x < 0 && v.y > 0);
  assert.ok(Math.hypot(v.x, v.y) <= 1 + 1e-9);
});

test('Pad: Standard-Layout mit 17 Tasten und 4 Achsen', () => {
  const s = erstellePad().schnappschuss(2);
  assert.strictEqual(s.mapping, 'standard');
  assert.strictEqual(s.id, PAD_ID);
  assert.strictEqual(s.index, 2);
  assert.strictEqual(s.buttons.length, 17);
  assert.strictEqual(s.axes.length, 4);
});

test('Pad: Tasten und Achsen setzen und zuruecksetzen', () => {
  const pad = erstellePad();
  pad.taste(TASTE.A, true);
  pad.achse(ACHSE.LX, 0.5);
  let s = pad.schnappschuss(0);
  assert.strictEqual(s.buttons[0].pressed, true);
  assert.strictEqual(s.buttons[0].value, 1);
  assert.strictEqual(s.axes[0], 0.5);
  pad.zuruecksetzen();
  s = pad.schnappschuss(0);
  assert.strictEqual(s.buttons[0].pressed, false);
  assert.strictEqual(s.axes[0], 0);
});

test('Pad: Schnappschuss ist von spaeteren Aenderungen entkoppelt', () => {
  const pad = erstellePad();
  const s = pad.schnappschuss(0);
  pad.taste(TASTE.B, true);
  assert.strictEqual(s.buttons[TASTE.B].pressed, false);
});
