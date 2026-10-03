package de.libertylight.tastatur

/**
 * Findet alle Wörter der Wortliste, die sich mit wenig "Tippfehler-Kosten" aus dem getippten
 * Wort ergeben. Statt jedes Wort einzeln zu vergleichen, läuft die Suche wie in einem Baum durch
 * die alphabetisch sortierte Liste: gemeinsame Anfänge werden nur einmal gerechnet, und sobald ein
 * Anfang schon zu teuer ist, entfällt der ganze Rest.
 *
 * Kosten wie bei der Editierdistanz (Damerau-Levenshtein), aber gewichtet:
 * Nachbartasten, Berührungsstelle, Doppelbuchstaben, vertauschte Buchstaben, fehlende Umlaute.
 * Nicht für mehrere Threads gedacht (benutzt gemeinsame Puffer).
 */
class FuzzySuche(private val modell: Sprachmodell, private val p: Parameter = Parameter()) {

    class Treffer(val index: Int, val kosten: Float)

    private val zeilen = Array(MAX_LAENGE + 2) { FloatArray(MAX_LAENGE + 2) }
    private val zeilenMin = FloatArray(MAX_LAENGE + 2)
    private val kand = CharArray(MAX_LAENGE + 2)

    // Zustand einer Suche
    private var typ = CharArray(0)
    private var m = 0
    private var sub: Array<FloatArray> = emptyArray()
    private var ins = FloatArray(0)
    private var grenze = 0f
    private var treffer: MutableList<Treffer> = ArrayList()

    fun suche(getippt: CharArray, ersetz: Array<FloatArray>, einfuegen: FloatArray, maxKosten: Float): List<Treffer> {
        if (getippt.isEmpty() || getippt.size > MAX_LAENGE) return emptyList()
        typ = getippt
        m = getippt.size
        sub = ersetz
        ins = einfuegen
        grenze = maxKosten
        treffer = ArrayList()
        if (modell.anzahl == 0) return treffer

        // Wurzel: alle getippten Zeichen waeren ueberzaehlig
        zeilen[0][0] = 0f
        for (i in 1..m) zeilen[0][i] = zeilen[0][i - 1] + ins[i - 1]
        zeilenMin[0] = 0f
        zeilenMin[1] = 0f
        besuche(0, modell.anzahl, 0)
        return treffer
    }

    private fun besuche(lo: Int, hi: Int, tiefe: Int) {
        val schluessel = modell.schluessel
        var start = lo
        if (schluessel[lo].length == tiefe) {
            val k = zeilen[tiefe][m]
            if (k <= grenze) treffer += Treffer(lo, k)
            start = lo + 1
        }
        var i = start
        while (i < hi) {
            val c = schluessel[i][tiefe]
            val j = gruppenEnde(i, hi, tiefe, c)
            kand[tiefe + 1] = c
            val kleinste = schritt(tiefe + 1, c)
            zeilenMin[tiefe + 1] = kleinste
            val davor = if (tiefe >= 1) zeilenMin[tiefe] else kleinste
            // Eine Transposition greift auf die Zeile davor zurueck: beide Zeilen beachten
            if (minOf(kleinste, davor) <= grenze && tiefe + 1 < MAX_LAENGE) besuche(i, j, tiefe + 1)
            else if (minOf(kleinste, davor) <= grenze) {
                // zu lang: nur noch das Wort selbst pruefen
                if (schluessel[i].length == tiefe + 1 && zeilen[tiefe + 1][m] <= grenze) treffer += Treffer(i, zeilen[tiefe + 1][m])
            }
            i = j
        }
    }

    /** Ende der Gruppe von Woertern ab [von], die an Stelle [tiefe] dasselbe Zeichen [c] haben. */
    private fun gruppenEnde(von: Int, hi: Int, tiefe: Int, c: Char): Int {
        val schluessel = modell.schluessel
        if (hi - von <= 16) {
            var j = von + 1
            while (j < hi && schluessel[j][tiefe] == c) j++
            return j
        }
        var lo = von + 1
        var h = hi
        while (lo < h) {
            val mitte = (lo + h) ushr 1
            if (schluessel[mitte][tiefe] == c) lo = mitte + 1 else h = mitte
        }
        return lo
    }

    /** Rechnet die Zeile für Tiefe [t] aus den beiden Zeilen davor; gibt das Minimum zurück. */
    private fun schritt(t: Int, c: Char): Float {
        val davor = zeilen[t - 1]
        val davor2 = if (t >= 2) zeilen[t - 2] else null
        val jetzt = zeilen[t]
        val z = Fehlermodell.zeichenNr(c)
        var auslassen = if (t >= 2 && kand[t - 1] == c) p.kostenDoppeltAuslassen else p.kostenAuslassen
        if (t == 1) auslassen += p.ersterBuchstabe
        jetzt[0] = davor[0] + auslassen
        var kleinste = jetzt[0]
        for (i in 1..m) {
            var v = davor[i - 1] + sub[i - 1][z]
            val a = davor[i] + auslassen
            if (a < v) v = a
            val b = jetzt[i - 1] + ins[i - 1]
            if (b < v) v = b
            if (i >= 2) {
                if (davor2 != null && t >= 2 && typ[i - 1] == kand[t - 1] && typ[i - 2] == c) {
                    val tausch = davor2[i - 2] + p.kostenTausch
                    if (tausch < v) v = tausch
                }
                if (digraph(typ[i - 2], typ[i - 1]) == c) {
                    val d = davor[i - 2] + p.kostenDigraph
                    if (d < v) v = d
                }
            }
            jetzt[i] = v
            if (v < kleinste) kleinste = v
        }
        return kleinste
    }

    /** Kosten für ein einzelnes Wort -- dieselbe Rechnung ohne Suchbaum (für gelernte Wörter und Tests). */
    fun kosten(getippt: CharArray, ersetz: Array<FloatArray>, einfuegen: FloatArray, wort: String): Float {
        if (getippt.isEmpty() || getippt.size > MAX_LAENGE || wort.length > MAX_LAENGE) return Float.MAX_VALUE
        typ = getippt
        m = getippt.size
        sub = ersetz
        ins = einfuegen
        zeilen[0][0] = 0f
        for (i in 1..m) zeilen[0][i] = zeilen[0][i - 1] + ins[i - 1]
        for (t in 1..wort.length) {
            kand[t] = wort[t - 1]
            schritt(t, wort[t - 1])
        }
        return zeilen[wort.length][m]
    }

    private fun digraph(a: Char, b: Char): Char = when {
        a == 's' && b == 's' -> 'ß'
        a == 'a' && b == 'e' -> 'ä'
        a == 'o' && b == 'e' -> 'ö'
        a == 'u' && b == 'e' -> 'ü'
        else -> '\u0000'
    }

    companion object {
        const val MAX_LAENGE = 31
    }
}
