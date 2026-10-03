# Sprachdaten der Tastatur

Zwei Dateien in `app/src/main/res/raw/` tragen Wortvorschläge und Autokorrektur. Beide werden von
`werkzeuge/baue_sprachdaten.py` aus frei verfügbaren deutschen Texten erzeugt.

| Datei | Inhalt | Größe |
| --- | --- | --- |
| `woerter_de.txt` | rund 100.000 Wörter, alphabetisch; je Wort die Schreibweise, `q` (Maß für die Häufigkeit: −2·log₂ der Wahrscheinlichkeit) und `g` (wie oft das Wort mitten im Satz großgeschrieben steht, in Prozent) | 1,5 MB |
| `folgen_de.txt` | zu den ~15.000 häufigsten Wörtern die acht wahrscheinlichsten Folgewörter, dazu der Satzanfang | 0,8 MB |

## Quellen

| Quelle | Verwendung | Lizenz |
| --- | --- | --- |
| **FrequencyWords** von Hermit Dave, deutsche Liste 2018 (`de_50k.txt`), aus OpenSubtitles. <https://github.com/hermitdave/FrequencyWords> | Häufigkeit der Alltagssprache (40 % Gewicht) | CC BY-SA 4.0 |
| **Tatoeba**, deutsche Sätze (`deu_sentences.tsv`). <https://tatoeba.org> | kurze Alltagssätze: Häufigkeit (25 %), Folgewörter | CC BY 2.0 FR |
| **Leipzig Corpora Collection**, `deu_news_2023_300K` und `deu_mixed-typical_2011_100K`, Universität Leipzig. <https://wortschatz-leipzig.de> | Nachrichten- und Mischtexte: Häufigkeit (35 %), Folgewörter, Großschreibung | nach Angabe der Projektseite CC BY; im Download liegt keine Lizenzdatei |
| `werkzeuge/eigene_woerter.txt` | Umgangssprache, Marken, Orte (von Hand) | – |

**Zur Weitergabe:** Die beiden erzeugten Dateien sind aus den Quellen abgeleitet. Wegen der
ShareAlike-Bedingung von FrequencyWords sollten sie unter **CC BY-SA 4.0** weitergegeben werden,
mit Namensnennung der drei Quellen. Für den privaten Gebrauch spielt das keine Rolle. Vor einer
Veröffentlichung (z. B. im Play Store) bitte Lizenzfragen nochmals prüfen, besonders die
fehlende Lizenzdatei im Leipziger Download.

## Was die Pipeline macht

- **Aufnahme:** Ein Wort kommt in die Liste, wenn es in echten Texten vorkommt (mindestens dreimal, oder
  zweimal bei häufigem Untertitelwort). Untertitel allein reichen nicht: Dort stecken OCR-Fehler
  (`lch` statt `Ich`), Serienfiguren und englische Wörter.
- **Abkürzungen** in Großbuchstaben (NATO, DSA) zählen nicht als Wörter, sonst würde `dsa` nicht korrigiert.
- **Wörter ohne Umlaut** (`fur`, `uber`, `konnen`) fliegen raus, wenn es in echten Texten die Umlaut-Form
  mehr als hundertmal häufiger gibt. Untertitel sind oft ohne Umlaute geschrieben.
- **Großschreibung:** Ein Wort gilt als Substantiv, wenn es mitten im Satz in mindestens 90 % der Fälle
  großgeschrieben vorkommt. Satzanfänge zählen nicht, dort ist Groß Pflicht. Deshalb bleiben
  `essen`/`Essen` und `morgen`/`Morgen` klein: das ist die sichere Wahl.
- **Folgewörter:** Die Paare werden gewichtet gezählt, `Tom` und `Maria` aus Tatoeba nur mit Viertelgewicht.
  Ein `^` hinter dem Folgewort heißt, dass es an dieser Stelle meist großgeschrieben steht
  (`guten Morgen`, `vielen Dank`).

## Neu erzeugen

Die Rohdaten kommen in `werkzeuge/daten/` (wird von Git ignoriert, zusammen rund 1 GB).

```bash
cd tastatur/werkzeuge && mkdir -p daten && cd daten
L=https://downloads.wortschatz-leipzig.de/corpora
curl -LO https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_50k.txt
curl -LO https://downloads.tatoeba.org/exports/per_language/deu/deu_sentences.tsv.bz2 && bunzip2 -k deu_sentences.tsv.bz2
curl -LO $L/deu_news_2023_300K.tar.gz            && tar xzf deu_news_2023_300K.tar.gz
curl -LO $L/deu_mixed-typical_2011_100K.tar.gz   && tar xzf deu_mixed-typical_2011_100K.tar.gz --wildcards "*-sentences.txt"
cd ..
python3 baue_sprachdaten.py --untertitel daten/de_50k.txt \
    --leipzig news=daten/deu_news_2023_300K/deu_news_2023_300K-sentences.txt \
    --leipzig mix=daten/deu_mixed-typical_2011_100K/deu_mixed-typical_2011_100K-sentences.txt \
    --tatoeba daten/deu_sentences.tsv --testsaetze daten/testdaten
```

Das Skript braucht `numpy` und läuft etwa 35 Sekunden. `--testsaetze` legt jeden zwanzigsten
Tatoeba-Satz beiseite (`tatoeba_test.txt`). Er fließt nicht in die Wortliste ein, sondern dient nur
zum Testen (siehe `AUTOKORREKTUR.md`).

### Testtexte, die nie zum Bauen benutzt werden

Für die Messung der Autokorrektur kommen zwei weitere Korpora dazu. Sie stecken **nicht** in der
Wortliste, damit der Test ehrlich bleibt:

```bash
cd tastatur/werkzeuge/daten
curl -LO $L/deu-de_web_2021_100K.tar.gz && tar xzf deu-de_web_2021_100K.tar.gz --wildcards "*-sentences.txt"
curl -LO $L/deu_wikipedia_2021_100K.tar.gz && tar xzf deu_wikipedia_2021_100K.tar.gz --wildcards "*-sentences.txt"
cp deu-de_web_2021_100K/*-sentences.txt testdaten/web.txt
cp deu_wikipedia_2021_100K/*-sentences.txt testdaten/wiki.txt
export TASTATUR_TESTDATEN=$PWD/testdaten
```
