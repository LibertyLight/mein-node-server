#!/usr/bin/env python3
"""
Erzeugt die Sprachdaten der Tastatur aus frei verfuegbaren deutschen Texten:

  app/src/main/res/raw/woerter_de.txt   Wort<TAB>q<TAB>g   alphabetisch; q = -2*log2(Wahrscheinlichkeit),
                                        g = Anteil der Grossschreibung mitten im Satz in Prozent
  app/src/main/res/raw/folgen_de.txt    vorher<TAB>nachher:q nachher:q ...   q = -log2(P(nachher|vorher))

Die Schreibweise in woerter_de.txt entscheidet, ob die Autokorrektur ein Wort grossschreibt:
Gross steht dort nur, wenn das Wort mitten im Satz in mindestens 90 % der Faelle gross vorkommt.
Satzanfaenge zaehlen dafuer nicht -- sie sind immer gross.

Quellen (Download-Befehle: siehe WOERTERLISTE.md):
  --untertitel   FrequencyWords de_50k.txt (OpenSubtitles 2018), Alltagssprache
  --leipzig      Saetze der Leipzig Corpora Collection (-sentences.txt), mehrfach angebbar: NAME=PFAD
  --tatoeba      Tatoeba deu_sentences.tsv (gesprochene, kurze Saetze)

Beispiel:
  python3 baue_sprachdaten.py --untertitel de_50k.txt \\
      --leipzig news=deu_news_2023_300K-sentences.txt --leipzig mix=deu_mixed-typical_2011_100K-sentences.txt \\
      --tatoeba tatoeba.tsv --testsaetze testdaten/
"""
import argparse
import collections
import itertools
import math
import re
import sys
from pathlib import Path

import numpy as np

HIER = Path(__file__).resolve().parent
RAW = HIER.parent / "app/src/main/res/raw"
EIGENE = HIER / "eigene_woerter.txt"

BUCHSTABEN = "A-Za-zÄÖÜäöüß"
TOKEN = re.compile(rf"[{BUCHSTABEN}]+(?:[-'’][{BUCHSTABEN}]+)*")
REINES_WORT = re.compile(rf"^[{BUCHSTABEN}]+$")

# Gewicht je Quelle fuer die Einzelwort-Wahrscheinlichkeit (Alltagssprache zaehlt am meisten)
GEWICHT_UNIGRAMM = {"untertitel": 0.40, "tatoeba": 0.25, "news": 0.20, "mix": 0.15, "web": 0.05, "wiki": 0.05}
# Gewicht je Satz fuer die Folgewoerter
GEWICHT_SATZ = {"tatoeba": 1.5, "news": 0.7, "mix": 0.9, "web": 1.0, "wiki": 0.5}
# Tatoeba besteht zu einem Zehntel aus "Tom" und "Maria" -- die sollen nicht die Vorschlaege bestimmen
NAMEN_DAEMPFEN = {"Tom", "Maria", "Mary", "Toms", "Marias", "Marys"}

KONTEXTE = 15000          # so viele Woerter bekommen eine Liste von Nachfolgern
NACHFOLGER = 8            # so viele Nachfolger je Wort
MIN_KONTEXT = 25.0        # Wort muss gewichtet so oft vorkommen
MIN_PAAR = 2.0            # Paar muss gewichtet so oft vorkommen
GROSS_ANTEIL = 0.9        # ab diesem Anteil mitten im Satz gilt ein Wort als grossgeschrieben


def saetze_leipzig(pfad):
    with open(pfad, encoding="utf-8") as f:
        for zeile in f:
            teile = zeile.rstrip("\n").split("\t", 1)
            if len(teile) == 2:
                yield teile[1]


def saetze_tatoeba(pfad, holdout):
    """Jeden zwanzigsten Satz (Id durch 20 teilbar) zurueckhalten -- er dient nur zum Testen."""
    with open(pfad, encoding="utf-8") as f:
        for zeile in f:
            teile = zeile.rstrip("\n").split("\t")
            if len(teile) != 3 or teile[1] != "deu":
                continue
            if int(teile[0]) % 20 == 0:
                holdout.append(teile[2])
            else:
                yield teile[2]


def quellen_laden(args):
    """-> Liste (name, funktion die Saetze liefert)"""
    quellen = []
    for angabe in args.leipzig or []:
        name, pfad = angabe.split("=", 1)
        quellen.append((name, lambda p=pfad: saetze_leipzig(p)))
    if args.tatoeba:
        quellen.append(("tatoeba", lambda: saetze_tatoeba(args.tatoeba, [])))
    return quellen


def schreibe_testsaetze(args):
    if not args.testsaetze:
        return
    ziel = Path(args.testsaetze)
    ziel.mkdir(parents=True, exist_ok=True)
    holdout = []
    for _ in saetze_tatoeba(args.tatoeba, holdout):
        pass
    (ziel / "tatoeba_test.txt").write_text("\n".join(holdout) + "\n", encoding="utf-8")
    print(f"Testsaetze: {len(holdout)} Tatoeba-Saetze zurueckgehalten", file=sys.stderr)


def tokens(satz):
    return TOKEN.findall(satz)


def lies_untertitel(pfad):
    zaehler = {}
    for zeile in open(pfad, encoding="utf-8"):
        w, n = zeile.split()
        if REINES_WORT.match(w):
            zaehler[w.lower()] = zaehler.get(w.lower(), 0) + int(n)
    return zaehler


def pass_eins(quellen):
    """Zaehlt je Quelle: kleingeschriebene Woerter, Schreibweisen mitten im Satz, Gesamtzahl."""
    klein = {}
    mitten = collections.Counter()     # exakte Schreibweise, nicht am Satzanfang
    gesamt = {}
    for name, saetze in quellen:
        z = collections.Counter()
        n = 0
        for satz in saetze():
            ws = tokens(satz)
            for i, w in enumerate(ws):
                if not REINES_WORT.match(w) or len(w) > 30:
                    continue
                if w.isupper() and len(w) > 1:
                    continue    # Abkuerzungen und Ueberschriften (NATO, DSA): wuerden sonst als "nato", "dsa" im Woerterbuch landen
                z[w.lower()] += 1
                n += 1
                if i > 0:
                    mitten[w] += 1
        klein[name], gesamt[name] = z, n
        print(f"  {name}: {n} Woerter, {len(z)} verschiedene", file=sys.stderr)
    return klein, mitten, gesamt


def gross_anteil(schluessel, mitten):
    """Anteil der Grossschreibung mitten im Satz in Prozent, leicht geglaettet (wenig Belege -> nahe 50)."""
    gross = mitten.get(schluessel.capitalize(), 0)
    klein = mitten.get(schluessel, 0)
    return round(100 * (gross + 0.5) / (gross + klein + 1))


def waehle_schreibweise(schluessel, mitten):
    """Gross nur bei klarer Mehrheit mitten im Satz, sonst klein."""
    return schluessel.capitalize() if gross_anteil(schluessel, mitten) >= GROSS_ANTEIL * 100 else schluessel


UMLAUT = {"a": "ä", "o": "ö", "u": "ü"}


def umlautvarianten(w):
    """Alle Schreibweisen, in denen a/o/u zu ä/ö/ü (und ss zu ß) geworden waeren."""
    stellen = [i for i, c in enumerate(w) if c in UMLAUT]
    for r in range(1, min(len(stellen), 3) + 1):
        for auswahl in itertools.combinations(stellen, r):
            yield "".join(UMLAUT[c] if i in auswahl else c for i, c in enumerate(w))
    if "ss" in w:
        yield w.replace("ss", "ß")


def baue(args):
    quellen = quellen_laden(args)
    print("Zaehle Woerter ...", file=sys.stderr)
    klein, mitten, gesamt = pass_eins(quellen)
    untertitel = lies_untertitel(args.untertitel)
    gesamt["untertitel"] = sum(untertitel.values())
    klein["untertitel"] = untertitel

    # Schreibweisen mit Binnenmajuskel (iPhone, WhatsApp): wenn sie mitten im Satz ueberwiegen
    mitten_nach_klein = collections.defaultdict(collections.Counter)
    for form, n in mitten.items():
        mitten_nach_klein[form.lower()][form] += n

    namen = [n for n in klein if n in GEWICHT_UNIGRAMM]
    summe_gewicht = sum(GEWICHT_UNIGRAMM[n] for n in namen)

    alle = set()
    for z in klein.values():
        alle |= set(z)

    def zaehle(w, quelle):
        return klein[quelle].get(w, 0) if quelle in klein else 0

    lexikon = {}   # klein -> (p, schreibweise)
    for w in alle:
        if len(w) < 2:
            continue
        sub = zaehle(w, "untertitel")
        text = sum(zaehle(w, q) for q in namen if q != "untertitel")
        # Untertitel allein reichen nicht: dort stecken OCR-Fehler (lch, lhre), Serienfiguren und
        # englische Woerter. Ein Wort muss auch in echten Texten vorkommen.
        if not (text >= 3 or (text >= 2 and sub >= 200) or (text >= 1 and sub >= 2000)):
            continue
        p = sum(GEWICHT_UNIGRAMM[q] * zaehle(w, q) / gesamt[q] for q in namen) / summe_gewicht
        formen = mitten_nach_klein.get(w)
        schreib = waehle_schreibweise(w, mitten)
        if schreib == w and formen:
            haeufigste, n_haeufigste = formen.most_common(1)[0]
            if haeufigste != w and haeufigste != w.capitalize() and n_haeufigste >= 3 and n_haeufigste >= 0.5 * sum(formen.values()):
                schreib = haeufigste   # iPhone, WhatsApp
        lexikon[w] = (p, schreib, text, gross_anteil(w, mitten))

    # Untertitel sind oft ohne Umlaute geschrieben: "fur", "uber", "konnen". Gibt es in echten Texten
    # eine Umlaut-Schreibweise, die mehr als 100 mal haeufiger ist, fliegt die Umlaut-lose Form raus.
    entfernt = []
    for w in list(lexikon):
        text_w = lexikon[w][2]
        for v in umlautvarianten(w):
            if v in lexikon and lexikon[v][2] >= 100 and lexikon[v][2] > 100 * (text_w + 1):
                del lexikon[w]
                entfernt.append(w)
                break
    print(f"  ohne Umlaut-Fehlschreibungen: {len(entfernt)} entfernt, z. B. {', '.join(entfernt[:12])}", file=sys.stderr)

    # eigene Woerter (Umgangssprache, Orte), falls noch nicht dabei
    if EIGENE.exists():
        for w in EIGENE.read_text(encoding="utf-8").split():
            if w.lower() not in lexikon:
                lexikon[w.lower()] = (3e-7, w, 0, 100 if w[0].isupper() else 0)
            elif w != w.lower():
                # ausdrueckliche Schreibweise (iPhone, WhatsApp, Itzehoe) gewinnt gegen die Statistik.
                # Nur fuer Namen und Marken gedacht -- fuer gewoehnliche Woerter entscheidet der Text.
                p0, _, t0, g0 = lexikon[w.lower()]
                lexikon[w.lower()] = (p0, w, t0, 100 if w[0].isupper() else g0)

    schluessel = sorted(lexikon)
    index = {w: i for i, w in enumerate(schluessel)}
    RAW.mkdir(parents=True, exist_ok=True)
    zeilen = []
    for w in schluessel:
        p, schreib, _, g = lexikon[w]
        q = max(0, min(99, round(-2 * math.log2(p))))
        zeilen.append(f"{schreib}\t{q}\t{g}")
    (RAW / "woerter_de.txt").write_text("\n".join(zeilen) + "\n", encoding="utf-8")
    nomen = sum(1 for w in schluessel if lexikon[w][1][0].isupper())
    print(f"woerter_de.txt: {len(schluessel)} Woerter, davon {nomen} grossgeschrieben", file=sys.stderr)

    folgen_bauen(quellen, index, schluessel, lexikon)


def folgen_bauen(quellen, index, schluessel, lexikon):
    """Zaehlt Wortpaare (inkl. Satzanfang) und schreibt die haeufigsten Nachfolger je Wort."""
    V = len(schluessel)
    SATZANFANG, UNBEKANNT = V, V + 1
    schluessel_liste, gewicht_liste = [], []
    print("Zaehle Folgewoerter ...", file=sys.stderr)
    for name, saetze in quellen:
        g_satz = GEWICHT_SATZ.get(name, 1.0)
        ks, gs = [], []
        for satz in saetze():
            ws = tokens(satz)
            if not ws:
                continue
            g = g_satz * (0.25 if any(w in NAMEN_DAEMPFEN for w in ws) else 1.0)
            vorher = SATZANFANG
            for pos, w in enumerate(ws):
                nr = index.get(w.lower(), UNBEKANNT) if REINES_WORT.match(w) else UNBEKANNT
                # unterstes Bit: mitten im Satz grossgeschrieben (am Satzanfang ist Gross Pflicht und sagt nichts)
                gross = 1 if (pos > 0 and w[:1].isupper() and not w.isupper()) else 0
                ks.append((vorher * (V + 2) + nr) * 2 + gross)
                gs.append(g)
                vorher = nr
        schluessel_liste.append(np.array(ks, dtype=np.int64))
        gewicht_liste.append(np.array(gs, dtype=np.float32))
        print(f"  {name}: {len(ks)} Paare", file=sys.stderr)
    ks = np.concatenate(schluessel_liste)
    gs = np.concatenate(gewicht_liste)
    einmalig, rueck = np.unique(ks, return_inverse=True)
    summen_roh = np.bincount(rueck, weights=gs).astype(np.float64)
    # Paare mit und ohne Grossschreibung zusammenfassen, Mehrheit merken
    paar = einmalig // 2
    flag = einmalig % 2
    paare, rueck2 = np.unique(paar, return_inverse=True)
    summen = np.bincount(rueck2, weights=summen_roh).astype(np.float64)
    gross_summe = np.bincount(rueck2, weights=summen_roh * flag).astype(np.float64)
    gross_mehrheit = gross_summe * 2 > summen
    einmalig = paare
    vorher_nr = einmalig // (V + 2)
    nachher_nr = einmalig % (V + 2)

    kontext_summe = np.bincount(vorher_nr, weights=summen, minlength=V + 2)
    # Kontexte: haeufigste Woerter plus Satzanfang
    kandidaten = [i for i in np.argsort(-kontext_summe[:V])[:KONTEXTE] if kontext_summe[i] >= MIN_KONTEXT]
    kandidaten.append(SATZANFANG)
    gewaehlt = set(int(k) for k in kandidaten)

    maske = np.isin(vorher_nr, list(gewaehlt)) & (nachher_nr < V) & (summen >= MIN_PAAR)
    zeilen = {}
    for v, n, s, gm in zip(vorher_nr[maske], nachher_nr[maske], summen[maske], gross_mehrheit[maske]):
        zeilen.setdefault(int(v), []).append((float(s), int(n), bool(gm)))
    ausgabe = []
    for v in sorted(zeilen, key=lambda x: -kontext_summe[x]):
        name = "<s>" if v == SATZANFANG else schluessel[v]
        eintraege = []
        for s, n, gm in sorted(zeilen[v], reverse=True)[:NACHFOLGER]:
            p = s / kontext_summe[v]
            if p < 0.002:
                continue
            q = min(15, max(0, round(-math.log2(p))))
            # ^ = steht an dieser Stelle meist grossgeschrieben (guten Morgen), auch wenn das Wort sonst klein steht
            eintraege.append(f"{schluessel[n]}:{q}{'^' if gm else ''}")
        if eintraege:
            ausgabe.append(f"{name}\t{' '.join(eintraege)}")
    (RAW / "folgen_de.txt").write_text("\n".join(ausgabe) + "\n", encoding="utf-8")
    print(f"folgen_de.txt: {len(ausgabe)} Woerter mit Nachfolgern", file=sys.stderr)
    for probe in ("<s>", "guten", "wie", "ich", "wo", "vielen"):
        if probe in {a.split("\t")[0] for a in ausgabe}:
            print("   ", next(a for a in ausgabe if a.split("\t")[0] == probe), file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--untertitel", required=True)
    ap.add_argument("--leipzig", action="append", help="NAME=PFAD, z. B. news=...-sentences.txt")
    ap.add_argument("--tatoeba")
    ap.add_argument("--testsaetze", help="Ordner, in den zurueckgehaltene Tatoeba-Saetze zum Testen geschrieben werden")
    args = ap.parse_args()
    schreibe_testsaetze(args)
    baue(args)


if __name__ == "__main__":
    main()
