'use strict';

/**
 * Gespraechsverlauf je WhatsApp-Nummer.
 *
 * Die Messages-API ist zustandslos: bei jeder Anfrage muss der bisherige
 * Verlauf mitgeschickt werden. Er liegt deshalb in derselben SQLite-Datei wie
 * der Rest der Anwendung und ueberlebt so einen Neustart des Servers.
 */

/** Rollen, die die API kennt -- alles andere wuerde die Anfrage zerlegen. */
const ROLLEN = new Set(['user', 'assistant']);

function jetztAlsText() {
  return new Date().toISOString();
}

function erstelleVerlauf(db, { maxNachrichten = 20 } = {}) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS wa_verlauf (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      nummer TEXT NOT NULL,
      rolle TEXT NOT NULL,
      text TEXT NOT NULL,
      erstellt_am TEXT NOT NULL
    )
  `);
  db.exec('CREATE INDEX IF NOT EXISTS wa_verlauf_nummer_idx ON wa_verlauf (nummer, id)');

  // Meta stellt Webhooks "mindestens einmal" zu und wiederholt sie bei jedem
  // Zeitueberschreiten. Ohne Gedaechtnis wuerde derselbe Satz mehrfach
  // beantwortet -- und mehrfach abgerechnet.
  db.exec(`
    CREATE TABLE IF NOT EXISTS wa_gesehen (
      nachricht_id TEXT PRIMARY KEY,
      erstellt_am TEXT NOT NULL
    )
  `);

  const einfuegen = db.prepare(
    'INSERT INTO wa_verlauf (nummer, rolle, text, erstellt_am) VALUES (?, ?, ?, ?)',
  );
  const lesen = db.prepare(`
    SELECT rolle, text FROM (
      SELECT id, rolle, text FROM wa_verlauf WHERE nummer = ? ORDER BY id DESC LIMIT ?
    ) ORDER BY id ASC
  `);
  const loeschen = db.prepare('DELETE FROM wa_verlauf WHERE nummer = ?');
  const zaehlen = db.prepare('SELECT COUNT(*) AS anzahl FROM wa_verlauf WHERE nummer = ?');
  const merken = db.prepare(
    'INSERT OR IGNORE INTO wa_gesehen (nachricht_id, erstellt_am) VALUES (?, ?)',
  );
  const altesAufraeumen = db.prepare('DELETE FROM wa_gesehen WHERE erstellt_am < ?');

  /** Eine Nachricht anhaengen. Leere Texte lehnt die API ab, also gar nicht erst speichern. */
  function anhaengen(nummer, rolle, text) {
    const inhalt = String(text ?? '').trim();
    if (!inhalt || !ROLLEN.has(rolle)) return false;
    einfuegen.run(String(nummer), rolle, inhalt, jetztAlsText());
    return true;
  }

  /**
   * Den Verlauf im Format der Messages-API liefern.
   *
   * Durch das Kuerzen kann vorne eine Assistenten-Antwort stehen bleiben --
   * die API verlangt aber, dass der Verlauf mit "user" beginnt.
   */
  function holen(nummer) {
    const zeilen = lesen.all(String(nummer), maxNachrichten);
    while (zeilen.length > 0 && zeilen[0].rolle !== 'user') zeilen.shift();
    return zeilen.map((zeile) => ({ role: zeile.rolle, content: zeile.text }));
  }

  function leeren(nummer) {
    const vorher = zaehlen.get(String(nummer))?.anzahl ?? 0;
    loeschen.run(String(nummer));
    return Number(vorher);
  }

  /**
   * true, wenn diese Nachrichten-ID schon einmal da war. Der Einfuegeversuch
   * ist die Pruefung: so koennen zwei gleichzeitige Zustellungen nicht beide
   * durchrutschen.
   */
  function schonGesehen(nachrichtId) {
    if (!nachrichtId) return false;
    const ergebnis = merken.run(String(nachrichtId), jetztAlsText());
    return Number(ergebnis.changes) === 0;
  }

  /** Alte Merkposten wegwerfen -- Meta wiederholt hoechstens einige Stunden. */
  function aufraeumen(maxAlterTage = 7) {
    const grenze = new Date(Date.now() - maxAlterTage * 86400000).toISOString();
    return Number(altesAufraeumen.run(grenze).changes);
  }

  return { anhaengen, holen, leeren, schonGesehen, aufraeumen, maxNachrichten };
}

module.exports = { erstelleVerlauf };
