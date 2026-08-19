const express = require('express');
const { DatabaseSync } = require('node:sqlite');

const { BIBLIOTHEKEN, findeBibliothek, sucheUrl, bereinigeTitel } = require('./lib/bibliotheken');
const { extrahiereMetadaten } = require('./lib/artikel-metadaten');
const { holeArtikelSeite } = require('./lib/artikel-abruf');

const app = express();
const PORT = 3000;

// Native SQLite-Datenbank initialisieren
const db = new DatabaseSync('datenbank.db');

// Tabelle erstellen
db.exec(`
  CREATE TABLE IF NOT EXISTS nachrichten (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    text TEXT NOT NULL,
    erstellt_am TEXT NOT NULL
  )
`);

db.exec(`
  CREATE TABLE IF NOT EXISTS bibliothek_suchen (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    artikel_url TEXT NOT NULL,
    titel TEXT NOT NULL,
    publikation TEXT,
    datum TEXT,
    bibliothek TEXT,
    erstellt_am TEXT NOT NULL
  )
`);

app.use(express.json());
app.use(express.static('public'));

// Alle Einträge abrufen
app.get('/api/nachrichten', (req, res) => {
  const stmt = db.prepare('SELECT * FROM nachrichten ORDER BY id DESC');
  res.json(stmt.all());
});

// Neuen Eintrag anlegen
app.post('/api/nachrichten', (req, res) => {
  const { text } = req.body;
  if (!text || text.trim() === '') {
    return res.status(400).json({ fehler: 'Text darf nicht leer sein' });
  }

  const zeit = new Date().toLocaleTimeString();
  const insert = db.prepare('INSERT INTO nachrichten (text, erstellt_am) VALUES (?, ?)');
  const result = insert.run(text, zeit);

  res.status(201).json({
    id: result.lastInsertRowid,
    text,
    erstellt_am: zeit
  });
});

// Eintrag löschen
app.delete('/api/nachrichten/:id', (req, res) => {
  const deleteStmt = db.prepare('DELETE FROM nachrichten WHERE id = ?');
  deleteStmt.run(Number(req.params.id));
  res.json({ erfolg: true });
});

// --- Bibliotheks-Helfer -----------------------------------------------------
// Sucht einen Artikel ueber den eigenen Bibliothekszugang in der
// Pressedatenbank. Der Server liest nur die Kopfdaten des Artikels und baut
// daraus die Suchadresse - angemeldet wird sich im Browser mit dem eigenen
// Ausweis, hier werden keine Zugangsdaten gespeichert.

app.get('/api/bibliothek/anbieter', (req, res) => {
  res.json(BIBLIOTHEKEN);
});

app.post('/api/bibliothek/analysieren', async (req, res) => {
  const { url, bibliothek, domain } = req.body || {};

  if (typeof url !== 'string' || url.trim() === '') {
    return res.status(400).json({ fehler: 'Bitte eine Artikel-Adresse angeben.' });
  }

  let seite;
  try {
    seite = await holeArtikelSeite(url.trim());
  } catch (fehler) {
    return res.status(400).json({ fehler: fehler.message });
  }

  const metadaten = extrahiereMetadaten(seite.html);
  if (!metadaten.titel) {
    return res.status(422).json({
      fehler: 'Auf der Seite war kein Artikeltitel zu finden. Titel bitte von Hand eintragen.'
    });
  }

  const eintrag = findeBibliothek(bibliothek);
  const zielDomain = eintrag ? eintrag.domain : (typeof domain === 'string' ? domain.trim() : '');

  res.json({
    ...metadaten,
    suchbegriff: bereinigeTitel(metadaten.titel),
    quelle: seite.endUrl,
    suche_url: zielDomain ? sucheUrl(zielDomain, metadaten.titel) : null
  });
});

app.get('/api/bibliothek/verlauf', (req, res) => {
  const stmt = db.prepare('SELECT * FROM bibliothek_suchen ORDER BY id DESC LIMIT 25');
  res.json(stmt.all());
});

app.post('/api/bibliothek/verlauf', (req, res) => {
  const { artikel_url, titel, publikation, datum, bibliothek } = req.body || {};

  if (typeof artikel_url !== 'string' || typeof titel !== 'string' || titel.trim() === '') {
    return res.status(400).json({ fehler: 'Adresse und Titel werden benötigt.' });
  }

  const insert = db.prepare(`
    INSERT INTO bibliothek_suchen (artikel_url, titel, publikation, datum, bibliothek, erstellt_am)
    VALUES (?, ?, ?, ?, ?, ?)
  `);
  const ergebnis = insert.run(
    artikel_url,
    titel.trim(),
    publikation || null,
    datum || null,
    bibliothek || null,
    new Date().toISOString()
  );

  res.status(201).json({ id: ergebnis.lastInsertRowid });
});

app.delete('/api/bibliothek/verlauf/:id', (req, res) => {
  db.prepare('DELETE FROM bibliothek_suchen WHERE id = ?').run(Number(req.params.id));
  res.json({ erfolg: true });
});

// An 0.0.0.0 binden, damit auch die Dashboard-App und andere Geraete im
// WLAN den Server ueber die IP des Handys erreichen.
app.listen(PORT, '0.0.0.0', () => {
  console.log(`Server läuft mit nativer SQLite auf http://localhost:${PORT}`);
  console.log(`Bibliotheks-Helfer:  http://localhost:${PORT}/bibliothek.html`);
});


