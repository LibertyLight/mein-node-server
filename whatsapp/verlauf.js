'use strict';

/**
 * Gespraechsverlauf je WhatsApp-Nummer.
 *
 * Die Messages-API ist zustandslos: bei jeder Anfrage muss der bisherige
 * Verlauf mitgeschickt werden. Er liegt deshalb in derselben SQLite-Datei wie
 * der Rest der Anwendung und ueberlebt so einen Neustart des Servers.
 *
 * Bilder liegen als BLOB in derselben Zeile. Weil sie bei jeder Anfrage
 * mitgehen und jedes Mal aufs Neue kosten, wandern nur die juengsten davon
 * wirklich mit -- aeltere schrumpfen auf einen Hinweis im Text.
 */

/** Rollen, die die API kennt -- alles andere wuerde die Anfrage zerlegen. */
const ROLLEN = new Set(['user', 'assistant']);

function jetztAlsText() {
  return new Date().toISOString();
}

/**
 * Spalten nachruesten, ohne bestehende Verlaeufe zu verlieren: die Tabelle
 * kann aus einer aelteren Fassung stammen, die noch keine Bilder kannte.
 */
function ergaenzeSpalten(db) {
  const vorhanden = new Set(db.prepare('PRAGMA table_info(wa_verlauf)').all().map((s) => s.name));
  if (!vorhanden.has('bild_daten')) db.exec('ALTER TABLE wa_verlauf ADD COLUMN bild_daten BLOB');
  if (!vorhanden.has('bild_typ')) db.exec('ALTER TABLE wa_verlauf ADD COLUMN bild_typ TEXT');
}

function erstelleVerlauf(db, { maxNachrichten = 20, maxBilder = 2 } = {}) {
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
  ergaenzeSpalten(db);

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
    'INSERT INTO wa_verlauf (nummer, rolle, text, bild_daten, bild_typ, erstellt_am) VALUES (?, ?, ?, ?, ?, ?)',
  );
  const lesen = db.prepare(`
    SELECT rolle, text, bild_daten, bild_typ FROM (
      SELECT id, rolle, text, bild_daten, bild_typ FROM wa_verlauf WHERE nummer = ? ORDER BY id DESC LIMIT ?
    ) ORDER BY id ASC
  `);
  const loeschen = db.prepare('DELETE FROM wa_verlauf WHERE nummer = ?');
  const zaehlen = db.prepare('SELECT COUNT(*) AS anzahl FROM wa_verlauf WHERE nummer = ?');
  const merken = db.prepare(
    'INSERT OR IGNORE INTO wa_gesehen (nachricht_id, erstellt_am) VALUES (?, ?)',
  );
  const altesAufraeumen = db.prepare('DELETE FROM wa_gesehen WHERE erstellt_am < ?');

  /**
   * Eine Nachricht anhaengen. Leere Texte lehnt die API ab, also gar nicht
   * erst speichern -- auch zu einem Bild gehoert immer ein Text.
   *
   * @param bild  optional {daten: Buffer, mimeTyp: string}
   */
  function anhaengen(nummer, rolle, text, bild = null) {
    const inhalt = String(text ?? '').trim();
    if (!inhalt || !ROLLEN.has(rolle)) return false;

    einfuegen.run(
      String(nummer),
      rolle,
      inhalt,
      bild ? bild.daten : null,
      bild ? bild.mimeTyp : null,
      jetztAlsText(),
    );
    return true;
  }

  /** Eine Zeile in das Format der Messages-API bringen. */
  function alsNachricht(zeile, mitBild) {
    if (!zeile.bild_daten) return { role: zeile.rolle, content: zeile.text };

    // Aelteres Bild: nur noch der Hinweis, dass da eins war.
    if (!mitBild) return { role: zeile.rolle, content: `[Bild] ${zeile.text}` };

    return {
      role: zeile.rolle,
      content: [
        // Bild vor Text -- so liest Claude es am besten.
        {
          type: 'image',
          source: {
            type: 'base64',
            media_type: zeile.bild_typ,
            data: Buffer.from(zeile.bild_daten).toString('base64'),
          },
        },
        { type: 'text', text: zeile.text },
      ],
    };
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

    // Von hinten nach vorn: nur die juengsten Bilder gehen wirklich mit.
    const vollstaendig = new Set();
    let uebrig = maxBilder;
    for (let i = zeilen.length - 1; i >= 0 && uebrig > 0; i -= 1) {
      if (zeilen[i].bild_daten) {
        vollstaendig.add(i);
        uebrig -= 1;
      }
    }

    return zeilen.map((zeile, i) => alsNachricht(zeile, vollstaendig.has(i)));
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

  return { anhaengen, holen, leeren, schonGesehen, aufraeumen, maxNachrichten, maxBilder };
}

module.exports = { erstelleVerlauf };
