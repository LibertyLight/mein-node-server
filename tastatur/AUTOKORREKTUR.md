# Autokorrektur: wie sie arbeitet und wie gut sie ist

Dieses Dokument erklärt, was die Autokorrektur tut, wie sie entscheidet, und mit welchen Messungen
sie abgestimmt wurde. Die Zahlen stammen aus einem simulierten Nutzer, nicht aus echtem Tippen.
Wie weit sie sich übertragen lassen, steht unter „Grenzen“.

## Was beim Tippen passiert

Endet ein Wort mit Leertaste, Satzzeichen oder Enter, entscheidet die Tastatur: **stehen lassen,
korrigieren, trennen oder großschreiben.** Was gleich eingesetzt wird, steht **fett** in der
Vorschlagsleiste. Ein unbekanntes Wort steht dort in „Anführungszeichen“.

| Situation | Ergebnis |
| --- | --- |
| `ihc`, `nicth`, `vileicht` (zwei Fehler in einem Wort; Tippfehler, Wort unbekannt) | wird zum wahrscheinlichsten Wort korrigiert |
| `fur`, `koennen`, `weiss` (Umlaut oder ß weggelassen) | `für`, `können`, `weiß` |
| `ichhabe`, `dasist` (Leerzeichen vergessen) | `ich habe`, `das ist` |
| `haus`, `schule` (alles klein getippt) | `Haus`, `Schule`, wenn das Wort mitten im Satz in mindestens 90 % der Fälle groß steht |
| `IMmer` (Umschalttaste zu lange gehalten) | `Immer` |
| `Gegenstands`, `Fressfeinden` (gebeugte Form eines bekannten Wortes) | bleibt |
| `Haustürschlüssel` (Zusammensetzung aus bekannten Teilen) | bleibt |
| `Itzehoe`, `Kratt`, Namen, Marken | bleiben; nach zweimaligem Schreiben gelten sie als bekannt |
| `NRW`, `iPhone`, `geht's`, `E-Mail`, Wörter mit Ziffern | bleiben |
| Wörter mit nur zwei Buchstaben | bleiben (Abkürzungen und Namen sind dort zu häufig) |
| Adress-, E-Mail- und Passwortfelder, nach `@`, `#`, `/` | keine Autokorrektur |

**⌫ direkt nach einer Korrektur** holt das Getippte zurück. Die Tastatur merkt sich das und ändert
genau diese Schreibweise nie wieder. Dasselbe passiert, wenn du in der Leiste das Getippte (ganz
links) antippst, obwohl eine Korrektur bereitstand.

## Wie sie rechnet

Für ein unbekanntes Wort sucht die Tastatur alle Wörter, die sich mit wenigen Tippfehlern daraus
ergeben könnten, und bewertet jedes:

```
Bewertung(Kandidat) = ln P(Kandidat | Wort davor)  −  Kosten, dass sich daraus das Getippte ergibt
```

- **P(Kandidat | davor)** kommt aus Häufigkeit und Folgewörtern (`SPRACHDATEN`, siehe `WOERTERLISTE.md`),
  dazu ein Bonus für Wörter, die du selbst oft schreibst.
- **Kosten** (in `Fehlermodell`):
  - *Daneben getippt:* Die Tastatur weiß, **wo der Finger aufgesetzt hat**, nicht nur, welche Taste
    getroffen wurde. Ein Druck an der Grenze zwischen `n` und `m` macht „m statt n“ billig, ein Druck
    mitten auf `n` teuer (Gauß-Verteilung der Fingerposition, ln-Verhältnis der Abstände).
  - *Vertauscht, ausgelassen, doppelt, zu viel:* feste Kosten; Doppelbuchstaben sind billiger
    (`Hallo` ↔ `Halo`).
  - *u statt ü, `ss` statt ß, `ae` statt ä:* billig, weil das Eintippen eines Umlauts den Langdruck braucht.
  - Der erste Buchstabe ist selten falsch: Aufschlag.
- **Stehen lassen** hat einen festen Wert (`unbekanntLnP`), erhöht für großgeschriebene Wörter mitten
  im Satz (Namen), gebeugte Formen und Zusammensetzungen. Korrigiert wird nur, wenn der beste
  Kandidat den Wert um mindestens `rand` übertrifft.

Die Suche läuft nicht über alle 100.000 Wörter, sondern wie in einem Baum durch die sortierte Liste
(`FuzzySuche`) und bricht ab, sobald ein Wortanfang zu teuer ist. Eine Abfrage dauert 0,2 bis 0,5 ms (Desktop-JVM; auf dem Handy etwa das Drei- bis Fünffache).

## Wie gut ist sie?

### Messmethode

`KorrekturBewertungTest` lässt einen simulierten Nutzer Wörter tippen: Finger mit Streuung
(0,22 Tastenbreiten) auf der echten Tastenbelegung (`Raster`), dazu Vertauschen, Auslassen, Verdoppeln
und Einfügen sowie faules Tippen ohne Umlaute (die Hälfte der Umlaute). Das ergibt in etwa 15 %
fehlerhafte Wörter. Die Texte sind **nie zum Bauen der Wortliste benutzt worden**: Webtexte,
Wikipedia und ein zurückgehaltenes Zwanzigstel der Tatoeba-Sätze. Die Wortliste wurde aus anderen
Texten gebaut, deshalb kommen dort viele Namen und seltene Wörter vor, die ihr fehlen. Das ist
absichtlich so, denn sie sind der schwerste Fall für eine Autokorrektur.

Der Nutzen, nach dem abgestimmt wurde: ein richtig behobener Tippfehler zählt +1 (mit 0,6 gewichtet,
weil die Simulation mehr Fehler macht als reales Tippen), eine Fehlkorrektur eines richtig
getippten Wortes −6, ein falsch ersetzter Tippfehler −1,5.

### Ergebnis: neu gegen die alte Autokorrektur (Version 0.2.0, gleiche Wortliste, gleiche Tippfehler)

Die alte Autokorrektur (0.2.0) hat bei unbekannten, aber richtig getippten Wörtern (Namen, Fachwörter,
Zusammensetzungen) **24 bis 40 %** verändert, die neue **3,5 bis 5 %**. Dazu kommen rund 10 Prozentpunkte
mehr behobene Tippfehler und etwa ein Fünftel der falsch ersetzten. Den größten Einzelgewinn bringen die
Berührungsstellen (siehe unten). Der Vergleich lief noch mit der alten Version; der Code dafür steckt nicht
mehr im Projekt.

Aktuelle Werte, ausgeliefert (Version 0.3.0), je Textsorte:

| Texte | Tippfehler behoben | falsch ersetzt | bekannte Wörter falsch geändert | unbekannte Wörter falsch geändert | Zeit je Wort |
| --- | --- | --- | --- | --- | --- |
| Web | 88,2 % | 1,6 % | 0,19 % | 3,6 % | 0,47 ms |
| Wikipedia | 88,4 % | 2,1 % | 0,17 % | 4,9 % | 0,46 ms |
| Tatoeba (zurückgehalten) | 90,8 % | 2,6 % | 0,08 % | 4,3 % | 0,24 ms |

### Was die einzelnen Bausteine bringen

Jeweils einer abgeschaltet, alle Korpora zusammen:

| Variante | Nutzen | Tippfehler behoben | falsch ersetzt | saubere Wörter falsch geändert |
| --- | --- | --- | --- | --- |
| so wie ausgeliefert | 1473 | 88,2 % | 1,9 % | 0,35 % |
| ohne Berührungsstellen | 1250 | 81,8 % | 3,1 % | 0,38 % |
| ohne Beugungserkennung | 1385 | 88,9 % | 2,1 % | 0,43 % |
| ohne Zusammensetzungen | 1491 | 89,1 % | 2,0 % | 0,35 % |
| ohne Namens-Bonus | 1466 | 89,3 % | 1,9 % | 0,37 % |
| ohne Folgewörter | 1476 | 88,1 % | 1,9 % | 0,34 % |
| ohne Umlaut-Nachsicht | 1363 | 83,9 % | 2,3 % | 0,35 % |
| Rand 0 (sofort korrigieren) | 1475 | 90,2 % | 1,9 % | 0,38 % |
| Rand 2 (vorsichtiger) | 1397 | 80,4 % | 1,7 % | 0,27 % |
| Rand 4 (sehr vorsichtig) | 1202 | 67,3 % | 1,3 % | 0,20 % |

Berührungsstellen und Umlaut-Nachsicht bringen am meisten. Zusammensetzungen, Namens-Bonus und
Folgewörter sind in der Simulation neutral bis leicht negativ; sie bleiben, weil echte Texte mehr
Namen und lange Wörter enthalten als die Testsätze (Nutzen ± 20 liegt im Rauschen).

### Großschreibung bei faulem Tippen (alles klein)

Schwelle 90 % (in `Parameter.substantivSchwelle`): holt etwa 65 bis 79 % der großgeschriebenen
Wörter zurück und macht 0,1 bis 0,2 % der kleingeschriebenen Wörter zu Unrecht groß. Höhere
Schwellen sind vorsichtiger, holen aber weniger zurück (Schwelle 99: 47 bis 66 %).

### Vorschlagsleiste

Wie oft steht das nächste Wort unter den drei Vorschlägen, ohne dass etwas getippt wurde?
Und nach wie vielen richtig getippten Buchstaben steht das ganze Wort in der Leiste?

| Texte | nächstes Wort: erster Vorschlag | unter den drei | Ergänzung nach 2 Buchstaben | nach 3 | Buchstaben gespart |
| --- | --- | --- | --- | --- | --- |
| Web | 6,9 % | 14,3 % | 25,5 % | 44,0 % | 43 % |
| Wikipedia | 6,0 % | 13,4 % | 24,1 % | 42,9 % | 41 % |
| Tatoeba | 11,2 % | 20,2 % | 38,2 % | 60,9 % | 47 % |

Das nächste Wort zu erraten ist mit Wortlisten aus rund 15 Millionen Wörtern bescheiden. Die
Ergänzung ist brauchbar: Im Mittel spart sie etwa 40 bis 47 % der Buchstaben ein.

## Grenzen

- **Simulation ist nicht echtes Tippen.** Echte Fehler sind anders verteilt, und die Parameter
  stimmen für die simulierten. Die Reihenfolge der Verbesserungen ist belastbar, die absoluten
  Prozentwerte sind es nur ungefähr. Was auf dem Handy auffällt, kann in `Parameter` nachgestellt
  werden: `rand` hoch = vorsichtiger, niedrig = mutiger.
- **Wörter, die ein anderes echtes Wort ergeben** (`bin` statt `bis`), erkennt sie nicht. Dafür
  bräuchte es ein besseres Satzmodell, und das Risiko für Fehlkorrekturen steigt.
- **Zweibuchstabige Wörter** werden nicht angefasst. Tippfehler wie `ud` für `und` blieben
  unkorrigiert, dafür bleiben Abkürzungen heil. Gemessen: 57 bis 70 % der Tippfehler dort wären
  behoben worden, aber 7 bis 15 % der unbekannten Kurzwörter hätte es verändert.
- **Namen und Fachwörter** fehlen der Wortliste und werden manchmal korrigiert. Ein ⌫, und die
  Tastatur lernt das Wort.
- **Satzzeichen im Wort** (`geht's`, `E-Mail`) und Wörter mit Ziffern fasst sie nicht an.

## Messung wiederholen

```bash
cd tastatur
export TASTATUR_TESTDATEN=$PWD/werkzeuge/daten/testdaten   # web.txt, wiki.txt, tatoeba_test.txt (siehe WOERTERLISTE.md)
TASTATUR_ABSTIMMEN=1 ./gradlew testDebugUnitTest --tests '*stimmeAutokorrekturAb*' -i   # Parameter neu abstimmen (~20 Minuten)
```

Ohne `TASTATUR_TESTDATEN` werden diese Tests übersprungen.
