'use strict';

/**
 * Konfiguration der WhatsApp-Anbindung.
 *
 * Zugangsdaten stehen ausschliesslich in Umgebungsvariablen -- sie gehoeren
 * weder ins Repository noch in die Datenbank. Fehlt etwas, startet der Bot
 * nicht und sagt beim Start, was fehlt, statt spaeter still 401er zu kassieren.
 */

const VORGABEN = {
  graphVersion: 'v23.0',
  modell: 'claude-opus-5',
  aufwand: 'medium',
  maxTokens: 8000,
  verlaufNachrichten: 20,
  whisperUrl: 'https://api.openai.com/v1',
  whisperModell: 'whisper-1',
  maxAudioMB: 20,
  systemPrompt: [
    'Du bist Claude und antwortest über WhatsApp.',
    'Fasse dich kurz: eine WhatsApp-Nachricht fasst höchstens 4096 Zeichen.',
    'Verzichte auf Markdown-Überschriften, Tabellen und Code-Blöcke mit Zeilennummern –',
    'WhatsApp stellt das nicht dar. Für Betonung genügen *Sternchen*.',
    'Antworte in der Sprache, in der du angeschrieben wirst.',
  ].join(' '),
};

/** Alles ausser Ziffern entfernen -- Meta liefert Nummern ohne "+", Menschen tippen es mit. */
function normalisiereNummer(nummer) {
  return String(nummer || '').replace(/\D/g, '');
}

function liste(wert) {
  return String(wert || '')
    .split(',')
    .map((eintrag) => eintrag.trim())
    .filter(Boolean);
}

function zahl(wert, vorgabe) {
  const geparst = Number(wert);
  return Number.isFinite(geparst) && geparst > 0 ? geparst : vorgabe;
}

function lade(umgebung = process.env) {
  const nummern = liste(umgebung.WHATSAPP_ERLAUBTE_NUMMERN);
  const alleErlaubt = nummern.some((eintrag) => eintrag.toLowerCase() === 'alle');

  return {
    apiSchluessel: umgebung.ANTHROPIC_API_KEY || '',
    token: umgebung.WHATSAPP_TOKEN || '',
    telefonId: umgebung.WHATSAPP_TELEFON_ID || '',
    pruefToken: umgebung.WHATSAPP_PRUEF_TOKEN || '',
    appGeheimnis: umgebung.WHATSAPP_APP_GEHEIMNIS || '',
    erlaubteNummern: alleErlaubt ? [] : nummern.map(normalisiereNummer).filter(Boolean),
    alleErlaubt,
    graphVersion: umgebung.WHATSAPP_GRAPH_VERSION || VORGABEN.graphVersion,
    modell: umgebung.CLAUDE_MODELL || VORGABEN.modell,
    aufwand: umgebung.CLAUDE_AUFWAND || VORGABEN.aufwand,
    maxTokens: zahl(umgebung.CLAUDE_MAX_TOKENS, VORGABEN.maxTokens),
    verlaufNachrichten: zahl(umgebung.WHATSAPP_VERLAUF_NACHRICHTEN, VORGABEN.verlaufNachrichten),
    systemPrompt: umgebung.CLAUDE_SYSTEM_PROMPT || VORGABEN.systemPrompt,

    // Sprachnachrichten sind freiwillig: ohne Whisper laeuft der Rest weiter.
    // Ein lokaler Whisper-Server braucht keinen Schluessel, deshalb genuegt
    // eine der beiden Angaben, um die Transkription einzuschalten.
    transkription: Boolean(umgebung.WHISPER_API_KEY || umgebung.WHISPER_URL),
    whisperUrl: umgebung.WHISPER_URL || VORGABEN.whisperUrl,
    whisperSchluessel: umgebung.WHISPER_API_KEY || '',
    whisperModell: umgebung.WHISPER_MODELL || VORGABEN.whisperModell,
    whisperSprache: umgebung.WHISPER_SPRACHE || '',
    maxAudioBytes: zahl(umgebung.WHATSAPP_MAX_AUDIO_MB, VORGABEN.maxAudioMB) * 1024 * 1024,
    transkriptZeigen: umgebung.WHATSAPP_TRANSKRIPT_ZEIGEN !== '0',
  };
}

const PFLICHT = [
  ['apiSchluessel', 'ANTHROPIC_API_KEY'],
  ['token', 'WHATSAPP_TOKEN'],
  ['telefonId', 'WHATSAPP_TELEFON_ID'],
  ['pruefToken', 'WHATSAPP_PRUEF_TOKEN'],
  ['appGeheimnis', 'WHATSAPP_APP_GEHEIMNIS'],
];

/** Namen der Umgebungsvariablen, ohne die der Bot nicht laufen kann. */
function fehlendeAngaben(konfig) {
  const fehlt = PFLICHT.filter(([feld]) => !konfig[feld]).map(([, name]) => name);

  // Ohne Freigabeliste koennte jeder, der die Geschaeftsnummer kennt, auf
  // Kosten des Betreibers mit Claude reden. Bewusst oeffnen: "=alle".
  if (!konfig.alleErlaubt && konfig.erlaubteNummern.length === 0) {
    fehlt.push('WHATSAPP_ERLAUBTE_NUMMERN');
  }

  return fehlt;
}

function istVollstaendig(konfig) {
  return fehlendeAngaben(konfig).length === 0;
}

/** Darf diese Nummer mit dem Bot reden? */
function istFreigegeben(konfig, nummer) {
  if (konfig.alleErlaubt) return true;
  return konfig.erlaubteNummern.includes(normalisiereNummer(nummer));
}

module.exports = {
  VORGABEN,
  lade,
  fehlendeAngaben,
  istVollstaendig,
  istFreigegeben,
  normalisiereNummer,
};
