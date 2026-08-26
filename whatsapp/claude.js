'use strict';

/**
 * Anbindung an die Claude Messages-API.
 *
 * Der Verlauf wird bei jeder Anfrage komplett mitgeschickt (die API merkt sich
 * nichts) -- er kommt aus verlauf.js. Hier steht nur, wie gefragt und wie die
 * Antwort ausgewertet wird.
 */

const Anthropic = require('@anthropic-ai/sdk');

/** Antworttexte fuer Faelle, in denen die API zwar antwortet, aber kein Text herauskommt. */
const HINWEISE = {
  abgelehnt: 'Dazu möchte ich lieber nichts sagen. Frag mich gern etwas anderes.',
  abgeschnitten:
    'Meine Antwort wurde zu lang und ist abgeschnitten. Stell die Frage gern kleinteiliger.',
  leer: 'Ich habe darauf gerade keine Antwort zustande gebracht – versuch es bitte noch einmal.',
};

/** Aus den Inhaltsbloecken der Antwort den reinen Text zusammensetzen. */
function leseAntwort(antwort) {
  if (antwort?.stop_reason === 'refusal') return HINWEISE.abgelehnt;

  const text = (antwort?.content || [])
    .filter((block) => block.type === 'text')
    .map((block) => block.text)
    .join('\n')
    .trim();

  if (text) return text;

  // Kein Text, aber Denkbloecke: max_tokens war zu knapp fuer eine Antwort.
  return antwort?.stop_reason === 'max_tokens' ? HINWEISE.abgeschnitten : HINWEISE.leer;
}

/**
 * Verstaendliche Meldung fuer die WhatsApp-Seite. Details gehoeren ins Log,
 * nicht in den Chat -- Fehlertexte der API koennen Interna verraten.
 */
function fehlerText(fehler) {
  if (fehler instanceof Anthropic.AuthenticationError) {
    return 'Der Zugang zu Claude ist gerade nicht gültig. Bitte den API-Schlüssel prüfen.';
  }
  if (fehler instanceof Anthropic.RateLimitError) {
    return 'Gerade sind zu viele Anfragen unterwegs. Bitte in einer Minute noch einmal versuchen.';
  }
  if (fehler instanceof Anthropic.APIConnectionError) {
    return 'Ich erreiche Claude im Moment nicht. Bitte gleich noch einmal versuchen.';
  }
  if (fehler instanceof Anthropic.APIError) {
    return `Claude hat mit einem Fehler geantwortet (${fehler.status}). Bitte später noch einmal versuchen.`;
  }
  return 'Da ist bei mir etwas schiefgelaufen. Bitte gleich noch einmal versuchen.';
}

/**
 * @param konfig  Ergebnis von konfig.lade()
 * @param client  nur fuer Tests: ein Ersatz fuer den echten SDK-Client
 */
function erstelleClaude(konfig, { client } = {}) {
  const anthropic = client || new Anthropic({ apiKey: konfig.apiSchluessel });

  async function antworte(nachrichten) {
    const antwort = await anthropic.messages.create({
      model: konfig.modell,
      max_tokens: konfig.maxTokens,
      system: konfig.systemPrompt,
      // Adaptives Denken: Claude entscheidet selbst, wie viel Nachdenken die
      // Frage braucht. "medium" haelt die Wartezeit im Chat ertraeglich.
      thinking: { type: 'adaptive' },
      output_config: { effort: konfig.aufwand },
      messages: nachrichten,
    });

    return leseAntwort(antwort);
  }

  return { antworte };
}

module.exports = { erstelleClaude, leseAntwort, fehlerText, HINWEISE };
