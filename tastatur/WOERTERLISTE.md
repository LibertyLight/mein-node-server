# Wortliste der Tastatur

`app/src/main/res/raw/woerter_de.txt` enthält rund 66.000 deutsche Wörter,
das häufigste zuerst. Daraus kommen Wortvorschläge und Autokorrektur.

## Quellen

- **FrequencyWords** von Hermit Dave, deutsche Liste 2018 (`de_50k.txt`),
  erzeugt aus OpenSubtitles. Lizenz der Inhalte: **CC BY-SA 4.0**.
  <https://github.com/hermitdave/FrequencyWords>
  Liefert die Alltagssprache und die Häufigkeiten. Die Wörter sind dort alle
  kleingeschrieben.
- **Leipzig Corpora Collection**, `deu_news_2023_300K`, Universität Leipzig.
  <https://wortschatz-leipzig.de/en/download>
  Liefert die Groß- und Kleinschreibung (Nomen) sowie zusätzliche Wörter aus
  Nachrichtentexten. Einen ausdrücklichen Lizenztext habe ich auf der
  Download-Seite nicht gefunden. Bei einer Veröffentlichung (Play Store) also
  vorher die Nutzungsbedingungen prüfen.
- `werkzeuge/eigene_woerter.txt`: handgepflegte Ergänzungen wie „Itzehoe“.

## Neu erzeugen

```bash
cd tastatur/werkzeuge
curl -LO https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_50k.txt
curl -LO https://downloads.wortschatz-leipzig.de/corpora/deu_news_2023_300K.tar.gz
tar xzf deu_news_2023_300K.tar.gz
python3 baue_woerterliste.py de_50k.txt deu_news_2023_300K/deu_news_2023_300K-words.txt
```

Das Skript filtert typische Untertitel-Fehler heraus, etwa „fur“ statt
„für“, und schreibt nur Wörter groß, die fast immer großgeschrieben
vorkommen. Bei „morgen“ und „essen“ bleibt es deshalb bei der Kleinschreibung.
