'use strict';

const Anthropic = require('@anthropic-ai/sdk');

/** Fehler mit HTTP-Status, den die Route direkt an die Tastatur weitergeben kann. */
class KiFehler extends Error {
  constructor(status, nachricht) {
    super(nachricht);
    this.status = status;
  }
}

function leseText(antwort) {
  if (antwort?.stop_reason === 'refusal') {
    throw new KiFehler(422, 'Claude möchte diesen Text nicht bearbeiten.');
  }
  const text = (antwort?.content || [])
    .filter((block) => block.type === 'text')
    .map((block) => block.text)
    .join('\n')
    .trim();
  if (!text) {
    throw new KiFehler(502, antwort?.stop_reason === 'max_tokens'
      ? 'Das Ergebnis wurde zu lang. Bitte einen kürzeren Text wählen.'
      : 'Claude hat kein Ergebnis geliefert. Bitte noch einmal versuchen.');
  }
  return text;
}

/** API-Fehler in eine Meldung fuer die Tastatur uebersetzen; Details gehoeren ins Log. */
function uebersetzeFehler(fehler) {
  if (fehler instanceof KiFehler) return fehler;
  if (fehler instanceof Anthropic.AuthenticationError) {
    return new KiFehler(502, 'Der API-Schlüssel auf dem Server ist ungültig.');
  }
  if (fehler instanceof Anthropic.RateLimitError) {
    return new KiFehler(429, 'Gerade zu viele Anfragen. Bitte gleich noch einmal versuchen.');
  }
  if (fehler instanceof Anthropic.APIConnectionError) {
    return new KiFehler(503, 'Der Server erreicht Claude gerade nicht.');
  }
  if (fehler instanceof Anthropic.APIError) {
    return new KiFehler(502, `Claude hat mit einem Fehler geantwortet (${fehler.status}).`);
  }
  return new KiFehler(500, 'Auf dem Server ist etwas schiefgelaufen.');
}

/**
 * @param konfig  Ergebnis von konfig.lade()
 * @param client  nur fuer Tests: Ersatz fuer den echten SDK-Client
 */
function erstelleClaude(konfig, { client } = {}) {
  const anthropic = client || new Anthropic({ apiKey: konfig.apiSchluessel });

  async function bearbeite({ system, nachrichten }) {
    const antwort = await anthropic.beta.messages.create({
      model: konfig.modell,
      max_tokens: konfig.maxTokens,
      system,
      // Thinking bleibt adaptiv (Vorgabe); der Aufwand bestimmt, wie lange es dauert.
      output_config: { effort: konfig.aufwand },
      // Lehnt ein Sicherheitsfilter ab, springt serverseitig ein passendes Modell ein.
      betas: ['server-side-fallback-2026-07-01'],
      fallbacks: 'default',
      messages: nachrichten,
    });
    return leseText(antwort);
  }

  return { bearbeite };
}

module.exports = { erstelleClaude, leseText, uebersetzeFehler, KiFehler };
