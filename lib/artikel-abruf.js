'use strict';

const MAX_BYTES = 512 * 1024;   // Kopfdaten stehen im <head>; mehr wird nicht gebraucht.
const MAX_WEITERLEITUNGEN = 3;
const TIMEOUT_MS = 10000;

// Der Server holt Seiten im Auftrag des Browsers. Ohne Pruefung koennte man ihm
// damit Adressen aus dem Heimnetz unterschieben (Router, Drucker, andere Dienste).
const GESPERRTE_HOSTS = new Set(['localhost', '127.0.0.1', '0.0.0.0', '::1', '[::1]']);

function istPrivateAdresse(hostname) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (GESPERRTE_HOSTS.has(host)) return true;
  if (host.endsWith('.local') || host.endsWith('.internal') || host.endsWith('.localhost')) return true;
  if (host.startsWith('fe80:') || host.startsWith('fc') || host.startsWith('fd')) return true;

  const ipv4 = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (ipv4) {
    const [a, b] = ipv4.slice(1).map(Number);
    if (a === 10 || a === 127 || a === 0) return true;
    if (a === 192 && b === 168) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 169 && b === 254) return true;
  }
  return false;
}

function pruefeUrl(eingabe) {
  let url;
  try {
    url = new URL(eingabe);
  } catch {
    throw new Error('Das ist keine gültige Adresse.');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('Nur http- und https-Adressen sind erlaubt.');
  }
  if (istPrivateAdresse(url.hostname)) {
    throw new Error('Adressen aus dem lokalen Netz werden nicht abgerufen.');
  }
  return url;
}

async function leseBegrenzt(antwort) {
  if (!antwort.body) return '';
  const leser = antwort.body.getReader();
  const dekoder = new TextDecoder('utf-8');
  let gelesen = 0;
  let text = '';

  try {
    while (gelesen < MAX_BYTES) {
      const { done, value } = await leser.read();
      if (done) break;
      gelesen += value.length;
      text += dekoder.decode(value, { stream: true });
    }
  } finally {
    await leser.cancel().catch(() => {});
  }

  return text;
}

// Folgt Weiterleitungen von Hand, damit jede Zwischenstation dieselbe Pruefung
// durchlaeuft - eine oeffentliche Adresse kann sonst ins Heimnetz umleiten.
async function holeArtikelSeite(eingabe, fetchImpl = globalThis.fetch) {
  let url = pruefeUrl(eingabe);

  for (let sprung = 0; sprung <= MAX_WEITERLEITUNGEN; sprung += 1) {
    const antwort = await fetchImpl(url.toString(), {
      redirect: 'manual',
      signal: AbortSignal.timeout(TIMEOUT_MS),
      headers: {
        'User-Agent': 'Mozilla/5.0 (Android) BibliotheksHelfer/1.0',
        Accept: 'text/html,application/xhtml+xml'
      }
    });

    if (antwort.status >= 300 && antwort.status < 400) {
      const ziel = antwort.headers.get('location');
      if (!ziel) throw new Error('Weiterleitung ohne Ziel.');
      url = pruefeUrl(new URL(ziel, url).toString());
      continue;
    }

    if (!antwort.ok) {
      throw new Error(`Die Seite antwortet mit Status ${antwort.status}.`);
    }

    const typ = antwort.headers.get('content-type') || '';
    if (!typ.includes('html')) {
      throw new Error('Die Adresse liefert keine HTML-Seite.');
    }

    return { html: await leseBegrenzt(antwort), endUrl: url.toString() };
  }

  throw new Error('Zu viele Weiterleitungen.');
}

module.exports = { holeArtikelSeite, pruefeUrl, istPrivateAdresse };
