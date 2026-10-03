#!/usr/bin/env python3
"""
Erzeugt app/src/main/res/raw/woerter_de.txt -- den Grundwortschatz der Tastatur,
ein Wort pro Zeile, das haeufigste zuerst.

Quellen (vorher herunterladen, siehe WOERTERLISTE.md):
  de_50k.txt    FrequencyWords (OpenSubtitles 2018), CC BY-SA 4.0 -- Alltagssprache
  deu_news_2023_300K-words.txt   Leipzig Corpora Collection -- Gross-/Kleinschreibung
                                 und Woerter aus Nachrichtentexten

Aufruf:  python3 baue_woerterliste.py de_50k.txt deu_news_2023_300K-words.txt
"""
import collections
import itertools
import re
import sys
from pathlib import Path

ZIEL = Path(__file__).resolve().parent.parent / "app/src/main/res/raw/woerter_de.txt"
EIGENE = Path(__file__).resolve().parent / "eigene_woerter.txt"
WORT = re.compile(r"^[a-zA-ZäöüÄÖÜß]+(?:-[a-zA-ZäöüÄÖÜß]+)?$")
TAUSCH = {"a": "ä", "o": "ö", "u": "ü"}


def lies_leipzig(pfad):
    formen = collections.defaultdict(collections.Counter)
    gesamt = collections.Counter()
    for zeile in open(pfad, encoding="utf-8"):
        t = zeile.rstrip("\n").split("\t")
        if len(t) < 3:
            continue
        w, n = t[1], int(t[2])
        if not WORT.match(w) or len(w) > 30:
            continue
        if w.isupper() and len(w) > 1:  # Abkuerzungen, Versalien
            continue
        formen[w.lower()][w] += n
        gesamt[w.lower()] += n
    return formen, gesamt


def umlautvarianten(w):
    stellen = [i for i, c in enumerate(w) if c in TAUSCH]
    for r in range(1, min(len(stellen), 3) + 1):
        for auswahl in itertools.combinations(stellen, r):
            yield "".join(TAUSCH[c] if i in auswahl else c for i, c in enumerate(w))
    if "ss" in w:
        yield w.replace("ss", "ß")


def main(untertitel, leipzig):
    formen, leipzig_gesamt = lies_leipzig(leipzig)

    def schreibweise(klein):
        c = formen.get(klein)
        if not c:
            return klein
        gross = klein[0].upper() + klein[1:]
        # Nur grossschreiben, wenn die Grossform klar ueberwiegt (Nomen).
        # "morgen"/"Morgen", "essen"/"Essen" bleiben klein -- das ist die sichere Wahl.
        return gross if c[gross] > 4 * c[klein] and c[gross] >= 3 else klein

    fw = {}
    for zeile in open(untertitel, encoding="utf-8"):
        w, n = zeile.split()
        fw.setdefault(w, int(n))

    def tippfehler(w, n):
        # Untertitel ohne Umlaute: "fur" statt "für"
        if any(fw.get(v, 0) > 50 * n for v in umlautvarianten(w)):
            return True
        # Seltenes Wort, das in echten Texten nie vorkommt
        return n < 100 and leipzig_gesamt.get(w, 0) == 0

    aus, gesehen = [], set()
    # 1. Alltagssprache, nach Haeufigkeit
    for w, n in fw.items():
        if not WORT.match(w) or n < 8 or tippfehler(w, n):
            continue
        if len(w) == 1 and w not in ("a", "o", "u"):
            continue
        k = w.lower()
        if k not in gesehen:
            gesehen.add(k)
            aus.append(schreibweise(k))
    # 2. Weitere Woerter aus Nachrichtentexten (Beugungsformen, Fachbegriffe)
    for k, n in leipzig_gesamt.most_common():
        if n < 5:
            break
        if k in gesehen or len(k) < 3:
            continue
        if any(leipzig_gesamt.get(v, 0) > 50 * n for v in umlautvarianten(k)):
            continue
        gesehen.add(k)
        aus.append(schreibweise(k))
    # 3. Eigene Woerter (Orte, Umgangssprache), sofern noch nicht dabei
    if EIGENE.exists():
        for w in EIGENE.read_text(encoding="utf-8").split():
            if w.lower() not in gesehen:
                gesehen.add(w.lower())
                aus.append(w)

    ZIEL.write_text("\n".join(aus) + "\n", encoding="utf-8")
    print(f"{len(aus)} Wörter → {ZIEL}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
