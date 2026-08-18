const express = require('express');
const { DatabaseSync } = require('node:sqlite');

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

app.listen(PORT, () => {
  console.log(`Server läuft mit nativer SQLite auf http://localhost:${PORT}`);
});


